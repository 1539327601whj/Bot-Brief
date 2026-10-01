#!/usr/bin/env python3
"""校验「低估精选」用到的东财字段与接口参数是否仍然名副其实。

为什么需要有这个脚本：Java 侧的 MarketDataClient 是按**一次真实探测**得到的字段 ID 写的
（f37 / f41 / f46 / f49 / f57 / f100 / f115 / f133 …），写代码的机器到东财的网络不通，
没法做端到端验证。东财改字段含义不会发通知，所以第一次部署、以及以后每隔一段时间，
都要在能联网的机器上把这份校验跑一遍。

它同时钉死三件**一改就静默出错**的事，这三件都比字段含义更容易被忽略：

  1. `filter` 必须原样发出去（不能百分号编码）——服务端用 ANTLR 解析它且不做 URL 解码，
     编码后直接回 `参数预处理错误: NoViableAltException`。这与旧 clist 的 `fs`
     （必须编码）正好相反，所以脚本里两个方向都验一遍。
  2. 池子是「总市值前 N 只」，**不是全市场**。市值下限会被算出来并打印，
     这句话要能对上结果页口径摘要里的那一行。
  3. 行情批量（ulist）一次能吃多少只 secids —— 这个数直接决定 `screener.quote-batch-size`，
     也直接决定一次筛选要打几次外呼。实测出来再填。

跑法（Windows 上 python 是商店占位符，必须用 py -3）：

    py -3 automation/scripts/verify_screener_fields.py

只读：不发任何写请求，不入库，不推送。
退出码 0 = 全部通过；1 = 有不一致，**在使用这些字段前先查清楚**。

⚠️ 这个脚本会打十几次外呼。东财是按 IP、按接口限流的，被封之后整族接口一起哑掉，
所以**不要连着反复跑**，确认失败也等一阵再来。
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime

UT = "fa5fd1943c7bdc76815634f86e88ea48"

# 池子清单走 datacenter-web，与行情族（push2*）是两个独立域名。
# 这个分离是有意的：行情被限流时清单通常还活着，这正是判断「是被限流」而不是「网络断了」的依据。
LIST_HOSTS = ("datacenter-web.eastmoney.com",)
QUOTE_HOSTS = ("push2.eastmoney.com", "82.push2.eastmoney.com", "push2delay.eastmoney.com")
KLINE_HOSTS = ("push2his.eastmoney.com", "push2delay.eastmoney.com")

LIST_PATH = "/api/data/v1/get"
LIST_REPORT = "RPT_VALUEANALYSIS_DET"
LIST_COLUMNS = "SECURITY_CODE,SECUCODE,SECURITY_NAME_ABBR,TOTAL_MARKET_CAP"

# 与 Java 侧 POOL_SIZE 一致；调这里的时候 production 的 screener.pool-size 要一起调。
POOL_SIZE = 300

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

# 每个域名配自己的 Referer：datacenter-web 认 data.eastmoney.com，
# 行情族认 quote.eastmoney.com。发错了不报错，只是可能被静默降级，所以别随手统一。
REFERERS = {
    "datacenter-web.eastmoney.com": "https://data.eastmoney.com/",
}
DEFAULT_REFERER = "https://quote.eastmoney.com/center/gridlist.html"

# 字段「是什么」，与 automation/agents/stock_screening_rules.md 第 2.1 节一致。
QUOTE_FIELDS = (
    "f12,f14,f2,f3,f6,f8,f20,f21,f23,f24,f25,f26,f37,f41,f46,f49,f57,f100,f113,f115,f133"
)

MEANING = {
    "f12": "代码",
    "f14": "名称",
    "f2": "最新价",
    "f3": "当日涨跌幅%",
    "f6": "成交额(元)",
    "f8": "换手率%",
    "f20": "总市值(元)",
    "f21": "流通市值(元)",
    "f23": "市净率",
    "f24": "60日涨跌幅%(仅背景)",
    "f25": "年初至今%(仅背景)",
    "f26": "上市日期",
    "f37": "ROE(加权)%",
    "f41": "营收同比%",
    "f46": "净利同比%",
    "f49": "毛利率%",
    "f57": "资产负债率%",
    "f100": "所属行业",
    "f113": "每股净资产",
    "f115": "PE(TTM)",
    "f133": "股息率%",
}

failures: list[str] = []
notes: list[str] = []


def fetch_json(path_query: str, hosts) -> dict:
    """按 host 顺序回退。path_query 是**已经拼好的** path?query，不再做任何编码。

    刻意不接受 dict：往下传 dict 就意味着某处会调 urlencode，
    而本文档开头第 1 条说的正是「这个接口不能编码」。拼装权留在调用方。
    """
    last_error = None
    for host in hosts:
        url = f"https://{host}{path_query}"
        headers = {
            "Referer": REFERERS.get(host, DEFAULT_REFERER),
            "User-Agent": UA,
            "Accept": "application/json, text/plain, */*",
        }
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=20) as resp:
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


def list_query(page_size: int, sort_column: str, sort_type: int | None,
               filter_expr: str | None, columns: str = LIST_COLUMNS,
               encoded_filter: bool = False) -> str:
    """拼池子清单的 URL。与 Java 侧 MarketDataClient.listUrl 逐字对应。

    encoded_filter=True 只用于「反向验证」：有意把 filter 百分号编码，
    期望服务端**报错**。能报错才说明「必须原样」这条结论还成立。
    """
    expr = filter_expr
    if expr is not None and encoded_filter:
        expr = urllib.parse.quote(expr, safe="")
    parts = [
        f"reportName={LIST_REPORT}",
        f"columns={columns}",
        f"pageSize={page_size}",
        "pageNumber=1",
        f"sortColumns={sort_column}",
    ]
    if sort_type is not None:
        parts.append(f"sortTypes={sort_type}")
    if expr is not None:
        parts.append(f"filter={expr}")
    parts.append("source=WEB&client=WEB")
    return f"{LIST_PATH}?" + "&".join(parts)


def quote_query(secids: list[str]) -> str:
    """拼行情批量 URL。secids 里的点和逗号必须原样——点被编码东财就认不出 secid。"""
    return (
        "/api/qt/ulist.np/get?"
        f"fltt=2&invt=2&ut={UT}"
        f"&secids={','.join(secids)}"
        f"&fields={QUOTE_FIELDS}"
    )


def kline_query(secid: str) -> str:
    return (
        "/api/qt/stock/kline/get?"
        f"secid={secid}&klt=101&fqt=1&lmt=250&end=20500101"
        "&fields1=f1,f2,f3,f4,f5,f6"
        "&fields2=f51,f52,f53,f54,f55,f56,f57,f58"
    )


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


def rows_of(body: dict, key: str = "result") -> list[dict]:
    """datacenter-web 的数据在 result.data；行情族在 data.diff（可能是 dict）。"""
    if key == "result":
        result = body.get("result") or {}
        return list(result.get("data") or [])
    data = body.get("data") or {}
    diff = data.get("diff")
    if isinstance(diff, dict):
        diff = list(diff.values())
    return list(diff or [])


def check(label: str, ok: bool, detail: str = "") -> None:
    mark = "OK  " if ok else "FAIL"
    print(f"  [{mark}] {label}" + (f" — {detail}" if detail else ""))
    if not ok:
        failures.append(f"{label} {detail}".strip())


def section(title: str) -> None:
    print(f"\n=== {title} ===")


def yi(yuan: float) -> str:
    return f"{yuan / 1e8:,.0f} 亿"


# ----------------------------------------------------------------------
# 一、池子清单
# ----------------------------------------------------------------------

def latest_trade_date() -> str:
    section("一、最新交易日")
    body = fetch_json(list_query(1, "TRADE_DATE", -1, None, columns="TRADE_DATE"), LIST_HOSTS)
    rows = rows_of(body)
    check("清单接口可用且回得到交易日", bool(rows), f"{len(rows)} 行")
    if not rows:
        raise RuntimeError("拿不到最新交易日，清单接口可能已经改版")
    raw = str(rows[0].get("TRADE_DATE") or "")
    day = raw.split(" ")[0]
    check("TRADE_DATE 是 YYYY-MM-DD", len(day) == 10 and day[4] == "-" and day[7] == "-", raw)
    print(f"  最新交易日：{day}")
    return day


def verify_pool(trade_date: str) -> list[dict]:
    section(f"二、池子清单（{LIST_REPORT}，{trade_date}，总市值前 {POOL_SIZE}）")
    body = fetch_json(list_query(POOL_SIZE, "TOTAL_MARKET_CAP", -1,
                                 f"(TRADE_DATE='{trade_date}')"), LIST_HOSTS)
    rows = rows_of(body)
    check("清单返回了行", bool(rows), f"{len(rows)} 行")
    if not rows:
        raise RuntimeError("清单为空。先确认交易日写法与 filter 是否被服务端接受了。")

    total = (body.get("result") or {}).get("count")
    print(f"  返回 {len(rows)} 行；该交易日全部标的数据量 count={total}")

    first = rows[0]
    for key, meaning in (("SECURITY_CODE", "代码"), ("SECUCODE", "带市场后缀的代码"),
                         ("SECURITY_NAME_ABBR", "简称"), ("TOTAL_MARKET_CAP", "总市值(元)")):
        check(f"清单字段 {key} 存在（{meaning}）", key in first, f"值={first.get(key)!r}")

    # 清单只负责「谁在池子里」，基本面一律以行情接口为准。
    # 如果清单哪天开始带 PE/PB，那就会有第二套口径偷偷进来，必须显式发现。
    extra = [k for k in first if k not in
             ("SECURITY_CODE", "SECUCODE", "SECURITY_NAME_ABBR", "TOTAL_MARKET_CAP")]
    if extra:
        notes.append(f"清单开始返回额外字段 {extra}——它们没进 columns，是服务端多给的；"
                     "确认没有被误当成第二套口径使用")

    print("\n  前 8 行：")
    for item in rows[:8]:
        print(f"    {item.get('SECURITY_CODE')} {item.get('SECURITY_NAME_ABBR')} "
              f"{item.get('SECUCODE')} 市值={yi(as_number(item.get('TOTAL_MARKET_CAP')) or 0)}")

    # 后缀只认 SH/SZ：北交所在 Java 侧的 fromListRow 就被丢掉了，
    # 它不该继续往下走（章程里明确不纳入）。
    suffixes: dict[str, int] = {}
    for item in rows:
        secucode = str(item.get("SECUCODE") or "")
        suffixes[secucode.split(".")[-1] if "." in secucode else "(无后缀)"] = \
            suffixes.get(secucode.split(".")[-1] if "." in secucode else "(无后缀)", 0) + 1
    check("SECUCODE 后缀只有 SH/SZ/BJ",
          set(suffixes) <= {"SH", "SZ", "BJ"},
          f"实际 {suffixes}")
    if suffixes.get("BJ"):
        notes.append(f"池子里有 {suffixes['BJ']} 只北交所标的，会被 fromListRow 剔除，"
                     "所以「进入扫描」的只数会明显少于 pageSize")

    caps = [as_number(i.get("TOTAL_MARKET_CAP")) for i in rows]
    caps = [c for c in caps if c]
    check("市值是降序的", caps == sorted(caps, reverse=True))
    check("市值单位是「元」而不是「万元」",
          1e10 < caps[0] < 1e13, f"最大 {yi(caps[0])}——异常就说明单位换口径了")

    # 池子下限：这句话必须能对上结果页口径摘要里那一行。
    floor = min(caps)
    print(f"\n  池子市值下限 ≈ {yi(floor)}")
    check("池子下限在合理量级", 1e9 < floor < 1e12, yi(floor))
    if floor > 200e8:
        notes.append(
            f"池子下限 {yi(floor)} 高于稳健档要求的 200 亿，"
            f"所以 200–{yi(floor)} 之间的标的**本次不在视野内**。"
            "要覆盖它们只能调大 screener.pool-size（清单一次能返 2000 行，不会多一次清单请求）。")

    return rows


def verify_filter_must_stay_raw(trade_date: str) -> None:
    """反向验证：把 filter 百分号编码，服务端**应该**报错。

    这是脚本里最容易被后续改动误伤的一条：如果哪天有人「顺手」统一了编码，
    池子会静默变成空/报错。这里把「必须原样」变成一个每次都会跑的断言。
    """
    section("三、filter 不能被百分号编码（与旧 clist 的 fs 正好相反）")
    expr = f"(TRADE_DATE='{trade_date}')"
    try:
        fetch_json(list_query(8, "TOTAL_MARKET_CAP", -1, expr, encoded_filter=True), LIST_HOSTS)
        check("编码后的 filter 会被服务端拒绝", False,
              "它居然成功了——说明服务端开始接受 URL 解码，"
              "Java 侧「刻意手拼 URL」那段注释与测试现在与事实不符，需要重新评估")
    except RuntimeError as exc:
        text = str(exc)
        check("编码后的 filter 会被服务端拒绝", True, "如期报错")
        if "NoViableAlt" in text or "参数预处理" in text:
            print("  错误信息命中预期形态（参数预处理 / NoViableAlt）")
        else:
            notes.append(f"编码后的 filter 报的是别的错：{text[:160]}——"
                         "仍属失败，但形态变了，值得看一眼")


# ----------------------------------------------------------------------
# 四、行情批量：基本面来源
# ----------------------------------------------------------------------

def verify_quote_batch(rows: list[dict]) -> None:
    section("四、行情批量（ulist.np）——基本面字段来源")

    secids = _secids(rows)
    check("能从清单拼出 secid", len(secids) >= 8, f"{len(secids)} 个")

    body = fetch_json(quote_query(secids[:8]), QUOTE_HOSTS)
    diff = rows_of(body, "diff")
    check("行情批量一次取多只", len(diff) == 8, f"{len(diff)} 条")
    if not diff:
        raise RuntimeError("ulist 没返回数据")

    first = diff[0]
    missing = [k for k in MEANING if k not in first]
    check("章程用到的字段全部存在", not missing, f"缺 {missing}" if missing else "")
    for key, meaning in MEANING.items():
        check(f"{key} 存在且可解释（{meaning}）", key in first, f"值={first.get(key)!r}")

    # 市净率 = 价 / 每股净资产 —— 一条同时验证 f2 / f113 / f23 三个字段
    checked = 0
    for item in diff:
        price = as_number(item.get("f2"))
        bps = as_number(item.get("f113"))
        pb = as_number(item.get("f23"))
        if not price or not bps or not pb or bps <= 0:
            continue
        expected = price / bps
        drift = abs(expected - pb) / pb
        checked += 1
        check(f"{item.get('f12')} 市净率 = 价/每股净资产", drift < 0.05,
              f"标的 {pb} vs 算得 {expected:.4f}（偏差 {drift:.2%}）")
    if checked == 0:
        failures.append("没有一只标的能同时拿到 f2/f23/f113，无法交叉验证市净率口径")

    negatives = [i for i in diff if (as_number(i.get("f115")) or 0) < 0]
    notes.append(
        f"样本 {len(diff)} 只里 PE(TTM) 为负的有 {len(negatives)} 只"
        + ("（亏损股会以负值返回，V5 能命中）" if negatives
           else "（这页没碰到亏损股，换一批再跑一次可以确认负值行为）"))

    for item in diff:
        raw = item.get("f26")
        text = str(raw or "")
        if raw is None or text in ("-", "--"):
            continue
        check(f"{item.get('f12')} f26 是 YYYYMMDD 整数",
              len(text) == 8 and text.isdigit(), f"值={raw!r}")


def verify_batch_limit(secids: list[str]) -> None:
    """实测一次能吃多少只 secids —— 这个数就是 screener.quote-batch-size。

    判据是**返回条数等于请求条数**：少一只就说明超了上限，
    少掉的那只最后会被 fromListRow 之外的逻辑当成「行情没回来」剔除，
    结果是池子静默缩水、摘要里的「缺少的行情数」莫名其妙变大。

    只试几个点，不二分：这个脚本给人的结论，不需要精确到个位，
    而每一次探测都是一次外呼。
    """
    section("五、行情批量一次能吃多少只 secids")
    if len(secids) < 50:
        notes.append("清单行数不足 50，跳过批量上限实测")
        return

    largest_ok = 0
    for size in (50, 100, 200, 300, 500):
        if size > len(secids):
            break
        try:
            body = fetch_json(quote_query(secids[:size]), QUOTE_HOSTS)
            got = len(rows_of(body, "diff"))
        except RuntimeError as exc:
            print(f"  请求 {size} 只 → 失败：{str(exc)[:120]}")
            check(f"{size} 只一次取回", False, "请求就失败了")
            break
        ok = got == size
        check(f"{size} 只一次取回", ok, f"实收 {got}")
        if not ok:
            break
        largest_ok = size

    if largest_ok:
        print(f"\n  实测上限 ≥ {largest_ok} 只")
        notes.append(
            f"行情批量实测至少能吃 {largest_ok} 只。"
            f"当前配置 screener.quote-batch-size 应设为不超过这个数；"
            f"{POOL_SIZE} 只的池子在 {largest_ok} 只/批下是 "
            f"{-(-POOL_SIZE // largest_ok)} 次外呼。")
    else:
        notes.append("批量上限没测出来（第一个档位就失败），保持现在的保守值别上调")


# ----------------------------------------------------------------------
# 六、日线
# ----------------------------------------------------------------------

def verify_kline(secid: str, label: str) -> None:
    section(f"六、日线（{label}，secid={secid}，前复权 fqt=1）")
    body = fetch_json(kline_query(secid), KLINE_HOSTS)
    data = body.get("data") or {}
    klines = data.get("klines") or []
    check("取到日线", len(klines) > 0, f"{len(klines)} 根")
    if not klines:
        return

    bars = []
    for line in klines:
        parts = str(line).split(",")
        if len(parts) < 3:
            continue
        close = as_number(parts[2])
        if close is None or close <= 0:
            continue
        bars.append((parts[0], close))

    check("每根日线都能解析出日期与收盘价", len(bars) == len(klines),
          f"解析成功 {len(bars)}/{len(klines)}")
    check("返回的 secid 与请求一致",
          str(data.get("code") or "") == secid.split(".")[-1], f"返回 {data.get('code')!r}")

    closes = [c for _, c in bars]
    high, low, last = max(closes), min(closes), closes[-1]
    percentile = (last - low) / (high - low) * 100 if high > low else 50.0
    drawdown = (high - last) / high * 100 if high > 0 else 0.0
    ma = sum(closes[-250:]) / min(len(closes), 250)

    print(f"  最后一根：{bars[-1][0]} 收 {last}")
    print(f"  一年区间：{low} ~ {high}")
    print(f"  一年价格分位 {percentile:.2f}% · 距最高点 -{drawdown:.2f}% · MA250 {ma:.3f}")
    check("价格分位落在 0–100 之间", 0 <= percentile <= 100, f"{percentile:.2f}")
    check("最后一根日期不是未来", bars[-1][0] <= date.today().isoformat(), bars[-1][0])
    staleness = (date.today() - datetime.strptime(bars[-1][0], "%Y-%m-%d").date()).days
    notes.append(f"{label} 日线最后一根距今 {staleness} 天"
                 + ("（超过 15 天会被判陈旧并标降级）" if staleness > 15 else ""))


def verify_index_fund_quote() -> None:
    section("七、指数基金批量行情")
    secids = "1.510300,0.159915,1.588000"
    body = fetch_json(quote_query(secids.split(",")), QUOTE_HOSTS)
    diff = rows_of(body, "diff")
    check("一次取多只 ETF", len(diff) == 3, f"{len(diff)} 条")
    for item in diff:
        print(f"  {item.get('f12')} {item.get('f14')} 价={item.get('f2')} "
              f"额={item.get('f6')} 市值={item.get('f20')}")


def main() -> int:
    # 这是个要人坐着看输出的脚本，输出全是中文。Windows 控制台默认 GBK，
    # 不显式切 UTF-8 的话用户看到的是一片乱码，等于白跑。
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    print("「低估精选」字段校验 —— 只读，不改数据")
    print(f"运行时间 {datetime.now().isoformat(timespec='seconds')}")
    print("⚠️ 东财按 IP 按接口限流，这个脚本会打十几次外呼，不要连着反复跑。")
    try:
        trade_date = latest_trade_date()
        pool = verify_pool(trade_date)
        verify_filter_must_stay_raw(trade_date)
        verify_quote_batch(pool)
        secids = _secids(pool)
        verify_batch_limit(secids)
        verify_kline(secids[0] if secids else "1.600519",
                     str(pool[0].get("SECURITY_NAME_ABBR") or "池内第一只"))
        verify_kline("1.510300", "沪深300ETF")
        verify_index_fund_quote()
    except RuntimeError as exc:
        print(f"\n取数失败：{exc}")
        print("连不上东财，或者**这个 IP 正在被限流**。")
        print("限流的特征：TCP 连得上，但服务端一个字节都不回就把连接掐了。")
        print("那是按 IP 计时的，等十几分钟再跑，别反复重试——越试封得越久。")
        return 1

    section("结论")
    for note in notes:
        print(f"  · {note}")
    if failures:
        print(f"\n  有 {len(failures)} 项不一致：")
        for item in failures:
            print(f"    - {item}")
        print("\n  在这些字段被用于排雷与打分之前，先按上面的输出核对含义。")
        return 1

    print("\n  全部通过：字段 ID 与含义仍与 automation/agents/stock_screening_rules.md 一致。")
    print(f"  对照基准 {date.today().isoformat()}；东财改口径不会通知，隔一段时间重跑一次。")
    return 0


def _secids(pool: list[dict]) -> list[str]:
    """清单行 → secid，沪 1 / 深 0，北交所不纳入（与 Java 侧 fromListRow 同一套规则）。"""
    out = []
    for item in pool:
        secucode = str(item.get("SECUCODE") or "")
        if "." not in secucode:
            continue
        code, market = secucode.split(".", 1)
        market = market.upper()
        if market == "SH":
            out.append(f"1.{code}")
        elif market == "SZ":
            out.append(f"0.{code}")
    return out


if __name__ == "__main__":
    sys.exit(main())