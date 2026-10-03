#!/usr/bin/env python3
"""校验「代码查询」页依赖的三个数据源，以及三条**改了就静默出错**的接口细节。

为什么要有这个脚本：查询页要回答「个股的 PE 分位」，而项目章程原来写死
「个股没有 PE 历史分位」。这条结论现在换了源（东财 `datacenter-web` 的估值分析），
新源是**一次真实探测**得到的，写代码的机器到东财的网络不通，没法端到端验证。
东财改字段含义、改 filter 语法、改历史保留范围都不会发通知，所以上线前、
以及以后每隔一段时间，都要在能联网的机器上把这份校验跑一遍。

## 一、为什么个股 PE 分位换到了这个源

原结论「东财不给个股 PE 历史分位」说的是 `push2` 行情族：那族只给**当前**的
`f115`(PE TTM)，没有历史序列，所以算不出分位。但 `datacenter-web` 的估值分析
（`RPT_VALUEANALYSIS_DET`）是**逐日快照**表，按股票代码过滤就能拿到该股
**日频 PE(TTM) 历史**——分位可以由这段历史自己算。

选它当主源的另一个理由是**域名分开**：`datacenter-web.eastmoney.com` 与
会被按 IP 限流的 `push2*` 是两个域名（`MarketDataClient` 的类注释里写明了这个分离
「除了分担封禁风险，它还提供了一条判断依据」）。一次查询只打一次这个域名。

## 二、这个脚本钉死的三件事（都比字段含义更容易被忽略）

  1. `filter=(SECURITY_CODE=300274)` 里的代码**不能加引号**。加引号的形态**永远拿不到数据**：
     实测两种表现都出现过——服务端直接以 `参数预处理错误` 拒绝，或者不报错但**返回 0 行**。
     后者更危险：页面不崩，只是永远显示「没有数据」，排查时会一路往限流、网络、字段名上想，全想错。
     （这跟同一个接口上 `filter=(TRADE_DATE='2026-09-30')` 必须**带单引号**正好相反，
     所以日期那侧继续按原样带引号，代码这侧坚决不带。）
     脚本把「被拒绝」和「回 0 行」都算作正常，只有「真拿到了行」才判失败。
  2. `PE_TTM` 必须与行情族的 `f115` 是同一个数。两者一致，`f115` 才能在主源不可达时
     顶「今日 PE」那一格；一旦哪天不一致，`f115` 就不再是备源，而是**第二套口径**，
     必须立刻拿掉，否则同一只票会出现两个 PE（`MarketDataClient` 的注释专门警告过这件事）。
  3. **历史起点**。实测这个源只保留到 2018-01-02（与个股上市时间无关，是表级的保留范围），
     所以「10 年前」这一档在个股上**必然为空**。脚本把起点和以此算出的可用年数打印出来，
     等到它真的够 10 年时，这个结论要能被重新评估，而不是靠谁记得。

另外顺带钉住两个**边界**，免得查询页把它们路由到错的源：
个股估值源里**没有指数**（指数代码回 0 行）；中证官网**不覆盖深证/国证 399xxx**
（回 0 个点），那类指数只有蛋卷有。

跑法（Windows 上 python 是商店占位符，必须用 py -3）：

    py -3 automation/scripts/verify_lookup_sources.py
    py -3 automation/scripts/verify_lookup_sources.py --skip-quote

`--skip-quote` 跳过第四节（与行情族 `f115` 的交叉验证），用于**行情域名正在冷却**时：
那一节会打 `push2`，而按 IP 计的封禁越试越长，冷却期间唯一该做的就是别再碰它。
跳过不等于通过——结论里会写明「跳过的项没有被校验」。

只读：不发任何写请求，不入库，不推送。
退出码 0 = 全部通过；1 = 有不一致，**在把这些字段用于页面之前先查清楚**。

⚠️ 这个脚本会打七八次外呼，其中三次打东财。东财按 IP、按接口限流，被封之后
整族接口一起哑掉，所以**不要连着反复跑**，确认失败也等一阵再来。
"""

from __future__ import annotations

import json
import re
import sys
import urllib.error
import urllib.request
from datetime import date, datetime

# ----------------------------------------------------------------------
# 个股估值源：东财 datacenter-web 估值分析
# ----------------------------------------------------------------------

VALUATION_HOSTS = ("datacenter-web.eastmoney.com",)
VALUATION_REFERER = "https://data.eastmoney.com/"
VALUATION_PATH = "/api/data/v1/get"
VALUATION_REPORT = "RPT_VALUEANALYSIS_DET"

# 只点这几列：整段历史最小列约 176KB，顺手把名称与收盘价要过来（都是白给的）。
# 刻意**不要** `columns=ALL`——ALL 会带十几个用不上的列，白白把响应放大四倍。
VALUATION_COLUMNS = (
    "SECURITY_CODE,SECUCODE,SECURITY_NAME_ABBR,TRADE_DATE,"
    "PE_TTM,PE_LAR,PB_MRQ,CLOSE_PRICE"
)

# 与 Java 侧 StockValuationClient 逐字对应的解析目标。
VALUATION_MEANING = {
    "SECURITY_CODE": "6 位代码",
    "SECUCODE": "带市场后缀的代码",
    "SECURITY_NAME_ABBR": "简称",
    "TRADE_DATE": "交易日（带 00:00:00）",
    "PE_TTM": "PE(TTM)",
    "PE_LAR": "PE(最近年报口径)——**不是**我们用的那个",
    "PB_MRQ": "市净率(MRQ)",
    "CLOSE_PRICE": "收盘价",
}

# 历史起点。这是**表级**保留范围，与个股上市时间无关：
# 阳光电源（2011 年上市）与贵州茅台（2001 年上市）都只到 2018-01-02。
VALUATION_HISTORY_START = "2018-01-02"

# 分位窗口按 10 年设计（章程 §2.4）。起点不够 10 年时该档必须为空，不是编一个数。
PERCENTILE_WINDOW_YEARS = 10

# ----------------------------------------------------------------------
# 行情族：只用来交叉验证 PE 与收盘价（不打日线）
# ----------------------------------------------------------------------

QUOTE_HOSTS = ("push2.eastmoney.com", "82.push2.eastmoney.com", "push2delay.eastmoney.com")
QUOTE_REFERER = "https://quote.eastmoney.com/center/gridlist.html"
UT = "fa5fd1943c7bdc76815634f86e88ea48"

# ----------------------------------------------------------------------
# 指数估值：中证官网（池外 6 位代码）与蛋卷（深证/国证与海外）
# ----------------------------------------------------------------------

CSINDEX_HOSTS = ("www.csindex.com.cn",)
CSINDEX_REFERER = "https://www.csindex.com.cn/"
CSINDEX_PATH = "/csindex-home/perf/indexCsiDsPe"

# 覆盖边界：中证官网认沪深主流中证/国证指数，但**不认深证 399xxx**。
# 这两只就是边界本身，改成别的代码就等于把这条结论取消了。
CSINDEX_COVERED = "000300"
CSINDEX_NOT_COVERED = "399006"

DANJUAN_HOSTS = ("danjuanfunds.com",)
DANJUAN_REFERER = "https://danjuanfunds.com/djmodule/value-center"
DANJUAN_PATH = "/djapi/index_eva/dj"
# 蛋卷一次回全部指数（实测 63 个），逐个请求是纯浪费，而且会自己招来封禁。
DANJUAN_EXPECTED_MIN_ITEMS = 50

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

# 探测用的样本。一只有过亏损年的成长股 + 一只长期盈利的白马：
# 两者 PE 量级差三倍，交叉验证 PE_TTM == f115 时不会因为「碰巧相等」而蒙混过关。
SAMPLE_STOCKS = (("300274", "0"), ("600519", "1"))

failures: list[str] = []
# 取数失败单独记：它和「字段漂移」是两回事，混在一起会让人以为东财改了口径，
# 实际上只是这个 IP 正在被限流。而且**某一节取数失败不该取消其余各节**——
# 否则最容易出问题的行情域名一挂，中证/蛋卷那几节就永远查不到。
network_failures: list[str] = []
# 有意跳过的节（--skip-quote）。跳过不等于通过，结论里必须写出来。
skipped: list[str] = []
notes: list[str] = []


def fetch_json(path_query: str, hosts) -> dict:
    """按 host 顺序回退。path_query 是**已经拼好的** path?query，不再做任何编码。

    刻意不接受 dict：往下传 dict 就意味着某处会调 urlencode，而这个接口的
    `filter` 连括号和等号都必须原样出现（编码后服务端用 ANTLR 解析会直接回
    `参数预处理错误: NoViableAltException`）。拼装权留在调用方。
    """
    last_error = None
    for host in hosts:
        url = f"https://{host}{path_query}"
        headers = {
            "Referer": referer_for(host),
            "User-Agent": UA,
            "Accept": "application/json, text/plain, */*",
        }
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError as exc:
            # 把响应体带出来：`参数预处理错误` 这类信息只在 body 里，不在状态码里
            detail = ""
            try:
                detail = exc.read().decode("utf-8", "replace")[:200]
            except Exception:  # noqa: BLE001 - 读不出来就算了，别盖住原始错误
                pass
            last_error = f"{host}: HTTP {exc.code} {detail}"
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last_error = f"{host}: {exc}"
        except json.JSONDecodeError as exc:
            last_error = f"{host}: 返回的不是 JSON（{exc}）"
    raise RuntimeError(f"{hosts[0]} 等 {len(hosts)} 个 host 都没取到数据，最后一次错误 → {last_error}")


def referer_for(host: str) -> str:
    """每个域名配自己的 Referer，与 Java 侧一致。发错了不报错，只是可能被静默降级。"""
    if host in VALUATION_HOSTS:
        return VALUATION_REFERER
    if host in CSINDEX_HOSTS:
        return CSINDEX_REFERER
    if host in DANJUAN_HOSTS:
        return DANJUAN_REFERER
    return QUOTE_REFERER


def valuation_query(code: str, page_size: int = 5000, quoted: bool = False) -> str:
    """拼估值分析请求。**代码默认不带引号**——这正是本脚本要钉住的第 1 条。

    quoted=True 只用于反向验证：有意加上引号，期望服务端**返回 0 行**。
    能查到空才说明「代码不能加引号」这条结论还成立。
    """
    expr = f'(SECURITY_CODE="{code}")' if quoted else f"(SECURITY_CODE={code})"
    return (
        f"{VALUATION_PATH}?reportName={VALUATION_REPORT}"
        f"&columns={VALUATION_COLUMNS}"
        f"&pageSize={page_size}&pageNumber=1"
        "&sortColumns=TRADE_DATE&sortTypes=-1"
        f"&filter={expr}"
        "&source=WEB&client=WEB"
    )


def quote_query(secids: list[str]) -> str:
    """拼行情批量。secids 里的点和逗号必须原样——点被编码东财就认不出 secid。"""
    return (
        "/api/qt/ulist.np/get?"
        f"fltt=2&invt=2&ut={UT}"
        f"&secids={','.join(secids)}"
        "&fields=f12,f14,f2,f3,f23,f115"
    )


def csindex_query(index_code: str) -> str:
    return f"{CSINDEX_PATH}?indexCode={index_code}"


def danjuan_query() -> str:
    return DANJUAN_PATH


def result_rows(body: dict) -> tuple[list[dict], int | None]:
    """datacenter-web 的数据在 result.data；蛋卷在中证自己的 data。"""
    result = body.get("result") or {}
    return list(result.get("data") or []), result.get("count")


def check(label: str, ok: bool, detail: str = "", ok_detail: str = "") -> None:
    """`detail` 是**失败时的解释**，只在失败时打；`ok_detail` 是成功时的实测值。

    两者必须分开。像「指数代码在这个源里回 0 行」这种断言，它的 detail 写的是
    「它居然有 N 行——那路由规则要重新想」——通过时把这句打出来，用户看到的是
    一行绿字配一句红话，几次之后就没人再读这个脚本的输出了。
    """
    mark = "OK  " if ok else "FAIL"
    shown = ok_detail if ok else detail
    print(f"  [{mark}] {label}" + (f" — {shown}" if shown else ""))
    if not ok:
        failures.append(f"{label} {detail}".strip())


def section(title: str) -> None:
    print(f"\n=== {title} ===")


def as_number(value):
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip()
    if text in ("", "-", "--"):
        return None
    try:
        return float(text)
    except ValueError:
        return None


def day_of(value) -> str:
    return str(value or "")[:10]


CSINDEX_DATE_PATTERN = re.compile(r"^\d{8}$")


def csindex_day(value) -> str:
    """中证官网的 `tradeDate` 是**紧凑的 YYYYMMDD**（`20110628`），返回 ISO 形式便于比较。

    非该形态返回空串——调用方据此判失败，而不是把 `2011-06-28` 当成一个能比较的日期。
    """
    text = str(value or "").strip()
    if not CSINDEX_DATE_PATTERN.match(text):
        return ""
    return f"{text[0:4]}-{text[4:6]}-{text[6:8]}"


def years_between(start_text: str, end_text: str) -> float:
    start = datetime.strptime(start_text, "%Y-%m-%d").date()
    end = datetime.strptime(end_text, "%Y-%m-%d").date()
    return (end - start).days / 365.25


# ----------------------------------------------------------------------
# 一、按代码取全历史
# ----------------------------------------------------------------------

def verify_by_code() -> dict[str, list[dict]]:
    section("一、个股估值源按代码取全历史（filter 里的代码**不带引号**）")
    histories: dict[str, list[dict]] = {}
    for code, market in SAMPLE_STOCKS:
        body = fetch_json(valuation_query(code), VALUATION_HOSTS)
        rows, count = result_rows(body)
        check(f"{code} 按代码查得到历史", bool(rows), f"{len(rows)} 行，count={count}")
        if not rows:
            continue
        histories[code] = rows
        # 一次请求就要能把全历史拿回来：按日翻页会是十年 2400 次外呼，
        # 对一个按 IP 限流的源来说是自找封禁，所以这条必须钉住。
        check(f"{code} 一次请求即返回全历史（不必翻页）",
              count is None or len(rows) == count,
              f"返回 {len(rows)} / count {count}")
        print(f"  {code} {rows[0].get('SECURITY_NAME_ABBR')}："
              f"{day_of(rows[-1].get('TRADE_DATE'))} ~ {day_of(rows[0].get('TRADE_DATE'))}")
    return histories


def verify_code_must_stay_unquoted() -> None:
    """反向验证：给代码加上引号，服务端**不报错但返回 0 行**。

    这是本脚本里最容易静默出事的一条：加了引号页面不会崩，只会永远显示
    「没有数据」，排查时会一路往限流、网络、字段名上想，全想错。
    """
    section("二、反向验证：代码一旦加引号就查不到（日期那侧恰恰相反，必须带引号）")
    code = SAMPLE_STOCKS[0][0]
    try:
        body = fetch_json(valuation_query(code, quoted=True), VALUATION_HOSTS)
    except RuntimeError as exc:
        # 被拒绝也算「加引号拿不到数据」。实测两种形态都出现过（拒绝 / 空结果），
        # 两者都正常，记一笔是为了以后能看到它在两种形态之间摇摆。
        check("加引号会被拒绝", True, "如期报错")
        notes.append(f"加引号这一发是**被拒绝**（{str(exc)[:120]}），不是回 0 行——"
                     "两种形态都算正常，只是记一笔")
        return
    rows, _ = result_rows(body)
    check("加引号拿不到数据", not rows,
          "它居然查到了——说明服务端开始接受引号，"
          "StockValuationClient 里「刻意不带引号」的注释与测试现在与事实不符，需要重新评估",
          ok_detail="如期回 0 行")


def verify_fields_and_depth(histories: dict[str, list[dict]]) -> None:
    section("三、字段钉死 + 历史起点（决定「十年」这一档能不能有数）")
    if not histories:
        failures.append("没有拿到任何历史，字段与深度都无法校验")
        return

    rows = histories[SAMPLE_STOCKS[0][0]]
    first = rows[0]
    missing = [k for k in VALUATION_MEANING if k not in first]
    check("解析目标字段全部存在", not missing, f"缺 {missing}" if missing else "")
    for key, meaning in VALUATION_MEANING.items():
        check(f"{key} 存在（{meaning}）", key in first, f"值={first.get(key)!r}")

    # TRADE_DATE 带 00:00:00，取前 10 位才是交易日。
    check("TRADE_DATE 是「YYYY-MM-DD HH:MM:SS」",
          len(str(first.get("TRADE_DATE") or "")) == 19
          and str(first.get("TRADE_DATE"))[4] == "-",
          repr(first.get("TRADE_DATE")))

    oldest = day_of(rows[-1].get("TRADE_DATE"))
    newest = day_of(rows[0].get("TRADE_DATE"))
    for code, history in histories.items():
        start = day_of(history[-1].get("TRADE_DATE"))
        check(f"{code} 历史起点就是表级保留范围 {VALUATION_HISTORY_START}",
              start == VALUATION_HISTORY_START,
              f"实际 {start}——起点变了就要重新评估「十年」这一档",
              ok_detail=f"实际 {start}")
    check("样本之间历史起点一致（说明是表级范围，不是按上市时间给的）",
          len({day_of(h[-1].get("TRADE_DATE")) for h in histories.values()}) == 1)

    available = years_between(oldest, newest)
    print(f"\n  可用历史 {oldest} ~ {newest}，约 {available:.1f} 年")
    if available < PERCENTILE_WINDOW_YEARS:
        notes.append(
            f"个股可用历史只有约 {available:.1f} 年，不足 {PERCENTILE_WINDOW_YEARS} 年窗口，"
            f"所以「十年」这一档在个股上**必然为空**，页面必须写「历史不足」而不是给一个数。"
            f"等到 {VALUATION_HISTORY_START[:4]} 年起满 {PERCENTILE_WINDOW_YEARS} 年时，"
            "这条结论要重新评估。")
    # 「十年前那一天的分位」需要在那一天之前就已有足够长的窗口，
    # 所以它比 PERCENTILE_WINDOW_YEARS 更苛刻：起点之后还要再攒够 10 年。
    for label, years in (("五年", 5), ("十年", 10)):
        if available < years:
            notes.append(f"个股「{label}」基线不可得（历史不足 {years} 年）")

    # 排序：脚本假定服务端给了降序（sortTypes=-1），分位基线要靠这个顺序，
    # 所以顺序变了必须报出来，不能让下游自己再排一遍又排错。
    dates = [day_of(r.get("TRADE_DATE")) for r in rows]
    check("返回是按交易日降序的", dates == sorted(dates, reverse=True), f"{dates[0]} … {dates[-1]}")


# ----------------------------------------------------------------------
# 四、与行情源交叉验证（f115 能不能当备源，就靠这一条）
# ----------------------------------------------------------------------

def verify_pe_matches_quote(histories: dict[str, list[dict]]) -> None:
    section("四、PE_TTM 必须与行情族 f115 是同一个数（决定 f115 能否当备源）")
    if not histories:
        failures.append("没有历史，无法交叉验证 PE 口径")
        return

    secids = [f"{market}.{code}" for code, market in SAMPLE_STOCKS if code in histories]
    body = fetch_json(quote_query(secids), QUOTE_HOSTS)
    diff = (body.get("data") or {}).get("diff") or []
    if isinstance(diff, dict):
        diff = list(diff.values())
    check("行情批量取回同样数量的标的", len(diff) == len(secids), f"{len(diff)}/{len(secids)}")
    by_code = {str(r.get("f12")): r for r in diff}

    compared = 0
    for code, _ in SAMPLE_STOCKS:
        rows = histories.get(code)
        quote = by_code.get(code)
        if not rows or not quote:
            continue
        pe_history = as_number(rows[0].get("PE_TTM"))
        pe_quote = as_number(quote.get("f115"))
        if pe_history is None or pe_quote is None:
            notes.append(f"{code} 的 PE 有缺失（历史 {pe_history} / 行情 {pe_quote}），跳过比对")
            continue
        drift = abs(pe_history - pe_quote) / abs(pe_quote)
        compared += 1
        check(f"{code} PE_TTM == f115（±1%）", drift < 0.01,
              f"估值分析 {pe_history} vs 行情 {pe_quote}（偏差 {drift:.2%}）")
        # 收盘价是白给的，顺手也核一下
        close_history = as_number(rows[0].get("CLOSE_PRICE"))
        close_quote = as_number(quote.get("f2"))
        if close_history and close_quote:
            check(f"{code} CLOSE_PRICE == f2", abs(close_history - close_quote) < 0.02,
                  f"{close_history} vs {close_quote}")

    if compared == 0:
        failures.append("没有一个标的能同时拿到 PE_TTM 与 f115，无法确认两者同口径——"
                        "f115 就不能当备源")
    else:
        notes.append("PE_TTM 与 f115 同口径，所以主源不可达时 f115 可以顶「今日 PE」那一格"
                     "（但只能顶当前值，它没有历史，给不了分位）。")


# ----------------------------------------------------------------------
# 五、个股估值源里没有指数
# ----------------------------------------------------------------------

def verify_no_indices_here() -> None:
    section("五、个股估值源里没有指数（池外指数必须走中证/蛋卷）")
    body = fetch_json(valuation_query("000300", page_size=5), VALUATION_HOSTS)
    rows, _ = result_rows(body)
    check("指数代码在这个源里回 0 行", not rows,
          f"它居然有 {len(rows)} 行——那池外指数的路由规则要重新想",
          ok_detail="如期回 0 行")


# ----------------------------------------------------------------------
# 六、中证官网的覆盖边界
# ----------------------------------------------------------------------

def verify_csindex_coverage() -> None:
    section("六、中证官网覆盖边界（池外 6 位代码走这里）")
    covered = fetch_json(csindex_query(CSINDEX_COVERED), CSINDEX_HOSTS)
    # 与 fetch_csindex_pe_history 一样先看业务信封：HTTP 200 也可以是业务失败。
    check("业务信封仍是 code=200 且 success",
          str(covered.get("code")) == "200" and bool(covered.get("success")),
          f"code={covered.get('code')!r} success={covered.get('success')!r}——"
          "fetch_csindex_pe_history 会因此抛错，Java 侧要与它一致")

    points = covered.get("data") or []
    check(f"{CSINDEX_COVERED} 能取到 PE 历史", bool(points),
          f"{len(points)} 个点", ok_detail=f"{len(points)} 个点")
    if not points:
        return

    first = points[0]
    check("字段名仍是 peg / tradeDate", "peg" in first and "tradeDate" in first,
          f"字段 {sorted(first.keys())}")

    # tradeDate 是紧凑的 YYYYMMDD。fetch_csindex_pe_history 用 `re.fullmatch(r"\d{8}")`
    # 过滤，**不匹配就 continue**——一旦换成带横线的形态，它会静默丢掉每一个点，
    # 最后报「未返回有效PE历史」，排查时会一路往限流、代码写错上想。
    raw_dates = [str(p.get("tradeDate") or "").strip() for p in points]
    compact = all(CSINDEX_DATE_PATTERN.match(d) for d in raw_dates)
    check("tradeDate 是紧凑的 YYYYMMDD（不是带横线的）", compact,
          f"样例 {first.get('tradeDate')!r}——"
          r"fetch_csindex_pe_history 的 \d{8} 过滤会静默丢掉所有点",
          ok_detail=f"样例 {first.get('tradeDate')!r}")
    if not compact:
        return

    dates = [csindex_day(d) for d in raw_dates]
    oldest, newest = dates[0], dates[-1]
    print(f"  {CSINDEX_COVERED}：{oldest} ~ {newest}")
    # 指数是 5/10 年能落上数的关键：历史够长，10 年前的窗口才算得出来。
    check(f"{CSINDEX_COVERED} 历史够长（≥10 年）",
          years_between(oldest, newest) >= PERCENTILE_WINDOW_YEARS,
          f"约 {years_between(oldest, newest):.1f} 年",
          ok_detail=f"约 {years_between(oldest, newest):.1f} 年")
    check("返回按交易日升序（分位滚动窗口依赖这个顺序）", dates == sorted(dates),
          f"{dates[0]} … {dates[-1]}")

    not_covered = fetch_json(csindex_query(CSINDEX_NOT_COVERED), CSINDEX_HOSTS)
    holes = not_covered.get("data") or []
    check(f"{CSINDEX_NOT_COVERED}（深证系）不在覆盖范围内", not holes,
          f"它居然有 {len(holes)} 个点——覆盖边界变了，池外路由要多试一条中证",
          ok_detail="如期 0 个点")


# ----------------------------------------------------------------------
# 七、蛋卷的覆盖与代码形态
# ----------------------------------------------------------------------

def verify_danjuan_coverage() -> None:
    section("七、蛋卷一次回全部指数（深证/国证与海外只有它有）")
    body = fetch_json(danjuan_query(), DANJUAN_HOSTS)
    check("蛋卷业务码正常", str(body.get("result_code")) in ("0", "200"),
          f"result_code={body.get('result_code')}")
    items = (body.get("data") or {}).get("items") or []
    check(f"一次回 ≥{DANJUAN_EXPECTED_MIN_ITEMS} 个指数", len(items) >= DANJUAN_EXPECTED_MIN_ITEMS,
          f"{len(items)} 个")

    codes = {str(i.get("index_code") or "").upper() for i in items}
    for probe, why in (("NDX", "海外指数"), ("SP500", "海外指数"),
                       ("SZ399975", "深证系（中证官网不覆盖）"),
                       ("CSIH30533", "国证/中证带字母代码")):
        check(f"蛋卷含 {probe}（{why}）", probe in codes)

    # 缺数是用 0 表示的，不是 null——下游必须按「非正 PE 即无效」处理，
    # 否则一个 pe=0 会被当成「极度便宜」。
    zeros = [i for i in items if as_number(i.get("pe")) == 0]
    if zeros:
        notes.append(f"蛋卷有 {len(zeros)} 个指数 PE 为 0（如 {zeros[0].get('index_code')}）——"
                     "0 是「没有数」而不是「便宜」，下游必须按非正 PE 丢弃，"
                     "这与既有的 valuation_from_danjuan_item 一致")


def run_section(name: str, fn, *args):
    """跑一节，取数失败只记这一节的账，不中断后面几节。

    三个源分属三家（东财 / 中证官网 / 蛋卷），一家不可达不该让另外两家的结论
    一起消失——尤其在东财正被限流的时候，中证与蛋卷那几节恰恰是最想看的。
    """
    try:
        return fn(*args)
    except RuntimeError as exc:
        print(f"\n  ⚠️ 本节取数失败：{exc}")
        network_failures.append(f"{name}：{exc}")
        return None


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    skip_quote = "--skip-quote" in args
    # 这是个要人坐着看输出的脚本，输出全是中文。Windows 控制台默认 GBK，
    # 不显式切 UTF-8 的话用户看到的是一片乱码，等于白跑。
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    print("「代码查询」数据源校验 —— 只读，不改数据")
    print(f"运行时间 {datetime.now().isoformat(timespec='seconds')}")
    print("⚠️ 东财按 IP 按接口限流，这个脚本会打七八次外呼，不要连着反复跑。")

    # 一、二、五、六、七 各自独立；三、四 依赖第一节拿到的历史，第一节失败就跳过。
    histories = run_section("一、个股估值源", verify_by_code)
    run_section("二、反向验证", verify_code_must_stay_unquoted)
    if histories:
        run_section("三、字段与历史起点", verify_fields_and_depth, histories)
        if skip_quote:
            # 行情域名正在冷却时，唯一该做的就是**别再碰它**——按 IP 计的封禁
            # 越试越长。剩下的几节分属另外两家，照跑。
            section("四、PE 交叉验证 —— 本轮按 --skip-quote 跳过（不打行情域名）")
            skipped.append("四、PE 交叉验证（--skip-quote）")
        else:
            run_section("四、PE 交叉验证", verify_pe_matches_quote, histories)
    else:
        failures.append("没有拿到个股历史，字段、历史起点、PE 交叉验证都未能校验")
    run_section("五、个股源里没有指数", verify_no_indices_here)
    run_section("六、中证官网覆盖边界", verify_csindex_coverage)
    run_section("七、蛋卷覆盖", verify_danjuan_coverage)

    section("结论")
    for note in notes:
        print(f"  · {note}")
    for item in skipped:
        print(f"  ⏭️  跳过（**没有校验**，不算通过）：{item}")

    if network_failures:
        print(f"\n  有 {len(network_failures)} 节**取数失败**（不是口径问题）：")
        for item in network_failures:
            print(f"    - {item}")
        print("\n  连不上数据源，或者**这个 IP 正在被限流**。")
        print("  限流的特征：TCP 连得上，但服务端一个字节都不回就把连接掐了。")
        print("  那是按 IP 计时的，等十几分钟再跑，别反复重试——越试封得越久。")
        print("  下面是没能校验的项，等能取到数时再跑一遍，别把它们当成通过。")

    if failures:
        print(f"\n  有 {len(failures)} 项不一致：")
        for item in failures:
            print(f"    - {item}")
        print("\n  在这些字段被用于查询页之前，先按上面的输出核对含义。")
        return 1

    if network_failures:
        # 有节没跑成，就不是「全部通过」——宁可让人再跑一次，也不给一个假的绿灯。
        return 1

    if skipped:
        print(f"\n  除跳过的 {len(skipped)} 节外全部通过。跳过的项**没有被校验**，"
              "等行情域名解封后不加 --skip-quote 再跑一次。")
        return 0

    print("\n  全部通过：三个源的口径与 automation/agents/stock_screening_rules.md §2.4/§2.5 一致。")
    print(f"  对照基准 {date.today().isoformat()}；东财改口径不会通知，隔一段时间重跑一次。")
    return 0


if __name__ == "__main__":
    sys.exit(main())