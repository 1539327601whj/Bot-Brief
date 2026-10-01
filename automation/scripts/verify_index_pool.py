#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验「低估精选」指数池：池子自洽、来源可达、口径纪律、与行情源一致。

为什么需要这个脚本
==================

池子从 7 只宽基扩到上百个指数之后，出错的方式从「报错」变成了「静默取错数」：

* 一个**抄错或没验过的 `csindexCode`**（手抄串了数字、或者那个代码指的是另一个指数）
  会安安稳稳地取回一条像模像样的 PE 曲线——卡片上数字正常、口径正常，
  **没有任何地方看得出来它取的是别的指数**。中证官网那份响应的**每一行**都带
  `indexName`（`000300` → 「沪深300」），这是唯一能自动对上的自证：拿池子里的名字和它对，
  对不上就停下来问人。
* 「新浪的基金规模用 `nmc`、不用 `mktcap`」是一条**有证据的纪律**（C1 组就是那张证据）。
  证据一旦不再成立，纪律就退化成了口口相传的传说，而 `mktcap` 会被某个「优化」重新引进来。
* 「csindex 与蛋卷是两个口径、不可横向比较」同理：C2 组把它当成断言跑，
  两边一旦收敛，那句话就该重写而不是继续沿用。

A 组里有几条规则是照 `IndexFundPool.parseAndValidate` 抄的：运行时会因为那些问题**让应用起不来**。
两边的关系是单向的——**预检只允许更严，不允许更松**。预检放过去一批池子、再在容器启动时炸掉，
比预检直接说「不要」糟得多。所以 A 组比 Java 多查几件事（`indexCode` 的形态、`market` 与 ETF
代码前缀是否自洽、`csindexCode` 有没有被两个指数共用、7 只旧宽基还在不在）；反过来，Java 允许
`valuationSource` 为空（阶段 1 就是这么上线的），A 组把它当**欠账**报出来——因为那正是
「卡片上一片空白」的成因，是这次要消灭的东西。

跑法（Windows 上 `python` 是商店占位符，必须用 `py -3`）：

    py -3 automation/scripts/verify_index_pool.py                # 全跑
    py -3 automation/scripts/verify_index_pool.py --local-only   # 只跑 A 组，不联网

只读：不发任何写请求，不入库，不推送。
退出码 0 = 全部通过；1 = 有不一致，**先查清楚，再往池子里搬条目**。

⚠️ 这个脚本会打二十来次外呼，其中中证官网那几次每次约 320KB / 0.5–1.2 秒，而且中证是
按 IP 限流的。**不要连着反复跑**，失败也等一阵再来。改完池子先跑 `--local-only`。

这里是**有意**重新实现了一遍新浪清单与腾讯报价的解析（而不是 import build_index_pool）：
校验脚本要和被校验对象共用实现才能验得动的地方才共用，而「分页会不会漏掉深市那一段」
「腾讯按逗号批量到底回不回得全」恰恰是要验的东西，共用就等于自证。
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime

_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

import etf_report as report  # noqa: E402

_ROOT = os.path.dirname(os.path.dirname(_SCRIPTS_DIR))
POOL_PATH = os.path.join(_ROOT, "backend", "src", "main", "resources", "screener", "index-pool.json")
# 别名表。名字自证要用它：池子里写「上证红利」，中证官网回的是「红利指数」，
# 而这不是取错代码，只是项目用了俗名。别名表正是「这个指数还叫什么」的存放处。
SEED_PATH = os.path.join(_ROOT, "automation", "agents", "index_pool_seed.json")

VALUATION_SOURCE_CSINDEX = report.VALUATION_SOURCE_CSINDEX
VALUATION_SOURCE_DANJUAN = report.VALUATION_SOURCE_DANJUAN
CSINDEX = VALUATION_SOURCE_CSINDEX
DANJUAN = VALUATION_SOURCE_DANJUAN

CATEGORIES = ("broad", "strategy", "sector", "theme", "overseas", "other")
# 池子里 valuationSource 与 percentileMethod 必须一一对应。方法名描述「怎么算的」，
# 可比性由 valuationSource 承载——所以这两者一旦对不上，页面上就会同时出现
# 「同方法不同来源」和「同来源不同方法」两种没法解释的组合。
METHOD_BY_SOURCE = {
    CSINDEX: report.CSI_PE_TTM_ROLLING_10Y,
    DANJUAN: report.DANJUAN_PE_TTM_PROVIDER,
}
# 这 7 只是池子的起点。它们从池子里消失不代表错，但那一定是有人**故意**改的，
# 而不是某次重建时顺手丢的——所以停下来问一句。
LEGACY_INDEX_CODES = (
    "SH000300", "SH000905", "SH000016", "SZ399006",
    "SH000688", "SH000852", "SH000015",
)

# `indexCode` 的前缀由代码号段决定，不按「看着像哪就用哪个」推。
#
# 这一份是**独立复制**的，故意不 import build_index_pool：被校验的正是「生成器按什么规则
# 拼 key」，共用实现就等于自证。两边只改一边会被这里挡下。
#
# 为什么要管前缀：`indexCode` 是 `market_valuation_history.index_code` 的键。蛋卷对同一个
# 号段有两种写法——「动漫游戏」是 `SH930901`，「红利低波」是 `CSI930740`，两个都是 93 开头
# 的中证自编指数。照抄蛋卷的写法，同一个指数换一条路径进来就会在库里多长出一个 key，
# 两份估值并存、两张卡都显示正常，**页面上看不出是错的**。
SEGMENT_PREFIX = (
    (("399", "980"), "SZ"),   # 深证 / 国证自编
    (("000", "950"), "SH"),   # 上证 / 中证交易所公布
)
# 其余（930/931/716/H30/H11…）是**中证自编**指数，不是沪深交易所公布的代码。
# 写 `SH930713` 会读成「上交所的 930713」，所以单独一个前缀。
DEFAULT_PREFIX = "CSI"
OVERSEAS = "overseas"


def index_code_prefix(code: str) -> str:
    """6 位中证/深证代码 → `indexCode` 前缀。"""
    for segments, prefix in SEGMENT_PREFIX:
        if code.startswith(segments):
            return prefix
    return DEFAULT_PREFIX

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

SINA_ETF_URL = (
    "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
    "Market_Center.getHQNodeData"
)
SINA_ETF_PAGES = 40
SINA_ETF_PAGE_SIZE = 100
# 实测 1693 只。这条下限是防**静默截断**的：页数写少一段，清单不会报错，
# 只会少掉尾部那一段，而 D 组于是报「池子里的 ETF 在新浪清单里找不到」——
# 那是假故障，真故障是分页参数。见 build_index_pool.fetch_sina_etfs 的注释。
SINA_ETF_MIN_COUNT = 1000
NMC_WAN_PER_YI = 10000

TENCENT_QUOTE_URL = "https://qt.gtimg.cn/q="
# 腾讯的字段位置是数出来的，不是文档写的。idx37=成交额(万元)、idx45=总市值(亿)。
# 实测 88 个字段；少于这个数说明格式变了，那两个下标就得重新确认。
TENCENT_FIELD_AMOUNT_WAN = 37
TENCENT_FIELD_MARKET_CAP_YI = 45
TENCENT_FIELD_COUNT = 88

CSINDEX_RAW_URL = "https://www.csindex.com.cn/csindex-home/perf/indexCsiDsPe"
# 中证官网每次约 320KB / 0.5–1.2 秒。抽样默认 5 条：抽查不是普查，
# 它防的是「整条链路的口径变了」，不是「第 137 个代码抄错了一位」——
# 后者靠搬条目时的那一次全查（--csindex-limit 0）。
CSINDEX_SAMPLE_DEFAULT = 5
# 生产解析路径也要跑一次：本脚本自己解析一遍只能证明**本脚本**读得懂，
# 证明不了 index_pool_sync 真正调用的那个函数读得懂。
CSINDEX_PRODUCTION_PROBE = "000300"
# 点数下限。计划里写的是 >1000，但科创100（000698）只有 843 个点、
# 更年轻的指数只会更少——用 1000 当硬门槛，会让「历史短」被误报成「取数失败」。
# 所以硬门槛压到 250（够算一年分位），不足 1000 的另行提示：
# **分位是短窗口分位，卡片上必须能看出该指数的历史长度。**
CSINDEX_MIN_POINTS = 250
CSINDEX_SHORT_HISTORY_POINTS = 1000
CSINDEX_MAX_STALENESS_DAYS = report.CURRENT_VALUATION_MAX_STALENESS_DAYS

CSINDEX_JITTER_SECONDS = (0.2, 0.5)

# C1 的三个探针是**写死的**，不从池子里取：它验的是「新浪的字段口径」，
# 这份证据必须不随池子内容漂移，否则池子一改，纪律的证据就跟着没了。
# 159915 必须写 `sz`——腾讯对市场前缀写错的代码是**静默丢弃**，不报错也不回空值，
# 请求 `sh159915` 只会让这个探针凭空消失。
SCALE_PROBES = ("sh510300", "sz159915", "sh588000")

# C2 的口径探针同样写死。close = 两边应当吻合（防止本组恒真），far = 两边应当背离。
PE_CALIBER_PROBES = (
    ("000300", "SH000300", "close", 0.05),
    ("000905", "SH000905", "far", 0.10),
    ("000852", "SH000852", "far", 0.10),
    ("000688", "SH000688", "far", 0.10),
)
PE_CALIBER_CODES = tuple(code for code, _, _, _ in PE_CALIBER_PROBES)


class Checker:
    """一边打印一边攒失败。

    特意做成对象而不是模块级全局：A–D 每组都能拿合成响应直接调，
    不然「故意把 nmc 换成 mktcap 它到底报不报错」就验不了——
    **一个永远返回 0 的校验脚本比没有校验更糟。**
    """

    def __init__(self) -> None:
        self.failures: list[str] = []
        self.notes: list[str] = []
        # 实际读的那份池子（--pool 可以指向提案文件）。只影响打印，
        # 但打印错了会让人把「提案验过」当成「正式池子验过」。
        self.pool_path: str | None = None

    def _emit(self, text: str) -> None:
        print(text)

    def section(self, title: str) -> None:
        self._emit(f"\n=== {title} ===")

    def check(self, label: str, ok: bool, detail: str = "") -> None:
        self._emit(f"  [{'OK  ' if ok else 'FAIL'}] {label}" + (f" — {detail}" if detail else ""))
        if not ok:
            self.failures.append(f"{label} {detail}".strip())

    def note(self, text: str) -> None:
        self._emit(f"  ...  {text}")
        self.notes.append(text)

    def line(self, text: str) -> None:
        self._emit(text)


def complain(ok: bool, detail: str) -> str:
    """只在失败时写出「为什么」。

    OK 那一行挂着一句失败文案，读起来像是出错了——一个 200 行的输出里，
    每 3 行就有一句「……会被跳过」，人就会开始整体忽略这个脚本。
    """
    return "" if ok else detail


# ----------------------------------------------------------------------
# 取数（每个都单独一层，测试直接替换这些名字就行）
# ----------------------------------------------------------------------

def fetch_json(url: str, params: dict | None = None, headers: dict | None = None,
               timeout: int = 20, encoding: str = "utf-8"):
    """拼好 URL 再发。刻意不走 requests：校验脚本要在「什么依赖都没装」的机器上能跑。"""
    query = urllib.parse.urlencode(params or {})
    request = urllib.request.Request(
        f"{url}?{query}" if query else url,
        headers={"User-Agent": UA, "Accept": "application/json, text/plain, */*",
                 **(headers or {})},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode(encoding, "replace"))


def load_pool(path: str | None = None) -> list[dict]:
    # 默认值在**调用时**取 POOL_PATH，不写成默认参数：默认参数是定义时就绑死的，
    # 那样测试改 POOL_PATH 不会生效，会静默去读生产那份池子。
    path = path or POOL_PATH
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    indices = data.get("indices")
    if not isinstance(indices, list) or not indices:
        raise RuntimeError(f"{path} 里没有 indices 数组")
    return indices


def fetch_danjuan() -> dict[str, dict]:
    """一次拿回蛋卷全部指数。走生产那个函数——它的缓存与验收规则正是要验的东西。"""
    return report.fetch_danjuan_items()


def name_matches(official: str, known: set[str]) -> bool:
    """官网名与池子里的名字是不是同一个指数。

    **不用「包含」**：包含会让 000306 的「沪深300金融」被认成 000300 的「沪深300」，
    而「这个代码指向的其实是另一个指数」正是这条检查唯一能自动抓到的东西。

    中证官网对一部分指数带「指数」后缀（`000922` 回「中证红利指数」，池子里叫
    「中证红利」），所以两边各自去掉后缀再精确比。俗名差异（项目里叫「上证红利」、
    官网叫「红利指数」）走别名表，不靠放松比较。
    """
    if official in known:
        return True
    bare = official.removesuffix("指数")
    return any(name.removesuffix("指数") == bare for name in known)


def load_seed_aliases(path: str = SEED_PATH) -> dict[str, set[str]]:
    """`indexName -> 它所有的名字`（含 indexName 本身）。

    读不到就返回空表，由调用方 note 出来——**不回退成「按包含松比」**：那会把
    「代码指到了另一个指数」也放过去，而这是本组唯一能自动抓到取错代码的地方。
    """
    try:
        with open(path, encoding="utf-8") as handle:
            seed = json.load(handle).get("seed") or []
    except (OSError, ValueError):
        return {}
    aliases: dict[str, set[str]] = {}
    for entry in seed:
        name = str(entry.get("indexName") or "")
        if not name:
            continue
        aliases[name] = {name, *(str(a) for a in (entry.get("aliases") or []) if a)}
    return aliases


def fetch_csindex_raw(csindex_code: str) -> dict:
    return fetch_json(
        CSINDEX_RAW_URL,
        params={"indexCode": csindex_code},
        headers={"Referer": "https://www.csindex.com.cn/"},
    )


def fetch_sina_etfs(pages: int = SINA_ETF_PAGES) -> dict[str, dict]:
    """新浪的场内 ETF 全量清单，`code -> 行`。结束条件是**空页**，不是页数。"""
    by_code: dict[str, dict] = {}
    for page in range(1, pages + 1):
        rows = fetch_json(
            SINA_ETF_URL,
            params={"page": page, "num": SINA_ETF_PAGE_SIZE, "sort": "symbol",
                    "asc": 1, "node": "etf_hq_fund", "symbol": ""},
            headers=report.A_SHARE_SINA_HEADERS,
        )
        if not isinstance(rows, list):
            raise RuntimeError(f"新浪 ETF 清单第 {page} 页不是数组")
        if not rows:
            break
        for row in rows:
            code = str(row.get("code") or "")
            if code and code not in by_code:
                by_code[code] = row
    if not by_code:
        raise RuntimeError("新浪 ETF 清单为空")
    return by_code


def fetch_tencent_quotes(symbols) -> dict[str, list[str]]:
    """腾讯批量报价，`symbol -> 按 ~ 切开的字段`。

    **必须按 GBK 解**：腾讯不声明 charset，按 UTF-8 解会让 ETF 名变成一串替换符
    （Java 侧那套 charsetOf 默认 UTF-8，所以那边更要显式指定）。这里只印一次名字，
    但口径要和在 Java 里写死的那条注释一致。

    返回里**没有**的 symbol 就是腾讯静默丢弃了它（前缀写错、代码已退市），
    调用方必须当成失败——静默丢弃正是最难发现的那种错。
    """
    if not symbols:
        return {}
    url = TENCENT_QUOTE_URL + ",".join(symbols)
    request = urllib.request.Request(url, headers={"Referer": "https://gu.qq.com/",
                                                  "User-Agent": UA})
    with urllib.request.urlopen(request, timeout=20) as response:
        text = response.read().decode("gbk", "replace")
    quotes: dict[str, list[str]] = {}
    for statement in text.split(";"):
        statement = statement.strip()
        if not statement.startswith("v_") or "=" not in statement:
            continue
        name, _, payload = statement.partition("=")
        symbol = name[2:].strip()
        quotes[symbol] = payload.strip().strip('"').split("~")
    return quotes


def parse_csindex_points(body: dict) -> tuple[str, list[tuple[date, float]]]:
    """中证官网响应 → `(indexName, [(日期, PE)])`。有效性规则与生产解析器一致。

    `indexName` 在**每一行**里（`data[i].indexName`），顶层没有——实测 000300 的响应
    顶层只有 `code/msg/data/success`，第一行是
    `{"tradeDate": "20110628", "indexName": "沪深300", "indexNameEn": "CSI 300", "peg": 14.32}`。
    取最后一行那份（最新）作代表；这就是「这个代码指向的到底是哪个指数」的自证。
    """
    if str(body.get("code")) != "200" or not body.get("success"):
        raise RuntimeError(f"业务码 {body.get('code')}: {body.get('msg', '')}")
    points = []
    names: list[str] = []
    for item in body.get("data") or []:
        name = str(item.get("indexName") or "")
        if name:
            names.append(name)
        trade_date = str(item.get("tradeDate") or "")
        pe_value = report.finite_positive(item.get("peg"), 300)
        if len(trade_date) != 8 or not trade_date.isdigit() or pe_value is None:
            continue
        points.append((datetime.strptime(trade_date, "%Y%m%d").date(), pe_value))
    points.sort(key=lambda point: point[0])
    return (names[-1] if names else ""), points


def latest_pe(body: dict) -> float | None:
    _, points = parse_csindex_points(body)
    return points[-1][1] if points else None


# ----------------------------------------------------------------------
# A. 池子自洽（纯本地）
# ----------------------------------------------------------------------

def check_pool(pool: list[dict], checker: Checker) -> None:
    checker.section("A. 池子自洽（纯本地，不联网）")
    # 印**实际读的那一份**，不是 POOL_PATH：--pool 指向提案时，印错路径会让人
    # 以为验的是正式池子，而结论被当成正式池子的结论
    checker.line(f"  池子：{checker.pool_path or POOL_PATH}")
    checker.line(f"  条目 {len(pool)} 个")

    seen_codes: dict[str, str] = {}
    seen_etfs: dict[str, str] = {}
    seen_csindex: dict[str, str] = {}
    no_etf: list[str] = []

    for entry in pool:
        name = str(entry.get("indexName") or "")
        code = str(entry.get("indexCode") or "")
        where = code or name or "(未命名条目)"

        checker.check(f"{where} 有 indexName / indexCode / category",
                      bool(name) and bool(code) and bool(entry.get("category")),
                      f"indexName={name!r} indexCode={code!r} category={entry.get('category')!r}")

        if entry.get("category") == OVERSEAS:
            # 港股/美股指数没有沪深的交易所代码，只能用它自己的稳定标识。
            # 但「自己的标识」必须**唯一且确定**，不能是随手写的一串——所以要求它
            # 就是蛋卷目录里的那个代码，而不是另起一个名字。
            overseas_ok = re.fullmatch(r"[A-Z][A-Z0-9]{1,11}", code) is not None
            checker.check(f"{where} 的 indexCode 是稳定的海外标识（大写字母数字）",
                          overseas_ok, complain(overseas_ok, f"{code!r} 不像一个稳定的标识"))
            danjuan_code = str(entry.get("danjuanCode") or "")
            if danjuan_code:
                same = code == danjuan_code.upper()
                checker.check(f"{where} 的 indexCode 就是它的 danjuanCode", same,
                              complain(same, f"indexCode={code!r} 而 danjuanCode={danjuan_code!r}"
                                             "——两者不一致时，估值会写在这个 key 下、"
                                             "而同步按那个 key 去查，卡片就永远是空的"))
        else:
            digits = str(entry.get("csindexCode") or "") or re.sub(
                r"\D", "", str(entry.get("danjuanCode") or ""))
            want = index_code_prefix(digits) + digits
            key_ok = len(digits) == 6 and code == want
            checker.check(f"{where} 的 indexCode 由号段推出", key_ok,
                          complain(key_ok, f"期望 {want}（{index_code_prefix(digits)} 段 + {digits}），"
                                           f"实际 {code!r}——照抄蛋卷的写法会让同一个指数长出两个 key"))
        if code in seen_codes:
            checker.check(f"{where} 的 indexCode 唯一", False,
                          f"和「{seen_codes[code]}」重复——同一个 code 会写出两份估值")
        seen_codes[code] = name

        category_ok = entry.get("category") in CATEGORIES
        checker.check(f"{where} 的 category 合法", category_ok,
                      complain(category_ok, f"{entry.get('category')!r} 不在 {list(CATEGORIES)}"))

        source = entry.get("valuationSource")
        method = entry.get("percentileMethod")
        if source is None:
            # 允许为空（阶段 1 就是这么上线的），但那是要人工补的欠账，不是可以不管的状态
            checker.check(f"{where} 声明了估值来源与口径", False,
                          "valuationSource/percentileMethod 为空——这个指数的 PE 与分位会长出空白")
            half = [key for key in ("percentileMethod", "csindexCode", "danjuanCode")
                    if entry.get(key)]
            if half:
                checker.check(f"{where} 没有来源时不留半截字段", False,
                              f"valuationSource 为空却填了 {half}——这条永远不会生效，"
                              "要么补来源，要么把这些字段清掉")
        else:
            checker.check(f"{where} 的 valuationSource 合法",
                          source in METHOD_BY_SOURCE,
                          complain(source in METHOD_BY_SOURCE,
                                   f"{source!r} 不在 {sorted(METHOD_BY_SOURCE)}"))
            method_ok = method == METHOD_BY_SOURCE.get(source)
            checker.check(f"{where} 的口径与来源一一对应", method_ok,
                          complain(method_ok, f"{source} 应配 {METHOD_BY_SOURCE.get(source)}，"
                                              f"实际 {method!r}"))
            if source == CSINDEX:
                csindex_code = str(entry.get("csindexCode") or "")
                checker.check(f"{where} 是 csindex 源且有 csindexCode", bool(csindex_code),
                              f"csindexCode={entry.get('csindexCode')!r}")
                if csindex_code:
                    checker.check(f"{where} 的 csindexCode 是 6 位代码",
                                  re.fullmatch(r"[0-9A-Za-z]{6}", csindex_code) is not None,
                                  complain(re.fullmatch(r"[0-9A-Za-z]{6}", csindex_code) is not None,
                                           f"{csindex_code!r} 不是 6 位（H30533 这种带字母的也算）"))
                if csindex_code in seen_csindex:
                    checker.check(f"{where} 的 csindexCode 未被别的指数占用", False,
                                  f"和「{seen_csindex[csindex_code]}」重复")
                if csindex_code:
                    seen_csindex[csindex_code] = name
            elif source == DANJUAN:
                checker.check(f"{where} 是 danjuan 源且有 danjuanCode",
                              bool(entry.get("danjuanCode")),
                              f"danjuanCode={entry.get('danjuanCode')!r}")

        etf_code = str(entry.get("etfCode") or "")
        if not etf_code:
            no_etf.append(where)
            # 照 IndexFundPool.parseAndValidate 抄的：预检比运行时松，就等于让一批池子
            # 通过预检、再在容器启动时炸掉——那比校验脚本直接说不要好得多
            both_blank = entry.get("market") is None and not str(entry.get("etfName") or "")
            checker.check(f"{where} 没有 etfCode 时 market/etfName 也留空", both_blank,
                          complain(both_blank, "没有代表 ETF 却给了 market/etfName"
                                               "——IndexFundPool 会在这里让应用起不来"))
            continue
        etf_ok = etf_code not in seen_etfs
        checker.check(f"{where} 的 etfCode 唯一", etf_ok,
                      complain(etf_ok, f"{etf_code} 也被「{seen_etfs.get(etf_code)}」用了——"
                                       "两只指数会显示同一份价格位置，页面上看不出是错的"))
        seen_etfs[etf_code] = name
        market_ok = (entry.get("market") == 1) == etf_code.startswith("5")
        checker.check(f"{where} 的 market 与 etfCode 的市场一致", market_ok,
                      complain(market_ok, f"etfCode={etf_code} market={entry.get('market')!r}"
                                          "（5 开头是沪市=1，1 开头是深市=0）"))

    missing = [code for code in LEGACY_INDEX_CODES if code not in seen_codes]
    checker.check("7 只旧宽基仍在池内", not missing,
                  f"少了的：{missing}——如果是有意删的，把 LEGACY_INDEX_CODES 一起改掉" if missing else "")

    if no_etf:
        checker.note(f"{len(no_etf)} 个指数没有代表 ETF（{', '.join(no_etf[:5])}"
                     f"{' 等' if len(no_etf) > 5 else ''}）：它们的价格位置与规模会是原因串。"
                     "出货的池子按设计不应该有这种条目（见 index-pool.json 的维护说明）")


# ----------------------------------------------------------------------
# B. 来源可达
# ----------------------------------------------------------------------

def check_sources(pool: list[dict], danjuan_items: dict[str, dict],
                  csindex_bodies: dict[str, dict | None], checker: Checker,
                  aliases: dict[str, set[str]] | None = None) -> None:
    checker.section("B. 来源可达")

    danjuan_entries = [entry for entry in pool if entry.get("valuationSource") == DANJUAN]
    if danjuan_entries:
        checker.check("蛋卷目录取到了", bool(danjuan_items), f"{len(danjuan_items)} 个指数")
    else:
        checker.note("池子里没有 danjuan 源的条目")
    checker.line(f"  danjuan 源 {len(danjuan_entries)} 条，csindex 源 "
                 f"{sum(1 for e in pool if e.get('valuationSource') == CSINDEX)} 条")

    for entry in danjuan_entries:
        name = entry.get("indexName")
        code = str(entry.get("danjuanCode") or "").upper()
        item = danjuan_items.get(code)
        if not item:
            checker.check(f"{name}（{code}）在蛋卷目录里查得到", False,
                          "查不到就会长期空白；确认代码是否已下架或改名")
            continue
        checker.check(f"{name}（{code}）的蛋卷记录自洽",
                      str(item.get("index_code", "")).upper() == code,
                      f"蛋卷回的 index_code={item.get('index_code')!r}")
        # 真跑一遍生产侧的验收规则：蛋卷对个别指数回的是 PE=0 / 分位=0 的占位
        # （实测 SZ399393 国证地产），同步时会被跳过——不是 bug，但那个指数会一直空白，
        # 所以这里要提前看见，而不是等用户发现卡片空着。
        try:
            valuation = report.valuation_from_danjuan_item({"index_name": name}, item)
            checker.check(f"{name}（{code}）通过估值的验收规则", True,
                          f"PE {valuation['pe_ttm']} 分位 {valuation['pe_percentile']} "
                          f"@ {valuation['updated_at']}")
            age = (report.now_beijing().date() - date.fromisoformat(valuation["updated_at"])).days
            if age > CSINDEX_MAX_STALENESS_DAYS:
                # 蛋卷的估值中心逢长假会停在最后一个交易日，停几个月就是没人维护了。
                # 同步会照写这个日期（卡片上看得出来），所以这里只提示，不当失败。
                checker.note(f"蛋卷对「{name}」的估值停在 {valuation['updated_at']}"
                             f"（{age} 天前）——一直不动说明这个代码大概没人维护了")
        except RuntimeError as error:
            checker.check(f"{name}（{code}）通过估值的验收规则", False,
                          f"{error}——蛋卷会回占位记录，这个指数会一直空白，"
                          "要么换来源要么从池子里去掉")

    for code in sorted(csindex_bodies):
        body = csindex_bodies[code]
        if body is None:
            checker.check(f"csindex {code} 取得到", False,
                          "外呼失败：可能被限流，也可能这个代码已经不可用")
            continue
        try:
            index_name, points = parse_csindex_points(body)
        except RuntimeError as error:
            checker.check(f"csindex {code} 业务码 200 且 success", False, str(error))
            continue

        latest = points[-1][0] if points else None
        stale_days = (report.now_beijing().date() - latest).days if latest else None
        checker.check(f"csindex {code} 业务码 200 且 success", True, f"indexName={index_name!r}")
        checker.check(f"csindex {code} 点数够算分位", len(points) >= CSINDEX_MIN_POINTS,
                      f"{len(points)} 个点"
                      + ("" if len(points) >= CSINDEX_MIN_POINTS else "——太少，取数大概失败了"))
        if len(points) < CSINDEX_SHORT_HISTORY_POINTS:
            checker.note(f"csindex {code} 只有 {len(points)} 个点：滚动窗口自然取"
                         f"min(10年, 可用长度)，它的分位是缩水窗口的，"
                         "卡片上必须能看出该指数的历史长度")
        checker.check(f"csindex {code} 最新数据够新", stale_days is not None
                      and 0 <= stale_days <= CSINDEX_MAX_STALENESS_DAYS,
                      f"最新 {latest}（{stale_days} 天前）")

        # 名字自证：这是唯一能自动抓到「代码指向另一个指数」的地方。
        #
        # 按**精确相等**比，不比包含：包含会让 000306「沪深300金融」被认成 000300
        # 「沪深300」——而那正是这条检查要抓的错。俗名与官网名不一致的（比如项目里叫
        # 「上证红利」、官网叫「红利指数」），把官网名写进 `index_pool_seed.json` 的
        # `aliases` 里，别为了让这一行变绿去放松比较。
        owner = next((entry for entry in pool
                      if str(entry.get("csindexCode") or "") == code), None)
        if owner is not None:
            pool_name = str(owner.get("indexName") or "")
            known = {pool_name, *((aliases or {}).get(pool_name) or ())}
            name_ok = bool(index_name) and name_matches(index_name, known)
            checker.check(f"csindex {code} 回的名字和池子里对得上", name_ok,
                          complain(name_ok, f"官网回「{index_name}」，池子里写「{pool_name}」"
                                            f"（别名表里有 {sorted(known)}）——对不上就先确认这个"
                                            "代码指向的到底是不是这个指数；确实只是叫法不同，"
                                            "就把官网名补进 index_pool_seed.json 的 aliases"))


def check_csindex_production_path(history: list[dict], checker: Checker) -> None:
    """生产解析路径至少跑通一次。本脚本自己解析得动，证明不了 index_pool_sync 解析得动。"""
    checker.section("B'. 生产解析路径（fetch_csindex_pe_history）")
    if not history:
        checker.check(f"{CSINDEX_PRODUCTION_PROBE} 经生产函数取到 PE 历史", False, "返回空")
        return
    last = history[-1]
    checker.check(f"{CSINDEX_PRODUCTION_PROBE} 经生产函数取到 PE 历史", True,
                  f"{len(history)} 个点，最新 {last.get('tradeDate')} "
                  f"PE {last.get('peTtm')} 分位 {last.get('pePercentile')}")
    checker.check("生产函数给出的口径就是池子里写的那个",
                  last.get("percentileMethod") == report.CSI_PE_TTM_ROLLING_10Y,
                  f"{last.get('percentileMethod')!r}")
    checker.check("生产函数给出的分位落在 0-100",
                  isinstance(last.get("pePercentile"), (int, float))
                  and 0 <= last["pePercentile"] <= 100,
                  f"{last.get('pePercentile')!r}")


# ----------------------------------------------------------------------
# C. 反向检查（最关键的两条）
# ----------------------------------------------------------------------

def check_scale_caliber(sina_by_code: dict[str, dict], tencent: dict[str, list[str]],
                        checker: Checker, probes=SCALE_PROBES) -> None:
    checker.section("C1. 反向检查：新浪的基金规模要用 nmc，不是 mktcap")
    checker.line(f"  探针（写死，不随池子漂移）：{', '.join(probes)}")

    gaps: list[tuple[str, float]] = []
    for symbol in probes:
        code = symbol[2:]
        row = sina_by_code.get(code)
        fields = tencent.get(symbol)
        if row is None:
            checker.check(f"{symbol} 在新浪清单里找得到", False,
                          "探针消失说明清单口径变了，先用 D 组的结果判断是哪边的问题")
            continue
        if not fields or len(fields) <= TENCENT_FIELD_MARKET_CAP_YI:
            checker.check(f"{symbol} 腾讯回了完整字段", False,
                          f"{len(fields) if fields else 0} 个字段（需要 >{TENCENT_FIELD_MARKET_CAP_YI}）"
                          "——前缀写错时腾讯是**静默丢弃**的，所以要当成失败")
            continue

        tencent_yi = report.to_optional_float(fields[TENCENT_FIELD_MARKET_CAP_YI])
        nmc = report.to_optional_float(row.get("nmc"))
        mktcap = report.to_optional_float(row.get("mktcap"))
        if not tencent_yi or tencent_yi <= 0 or nmc is None or mktcap is None:
            checker.check(f"{symbol} 三个规模字段都取到了", False,
                          f"腾讯 {tencent_yi!r} nmc {nmc!r} mktcap {mktcap!r}")
            continue

        nmc_yi = nmc / NMC_WAN_PER_YI
        mktcap_yi = mktcap / NMC_WAN_PER_YI
        nmc_gap = abs(nmc_yi - tencent_yi) / tencent_yi
        mktcap_gap = abs(mktcap_yi - tencent_yi) / tencent_yi
        checker.check(f"{symbol} 新浪 nmc 与腾讯总市值一致", nmc_gap < 0.02,
                      f"nmc {nmc_yi:.2f} 亿 vs 腾讯 {tencent_yi:.2f} 亿，差 {nmc_gap:.1%}")
        checker.line(f"       （{symbol} {str(row.get('name') or '')}："
                     f"mktcap {mktcap_yi:.2f} 亿，与腾讯差 {mktcap_gap:.1%}）")
        gaps.append((symbol, mktcap_gap))

    if gaps:
        worst = max(gaps, key=lambda item: item[1])
        checker.check("mktcap 与腾讯总市值明显不等（「用 nmc」这条纪律的证据）",
                      worst[1] > 0.20,
                      f"最大 {worst[0]} 差 {worst[1]:.1%}；"
                      "**如果这条过了，说明新浪改了口径，重新确认再用 nmc**")
    else:
        checker.check("mktcap 与腾讯总市值明显不等（「用 nmc」这条纪律的证据）", False,
                      "没有一只取到可比的数据，这条纪律现在没有证据支撑")


def check_pe_caliber(csindex_pe: dict[str, float | None], danjuan_items: dict[str, dict],
                     checker: Checker, probes=PE_CALIBER_PROBES) -> None:
    checker.section("C2. 反向检查：csindex 与蛋卷是两个口径")
    far: list[tuple[str, float]] = []

    for csindex_code, danjuan_code, expectation, threshold in probes:
        left = csindex_pe.get(csindex_code)
        item = danjuan_items.get(danjuan_code)
        right = report.to_optional_float(item.get("pe")) if item else None
        # 两边都得是正数：蛋卷对个别指数回的是 PE=0 的占位（实测 SZ399393 国证地产），
        # 拿 0 去比会得出「差 100%，两口径果然背离」——那是假的证据。
        if left is None or right is None or left <= 0 or right <= 0:
            checker.check(f"{csindex_code} / {danjuan_code} 两边都有 PE", False,
                          f"csindex {left!r}、蛋卷 {right!r}——缺一边、或有一边是 0（占位记录），"
                          "就谈不上比口径，说明某个来源的覆盖面变了")
            continue
        gap = abs(right - left) / left
        detail = f"csindex {left:.2f} vs 蛋卷 {right:.2f}，差 {gap:.1%}"
        if expectation == "close":
            checker.check(f"{csindex_code} 两边基本吻合（这条是本组恒真的防线）",
                          gap < threshold, detail)
        else:
            checker.check(f"{csindex_code} 两边明显背离（「不可横比」的依据）",
                          gap > threshold, detail)
            far.append((csindex_code, gap))

    if far:
        best = max(far, key=lambda item: item[1])
        checker.check("至少一个指数上两口径明显背离", best[1] > 0.10,
                      f"最大 {best[0]} 差 {best[1]:.1%}；**如果这条过了，说明两个源已经收敛，"
                      "「不同来源不可横向比较」这句话就失去了依据**")
    else:
        checker.check("至少一个指数上两口径明显背离", False,
                      "没有一组比得起来，这条纪律现在没有证据支撑")


# ----------------------------------------------------------------------
# D. 池子与行情源一致
# ----------------------------------------------------------------------

def check_quote_sources(pool: list[dict], sina_by_code: dict[str, dict],
                        tencent: dict[str, list[str]], checker: Checker,
                        sample: int = 5) -> None:
    checker.section("D. 池子与行情源一致")

    with_etf = [entry for entry in pool if entry.get("etfCode")]
    checker.line(f"  池子里有代表 ETF 的 {len(with_etf)} 个指数")

    missing = [f"{entry.get('indexName')}/{entry.get('etfCode')}" for entry in with_etf
               if str(entry.get("etfCode")) not in sina_by_code]
    checker.check("池子里每个 etfCode 都在新浪 ETF 清单里", not missing,
                  complain(not missing,
                           f"找不到的：{missing[:5]}{' 等' if len(missing) > 5 else ''}"))

    picked = with_etf[:sample]
    symbols = [("sh" if entry.get("market") == 1 else "sz") + str(entry["etfCode"])
               for entry in picked]
    if not symbols:
        checker.check("腾讯批量报价抽样非空", False, "池子里没有任何 ETF 可抽")
        return

    checker.line(f"  腾讯抽样（{len(symbols)} 只）：{', '.join(symbols)}")
    dropped = [symbol for symbol in symbols if not tencent.get(symbol)]
    checker.check("腾讯批量报价抽样全部回得来", not dropped,
                  f"没回的：{dropped}——腾讯对市场前缀写错的代码是静默丢弃的，"
                  "一个都不能当跳过" if dropped else "")

    for symbol in symbols:
        fields = tencent.get(symbol)
        if not fields:
            continue
        count_ok = len(fields) >= TENCENT_FIELD_COUNT
        checker.check(f"{symbol} 的字段数没变（idx37/idx45 还站得住）", count_ok,
                      f"{len(fields)} 个字段（实测 {TENCENT_FIELD_COUNT}）"
                      if count_ok else
                      f"只有 {len(fields)} 个字段（实测 {TENCENT_FIELD_COUNT}）"
                      "——字段数变了，那两个下标要重新确认")
        price = report.to_optional_float(fields[3]) if len(fields) > 3 else None
        checker.check(f"{symbol} 的最新价是个正数", bool(price and price > 0),
                      f"{price!r}（字段位置 3）")


# ----------------------------------------------------------------------
# 编排
# ----------------------------------------------------------------------

def sample_csindex_codes(pool: list[dict], limit: int) -> list[str]:
    """池子里要抽查的 csindex 代码。

    抽样取排序后的前 `limit` 个——**它总是同一批**。这是有意的：抽查要的是「口径变了没有」，
    每次都换样本只会让失败变得不可复现。代价是查不到后面的条目，所以调用方要把
    「这次还有几个没查」说出来，搬新条目进来时用 `--csindex-limit 0` 全查一遍。
    """
    codes = sorted({str(entry.get("csindexCode")) for entry in pool
                    if entry.get("valuationSource") == CSINDEX and entry.get("csindexCode")})
    return codes[:limit] if limit and limit > 0 else codes


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="校验「低估精选」指数池（只读，不写任何东西）")
    parser.add_argument("--local-only", action="store_true", help="只跑 A 组，不联网")
    parser.add_argument("--csindex-limit", type=int, default=CSINDEX_SAMPLE_DEFAULT,
                        help=f"抽查多少个 csindex 条目（0 = 全查，默认 {CSINDEX_SAMPLE_DEFAULT}）")
    parser.add_argument("--sina-pages", type=int, default=SINA_ETF_PAGES)
    parser.add_argument("--pool", default=None,
                        help="校验另一个池子文件（比如刚收窄出来的候选提案）——"
                             "不然就只能先把条目搬进正式池子才验得动，"
                             "而那时候验失败已经在生产里了")
    args = parser.parse_args(argv)

    checker = Checker()
    checker.pool_path = args.pool or POOL_PATH
    try:
        pool = load_pool(args.pool)
    except (OSError, ValueError, RuntimeError) as error:
        checker.section("A. 池子自洽（纯本地，不联网）")
        checker.check("池子 JSON 可解析且非空", False, str(error))
        return finish(checker)
    check_pool(pool, checker)
    if args.local_only:
        return finish(checker)

    sampled = sample_csindex_codes(pool, args.csindex_limit)
    all_sources = sum(1 for entry in pool if entry.get("valuationSource") == CSINDEX)
    checker.note(f"csindex 本次抽查 {len(sampled)}/{all_sources} 个"
                 + ("（--csindex-limit 0 可以全查，搬新条目进来时应该跑一次）"
                    if len(sampled) < all_sources else ""))

    danjuan_items: dict[str, dict] = {}
    try:
        danjuan_items = fetch_danjuan()
    except Exception as error:  # noqa: BLE001 - 校验脚本要报告失败，不是崩掉
        checker.note(f"蛋卷目录取不到：{error}")

    csindex_bodies: dict[str, dict | None] = {}
    for index, code in enumerate(dict.fromkeys([*sampled, *PE_CALIBER_CODES])):
        if index:
            time.sleep(random.uniform(*CSINDEX_JITTER_SECONDS))
        try:
            csindex_bodies[code] = fetch_csindex_raw(code)
        except Exception as error:  # noqa: BLE001
            checker.note(f"csindex {code} 外呼失败：{error}")
            csindex_bodies[code] = None

    seed_aliases = load_seed_aliases()
    if not seed_aliases:
        checker.note(f"别名表读不到（{SEED_PATH}）：名字自证只能按池子里的名字精确比，"
                     "像「上证红利 / 红利指数」这种俗名差异会被报成失败")
    check_sources(pool, danjuan_items, csindex_bodies, checker, aliases=seed_aliases)

    try:
        time.sleep(random.uniform(*CSINDEX_JITTER_SECONDS))
        check_csindex_production_path(
            report.fetch_csindex_pe_history(CSINDEX_PRODUCTION_PROBE), checker)
    except Exception as error:  # noqa: BLE001
        checker.section("B'. 生产解析路径（fetch_csindex_pe_history）")
        checker.check(f"{CSINDEX_PRODUCTION_PROBE} 经生产函数取到 PE 历史", False, str(error))

    try:
        sina_by_code = fetch_sina_etfs(args.sina_pages)
    except Exception as error:  # noqa: BLE001
        checker.section("C1. 反向检查：新浪的基金规模要用 nmc，不是 mktcap")
        checker.check("新浪 ETF 清单取得到", False, str(error))
        sina_by_code = {}
    if sina_by_code:
        checker.note(f"新浪 ETF 清单 {len(sina_by_code)} 只"
                     + ("" if len(sina_by_code) >= SINA_ETF_MIN_COUNT
                        else f"——**少于实测的 {SINA_ETF_MIN_COUNT} 只，大概被静默截断了**"))

    tencent: dict[str, list[str]] = {}
    quote_symbols = [*SCALE_PROBES]
    quote_symbols += [("sh" if entry.get("market") == 1 else "sz") + str(entry["etfCode"])
                      for entry in pool if entry.get("etfCode")][:5]
    try:
        tencent = fetch_tencent_quotes(list(dict.fromkeys(quote_symbols)))
    except Exception as error:  # noqa: BLE001
        checker.note(f"腾讯报价取不到：{error}")

    check_scale_caliber(sina_by_code, tencent, checker)
    check_pe_caliber({code: (latest_pe(body) if body else None)
                      for code, body in csindex_bodies.items()}, danjuan_items, checker)
    check_quote_sources(pool, sina_by_code, tencent, checker)
    return finish(checker)


def finish(checker: Checker) -> int:
    print(f"\n{'=' * 60}")
    if checker.notes:
        print(f"提示 {len(checker.notes)} 条（不影响退出码，但别当没看见）：")
        for text in checker.notes:
            print(f"  · {text}")
    if checker.failures:
        print(f"不通过 {len(checker.failures)} 条：")
        for text in checker.failures:
            print(f"  ✗ {text}")
        print("\n退出码 1：先用上面这些查清楚，再往池子里搬条目。")
        return 1
    print("全部通过：池子自洽、来源可达、两条反向证据仍然成立。")
    return 0


if __name__ == "__main__":
    sys.exit(main())