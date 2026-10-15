#!/usr/bin/env python3
"""校验「代码查询 · 深度分析」要接的四个新闻/公告源，钉死四件**猜不出来**的事。

为什么要有这个脚本：这一段的四个源都只有**一次真实探测**撑腰，而它们的坑全在
「猜错了不报错，只是永远拿不到数据」这一类上——页面不崩，只是那一栏永远空着，
排查时会一路往网络、限流、字段名上想，全想错。写代码的机器到这几个域名的网络不通，
所以只能先在能联网的机器上把下面四条钉死，再照着结论写客户端。

## 一、这个脚本要钉死的四件事

  1. **东财资讯搜索的 JSONP 壳形态，以及 `param` 要不要二次 URL 编码。**
     那个接口的查询条件是塞进一个 URL 参数里的 **JSON 字符串**，而 JSON 里本身又带
     `preTag=<em>` 这种要转义的东西。于是「编码一次」和「编码两次」都说得通，
     而**服务端只认其中一种**，认不出时通常不报错、直接回一个空壳。
     脚本把两种形态各打一次，把**实际回数据的那个**写进结论。
  2. **东财公告 `data.list` 的日期字段到底叫 `notice_date` 还是 `display_time`。**
     两个都出现过，含义还不一样：一个是公告日，一个是入库/展示时间。
     选错了，页面上会有一批「未来日期」或「批量同一秒」的假日期。
     脚本把两个字段**都**打印出来，并对同一批公告比对它们是否一致。
  3. **Google News RSS 在本机（可能挂着系统代理）是否可达。**
     国内直连基本不通，而挂上代理后国内域名又可能反过来失败——两边是矛盾的，
     只能实测。脚本会把代理环境变量一并打出来，免得把「代理没生效」读成「源挂了」。
  4. **新浪财经那一路到底有没有可用的 JSON 接口。**
     候选有两个形态：`feed.mix.sina.com.cn` 的滚动新闻 JSON 接口，以及
     `vip.stock.finance.sina.com.cn` 的个股新闻页。前者回 JSON，后者回 HTML——
     **回 HTML 的那个对我们没用**（章程 §9.10 明令不读正文，剥 HTML 是纯负担）。
     脚本把两个都打一次，让结论落在实测上。

## 二、只读、不重试、每 URL 只打一次

不发任何写请求，不入库，不推送。每个 URL **只发一次**，失败就是失败——
东财按 IP 按接口限流，冷却期间唯一该做的就是别再碰它，重试只会把封禁推得更深。

[!] 这个脚本会打 **6–8 次**外呼，其中三次打东财。**不要连着反复跑。**
   确认失败也等十几分钟再来。看到「TCP 连得上、服务端一个字节都不回就掐连接」，
   那就是限流的特征，不是网络断。

跑法（Windows 上 python 是商店占位符，必须用 py -3）：

    py -3 automation/scripts/verify_analysis_sources.py
    py -3 automation/scripts/verify_analysis_sources.py --skip-google

`--skip-google` 跳过第四节。**跳过不等于通过**——结论里会写明「跳过的项没有被校验」。

退出码 0 = 每一项都拿到了可用的答案；1 = 有项没打出来，**照结论改代码之前先查清楚**。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime

# Windows 中文控制台的编码是 GBK，任何一个它编不出来的字符都会让 print **直接抛异常**。
# 最要命的不是难看，是**脚本跑到一半死掉**：前面几节的外呼已经打出去了，
# 后面的没有，而屏幕上只剩一个 traceback——一次探测的额度白烧，还没拿到结论。
# 把「编不出来」降级成打一个 `?`，别把整个脚本停在半路。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(errors="replace")

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

# 样本：一只成交活跃的主板白马 + 一只宽基 ETF。
# 用两个是因为**公告接口对 ETF 的 `ann_type` 支持不一定一样**——
# 个股是 A（全部），ETF 可能只认某些类型，甚至压根不认。
SAMPLE_STOCK = "300274"
SAMPLE_ETF = "510300"
SAMPLE_NAME = "阳光电源"
SAMPLE_INDUSTRY_KEYWORD = "光伏 政策"

# ----------------------------------------------------------------------
# 四个源
# ----------------------------------------------------------------------

NOTICE_HOST = "np-anotice-stock.eastmoney.com"
NOTICE_REFERER = "https://data.eastmoney.com/notices/"

SEARCH_HOST = "search-api-web.eastmoney.com"
SEARCH_REFERER = "https://so.eastmoney.com/"

SINA_ROLL_HOST = "feed.mix.sina.com.cn"
SINA_CORP_HOST = "vip.stock.finance.sina.com.cn"
SINA_REFERER = "https://finance.sina.com.cn/"

GOOGLE_NEWS_HOST = "news.google.com"

# 报告用：哪一项钉住了、结论是什么。**结论必须落成一句能照着写代码的话**，
# 只打一行原始响应等于把活儿又推回给人。
findings: dict[str, str] = {}
# 「探出来了，但结论是坏消息」——**这不是失败，不该让退出码变红**。
# 比如新浪的关键词检索不生效，那是我们要的答案（结论=这一路不接），
# 把它记成 failures 会让人以为「网络不稳，重跑一次就好」，然后白烧一次配额。
cautions: list[str] = []
failures: list[str] = []
network_failures: list[str] = []
skipped: list[str] = []


# ----------------------------------------------------------------------
# 通用探测
# ----------------------------------------------------------------------

def describe_proxy() -> list[str]:
    """把代理环境变量打出来。

    这不是凑数：本机实测过一次「系统代理开着 → 国内行情域名全挂」，
    而 Google News 这类域名又**必须**走代理才能通。两边是矛盾的，
    所以看到某一节失败时，第一个要排除的就是「这次到底走没走代理」。
    """
    keys = ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "NO_PROXY", "no_proxy")
    out = []
    for k in keys:
        v = os.environ.get(k)
        if v:
            out.append(f"{k}={v}")
    return out


def probe(url: str, referer: str, accept: str = "*/*") -> dict:
    """**打一次**，永远不抛异常。失败也返回一个能打印的 dict。

    刻意不重试：东财按 IP 按接口限流，重试只会把封禁推得更深。
    """
    req = urllib.request.Request(url, headers={
        "Referer": referer,
        "User-Agent": UA,
        "Accept": accept,
        "Accept-Language": "zh-CN,zh;q=0.9",
    })
    result = {"url": url, "status": None, "content_type": None, "body": "", "error": None}
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            result["status"] = resp.status
            result["content_type"] = resp.headers.get("Content-Type")
            # 用 utf-8 + replace 解：新浪有几个页面是 GBK，解错了能看出来（一片问号），
            # 而不是抛异常——抛异常会把「编码问题」误报成「取数失败」。
            result["body"] = raw.decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        result["status"] = exc.code
        result["content_type"] = exc.headers.get("Content-Type") if exc.headers else None
        try:
            result["body"] = exc.read().decode("utf-8", "replace")
        except Exception:  # noqa: BLE001 - 读不出来就算了，别盖住原始错误
            pass
        result["error"] = f"HTTP {exc.code}"
    except Exception as exc:  # noqa: BLE001 - 网络层什么都可能抛，这一节不该炸掉整个脚本
        result["error"] = f"{type(exc).__name__}: {exc}"
    return result


def report(tag: str, r: dict, max_body: int = 400) -> None:
    """打印一次探测的**全部**可判断信息：状态码、Content-Type、前 400 字符。"""
    print(f"\n  ── {tag}")
    print(f"     URL          {r['url'][:150]}")
    print(f"     状态码       {r['status']}   Content-Type  {r['content_type']}")
    if r["error"]:
        print(f"     错误         {r['error']}")
    head = r["body"][:max_body].replace("\n", "\\n")
    print(f"     前 400 字符  {head if head else '（空响应体）'}")


def note_network(tag: str, r: dict) -> None:
    network_failures.append(f"{tag} —— {r['error'] or ('HTTP ' + str(r['status']))}")


def parse_jsonp(text: str) -> tuple[object | None, str | None, str | None]:
    """剥 JSONP 壳。返回 (对象, 壳前缀, 剥壳后的原文片段)。

    壳的形态实测有三种：`cb({...})`、`/**/cb({...})`、以及**干脆不带壳的裸 JSON**。
    第三种要认，否则会把「服务端改了行为」读成「解析失败」。
    """
    t = text.strip()
    if t.startswith("{") or t.startswith("["):
        return _loads(t), None, t[:200]
    m = re.match(r"^\s*(?:/\*\*/\s*)?([\w$.]+)\s*\(", t)
    if not m:
        return None, None, t[:200]
    inner = t[m.end():]
    idx = inner.rfind(")")
    if idx >= 0:
        inner = inner[:idx]
    inner = inner.strip().rstrip(";").strip()
    return _loads(inner), m.group(1), inner[:200]


def _loads(s: str) -> object | None:
    try:
        return json.loads(s)
    except json.JSONDecodeError:
        return None


def keys_of(obj: object) -> str:
    if isinstance(obj, dict):
        return ", ".join(obj.keys())
    return f"（不是对象，是 {type(obj).__name__}）"


def first_item(obj: object, *path: str) -> dict | None:
    """沿路径找第一个 dict。找不到就返回 None——**不猜别名**，猜了就等于把结论写死在自己脑子里。"""
    cur = obj
    for p in path:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(p)
    if isinstance(cur, list):
        return cur[0] if cur else None
    return cur if isinstance(cur, dict) else None


# ----------------------------------------------------------------------
# 一、东财公告
# ----------------------------------------------------------------------

def notice_url(code: str, ann_type: str = "A", page_size: int = 20) -> str:
    return (f"https://{NOTICE_HOST}/api/security/ann"
            f"?sr=-1&page_size={page_size}&page_index=1"
            f"&ann_type={ann_type}&client_source=web&stock_list={code}")


def verify_notice() -> None:
    for tag, code in (("个股", SAMPLE_STOCK), ("ETF", SAMPLE_ETF)):
        r = probe(notice_url(code), NOTICE_REFERER, "application/json, text/plain, */*")
        report(f"公告 {tag} {code}", r)
        if r["status"] != 200 or not r["body"]:
            note_network(f"公告 {tag}", r)
            continue

        body = _loads(r["body"])
        if body is None:
            failures.append(f"公告 {tag} 回了 200 但不是 JSON —— 客户端必须按「非 JSON」处理，不能直接反序列化")
            continue

        print(f"     顶层字段     {keys_of(body)}")
        data = body.get("data")
        print(f"     data 字段    {keys_of(data)}")
        items = (data or {}).get("list") if isinstance(data, dict) else None
        total_hits = (data or {}).get("total_hits") if isinstance(data, dict) else None
        print(f"     条数         {len(items) if isinstance(items, list) else '没有 data.list'}"
              f"      total_hits  {total_hits}")
        if not isinstance(items, list) or not items:
            # `success: 1` + `total_hits: 0` 是**明确的「这个代码在这个接口里没有公告」**，
            # 不是取数失败。差别很大：失败要重试/报警，空结果只要在页面上如实写「本次取到 0 条」。
            if isinstance(data, dict) and "list" in data:
                print("     >> 字段名是对的（data.list 存在），是**真空**——"
                      "页面上如实写「本次取到 0 条」，不要用别的接口硬凑")
                findings[f"公告接口（{tag}）"] = (
                    f"{tag} {code} 实测 data.list 为空且 total_hits=0 —— "
                    "接口认这个代码但确实没有公告，**按空结果呈现**")
                if tag == "ETF":
                    cautions.append(f"ETF {code} 在公告接口上恒为空 —— "
                                    "基金公告不从这个接口出，ETF 的公告这一栏会是空的")
            else:
                failures.append(f"公告 {tag} {code} 的 data 里根本没有 list 字段 —— 字段名变了")
            continue

        item = items[0]
        print(f"     单条字段     {keys_of(item)}")
        for f in ("notice_date", "display_time", "title", "art_code"):
            if f in item:
                print(f"       {f:<14} {item[f]}")

        # 两个日期字段都在时**必须比对**：它们的含义不同，页面上取错了一个会给出
        # 一批「未来日期」或「同一个时间戳刷屏」的假日期，而且不报错。
        if "notice_date" in item and "display_time" in item:
            nd = str(item["notice_date"])[:10]
            dt = str(item["display_time"])[:10]
            same = sum(1 for i in items[:10]
                       if str(i.get("notice_date"))[:10] == str(i.get("display_time"))[:10])
            print(f"     两个日期字段前 10 条里一致 {same} 条（notice_date={nd} display_time={dt}）")
            # display_time 的毫秒位用的是**冒号**分隔（16:02:03:301），不是点。
            # `2026-10-15 16:02:03:301` 直接丢给 LocalDateTime.parse 会抛异常——
            # 而且是在**运行时**、只在有公告的票上才炸。
            if re.search(r"\d{2}:\d{2}:\d{2}:\d{3}", str(item["display_time"])):
                print("     >> display_time 的毫秒位是**冒号**分隔（HH:mm:ss:SSS），"
                      "不是标准格式，不能直接反序列化成 LocalDateTime")
                cautions.append("notice 的 display_time 是 HH:mm:ss:SSS（冒号分隔毫秒），"
                                "不是标准 LocalDateTime 格式")
            findings["公告日期字段"] = (
                f"用 notice_date（公告日，标准 `yyyy-MM-dd HH:mm:ss`）；"
                f"display_time 与它前 10 条里一致 {same} 条，且毫秒位用冒号——"
                "**取 notice_date，不要碰 display_time**")
        else:
            present = [f for f in ("notice_date", "display_time") if f in item]
            findings["公告日期字段"] = f"只有 {present} 存在；客户端按这个字段名取值"

        # `columns[].column_name` 是**公告分类**（实测出现过「股份质押、冻结」），
        # 正好是方案里说「无源」的筹码类事项——它其实有出处。
        cols = item.get("columns")
        if isinstance(cols, list) and cols:
            names = [str(c.get("column_name")) for c in cols if isinstance(c, dict)]
            print(f"     分类         {'、'.join(names)}")
            findings["公告分类"] = (f"columns[].column_name 是公告分类（实测出现「{'、'.join(names)}」）——"
                                    "质押/解禁/减持这类「人工核实」项可以据此变成有出处的列举")

        findings["公告接口"] = (f"{NOTICE_HOST}/api/security/ann?sr=-1&page_size=N&page_index=1"
                                f"&ann_type=A&client_source=web&stock_list=<code>，"
                                f"{tag} {code} 实测拿到 {len(items)} 条")


# ----------------------------------------------------------------------
# 二、东财资讯搜索（JSONP + 那个一定要试两次的 param）
# ----------------------------------------------------------------------

def search_param(keyword: str, page_size: int = 10) -> str:
    payload = {
        "uid": "",
        "keyword": keyword,
        "type": ["cmsArticleWebOld"],
        "client": "web",
        "clientType": "web",
        "clientVersion": "curr",
        "param": {
            "cmsArticleWebOld": {
                "searchScope": "default",
                "sort": "default",
                "pageIndex": 1,
                "pageSize": page_size,
                "preTag": "<em>",
                "postTag": "</em>",
            }
        },
    }
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def search_url(keyword: str, encode_times: int) -> str:
    """encode_times=1 → 编一次；=2 → 编两次（`%` 再编成 `%25`）。

    **这一条正是本脚本存在的理由**：两种写法都说得通，服务端只认一种。
    """
    param = search_param(keyword)
    for _ in range(encode_times):
        param = urllib.parse.quote(param, safe="")
    return f"https://{SEARCH_HOST}/search/jsonp?cb=cb&param={param}"


def verify_search() -> None:
    print("\n  搜索条件（未编码原文，客户端要原样拼进去）：")
    print(f"     {search_param(SAMPLE_NAME)}")

    winner = None
    rejected: list[int] = []
    for times in (1, 2):
        r = probe(search_url(SAMPLE_NAME, times), SEARCH_REFERER,
                  "application/javascript, text/plain, */*")
        report(f"资讯搜索 param 编码 {times} 次", r)
        if r["status"] != 200 or not r["body"]:
            # **这一档的 400 是答案，不是故障**：服务端明确拒绝了这种编码形态。
            # 记成 network_failure 会让人以为「网络不稳，重跑一次就好」，
            # 于是白烧一次配额，还学不到东西。
            if r["status"] == 400 and "json" in r["body"]:
                rejected.append(times)
                print(f"     >> 服务端明确拒绝这一档 —— 这正是要钉的结论：**编码 {times} 次不行**")
                continue
            note_network(f"资讯搜索（编码 {times} 次）", r)
            continue

        obj, shell, inner = parse_jsonp(r["body"])
        print(f"     JSONP 壳     {shell if shell else '（没有壳，是裸 JSON）'}")
        print(f"     剥壳后开头   {inner[:200]}")
        if obj is None:
            print("     解析结果     × 剥壳后仍不是 JSON")
            continue

        print(f"     顶层字段     {keys_of(obj)}")
        rows = first_item(obj, "result", "cmsArticleWebOld")
        if rows is None:
            # 空壳也是结论：服务端认出/没认出这个形态，表现就在这里。
            print(f"     result       {str(obj.get('result'))[:200] if isinstance(obj, dict) else ''}")
            continue

        print(f"     单条字段     {keys_of(rows)}")
        for f in ("title", "date", "url", "mediaName", "content"):
            if f in rows:
                print(f"       {f:<12} {str(rows[f])[:80]}")
        if winner is None:
            winner = times
            findings["资讯搜索"] = (f"param 需要 URL 编码 {times} 次"
                                    + (f"（编码 {'/'.join(str(t) for t in rejected)} 次被服务端回 400 拒绝）"
                                       if rejected else "")
                                    + f"；响应是 JSONP（壳实测 {shell or '无'}），"
                                    "数据在 result.cmsArticleWebOld[]")
        else:
            print("     （两种编码都能出数据——客户端按编码次数少的那种写，少一层出错的机会）")

    if winner is None:
        failures.append("资讯搜索两种 param 编码都没拿到条目 —— 接口形态变了，别照现成写法硬套")
    else:
        # 标题里带 <em></em> 强调标签（就是上面那个 preTag/postTag 干的），
        # 清洗管线必须去掉——**不能只想到 HTML 实体，漏了这层再转义一遍的标签**。
        print("\n     注意：title / content 里包着 <em></em> 强调标签（preTag/postTag 造成的），"
              "清洗时必须剥掉")
        findings["资讯搜索 · 标题"] = "title 带 <em></em> 强调标签，清洗时剥掉；" \
                                   "content 是正文片段，按 §9.10 **不读**"


# ----------------------------------------------------------------------
# 三、新浪财经：两个候选形态，只有回 JSON 的那个能用
# ----------------------------------------------------------------------

def sina_roll_url(keyword: str, num: int = 10) -> str:
    return (f"https://{SINA_ROLL_HOST}/api/roll/get?pageid=153&lid=2516"
            f"&num={num}&page=1&k={urllib.parse.quote(keyword)}")


def sina_corp_url(code: str) -> str:
    market = "sz" if code[0] in "0123" else "sh"
    return (f"https://{SINA_CORP_HOST}/corp/go.php/vCB_AllNewsStock/"
            f"symbol/{market}{code}.phtml")


def verify_sina() -> None:
    r = probe(sina_roll_url(SAMPLE_NAME), SINA_REFERER, "application/json, */*")
    report("新浪滚动新闻 JSON 候选", r)
    if r["status"] == 200 and r["body"]:
        body = _loads(r["body"])
        if body is None:
            print("     解析结果     × 不是 JSON")
        else:
            print(f"     顶层字段     {keys_of(body)}")
            result = body.get("result") if isinstance(body, dict) else None
            row = first_item(body, "result", "data")
            if row is None:
                print(f"     result.data  {str(result)[:200]}")
            else:
                print(f"     单条字段     {keys_of(row)}")
                for f in ("title", "url", "ctime", "media_name", "intro"):
                    if f in row:
                        print(f"       {f:<12} {str(row[f])[:80]}")
                # **本节的真正目的**：`k=` 到底过不过滤。接口回 200 不代表关键词生效——
                # 它可能老老实实把整条财经流端回来，而这不报错。
                # 判据：回的第一条标题里有没有关键词。没有 = 没过滤。
                titles = [str(i.get("title", "")) for i in (result or {}).get("data", [])[:10]]
                hit = sum(1 for t in titles if SAMPLE_NAME in t)
                total = (result or {}).get("total")
                print(f"     前 10 条标题命中「{SAMPLE_NAME}」 {hit} 条     result.total = {total}")
                if hit == 0:
                    print("     >> 关键词**没有生效**：这回来的是一条通用财经流，不是这只票的新闻。"
                          "接了这一路，就要靠打分后的相关性过滤把几乎全部条目丢掉——"
                          "花一次外呼换来一条要扔的流，不接")
                    findings["新浪财经"] = (
                        f"{SINA_ROLL_HOST}/api/roll/get 可访问但 `k=` 参数**不过滤**"
                        f"（前 10 条命中 0 条，total={total} 是整条财经流）——**这一路不接**")
                    cautions.append("新浪财经：接口通但关键词检索不生效，接了就只是白花一次外呼")
                else:
                    findings["新浪财经"] = (f"{SINA_ROLL_HOST}/api/roll/get 可用且关键词生效，"
                                            "数据在 result.data[]，字段如上")
    else:
        note_network("新浪滚动新闻", r)

    r2 = probe(sina_corp_url(SAMPLE_STOCK), SINA_REFERER, "text/html, */*")
    report("新浪个股新闻页（HTML 候选）", r2)
    if r2["status"] == 200 and r2["body"]:
        is_html = "<html" in r2["body"][:2000].lower() or "<div" in r2["body"][:2000].lower()
        hit = SAMPLE_NAME in r2["body"]
        print(f"     是不是 HTML  {is_html}    正文里出现「{SAMPLE_NAME}」  {hit}")
        if is_html:
            # 结论要明确：这一路**不用**。章程 §9.10 只输出标题+来源+日期+链接，
            # 不读正文；一个 HTML 页面意味着要写一条能扛住改版的解析器，收益为零。
            findings["新浪个股新闻页"] = ("是 HTML，**不用这一路**：§9.10 只输出标题+来源+日期+链接、"
                                          "不读正文，为它写一条会随改版失效的解析器收益为零")
    else:
        note_network("新浪个股新闻页", r2)

    if "新浪资讯" not in findings:
        findings.setdefault("新浪源结论文档", "两个候选都没拿到可用 JSON —— 这一路先不接，不要用 HTML 硬顶")


# ----------------------------------------------------------------------
# 四、Google News RSS（政策检索）
# ----------------------------------------------------------------------

def google_news_url(keyword: str) -> str:
    return (f"https://{GOOGLE_NEWS_HOST}/rss/search"
            f"?q={urllib.parse.quote(keyword)}&hl=zh-CN&gl=CN&ceid=CN:zh-Hans")


def verify_google_news() -> None:
    r = probe(google_news_url(SAMPLE_INDUSTRY_KEYWORD), "https://news.google.com/",
              "application/rss+xml, application/xml, text/xml, */*")
    report("Google News RSS", r)
    if r["status"] != 200 or not r["body"]:
        note_network("Google News RSS", r)
        return

    try:
        root = ET.fromstring(r["body"])
    except ET.ParseError as exc:
        # RSS 回的不是 XML，通常是**拦截页或登录页**——那比「连不上」更麻烦，
        # 因为它状态码是 200。
        failures.append(f"Google News RSS 回了 200 但不是 XML（{exc}）——"
                        "这是被拦的典型表现，按不可用处理，别当成空结果")
        return

    items = root.findall(".//item")
    print(f"     item 条数    {len(items)}")
    if not items:
        failures.append("Google News RSS 解析出了 XML 但 0 条 item —— "
                        "要么关键词召回为空，要么 query 形态不对")
        return

    print(f"     单条标签     {', '.join(c.tag for c in items[0])}")
    for tag in ("title", "pubDate", "link", "source"):
        el = items[0].find(tag)
        if el is not None:
            print(f"       {tag:<10} {str(el.text)[:90]}")

    # 三条会静默出错的地方，都要在这里点出来——它们都不报错，只是让页面上的东西不对。
    link = str((items[0].findtext("link") or ""))
    if GOOGLE_NEWS_HOST in link:
        print("     >> link 是 news.google.com 的跳转地址，不是原站地址（点击会经 Google 跳一次）")
        cautions.append("Google News 的 link 是 google 跳转地址，不是发布方原链")
    print("     >> pubDate 是 GMT，显示前要换算到 Asia/Shanghai，否则 20:00 之后的文章会显示成第二天")
    cautions.append("Google News 的 pubDate 是 GMT，必须换算到 Asia/Shanghai 再显示日期")

    findings["政策检索"] = (f"Google News RSS 可达（{len(items)} 条，"
                            "标签 title/link/guid/pubDate/description/source）；"
                            "description 里带 HTML，**不取**")


# ----------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description="校验深度分析要接的新闻/公告源（只读、不重试）")
    ap.add_argument("--skip-google", action="store_true",
                    help="跳过 Google News RSS（需要代理，且与国内域名可能互相打架）")
    args = ap.parse_args()

    print("「代码查询 · 深度分析」数据源校验 —— 只读，不改数据")
    print(f"运行时间 {datetime.now().isoformat(timespec='seconds')}")
    print("[!] 会打 6-8 次外呼，其中三次打东财。**不要连着反复跑**，失败也等十几分钟再来。")
    proxy = describe_proxy()
    print(f"代理环境     {', '.join(proxy) if proxy else '（没有任何代理环境变量）'}")

    def section(title: str) -> None:
        print(f"\n{'=' * 68}\n{title}\n{'=' * 68}")

    def run(title: str, fn) -> None:
        section(title)
        before_fail = len(failures)
        before_net = len(network_failures)
        try:
            fn()
        except Exception as exc:  # noqa: BLE001 - 一节炸掉不该取消其余各节
            failures.append(f"{title} 自身抛异常：{type(exc).__name__}: {exc}")
        if len(failures) == before_fail and len(network_failures) == before_net:
            print("\n  [OK] 这一节拿到了可用的答案")

    run("一、东财公告", verify_notice)
    run("二、东财资讯搜索", verify_search)
    run("三、新浪财经候选形态", verify_sina)
    if args.skip_google:
        section("四、Google News RSS —— 本轮按 --skip-google 跳过（未校验）")
        skipped.append("四、Google News RSS（--skip-google）")
    else:
        run("四、Google News RSS", verify_google_news)

    section("结论：照着这几句写客户端")
    if findings:
        for k, v in findings.items():
            print(f"  · {k}：{v}")
    else:
        print("  （什么都没钉住）")
    for item in skipped:
        print(f"  [跳过] 没有校验，不算通过：{item}")

    if cautions:
        print(f"\n  [!] 探出来了，但结论是坏消息（**不是取数失败，别重跑**）：")
        for item in cautions:
            print(f"    - {item}")

    if network_failures:
        print(f"\n  有 {len(network_failures)} 项**取数失败**（不是口径问题）：")
        for item in network_failures:
            print(f"    - {item}")
        print("\n  先分清两件事：")
        print("    1. 限流：TCP 连得上，但服务端一个字节都不回就把连接掐了。按 IP 计时，"
              "等十几分钟再来，别重试。")
        print("    2. 代理：Google News 要代理，国内域名挂了代理反而可能失败。"
              "把上面那行代理环境变量与失败的那一节对上。")

    if failures:
        print(f"\n  有 {len(failures)} 项**对不上**：")
        for item in failures:
            print(f"    - {item}")
        print("\n  照现成写法接源之前，先按上面的输出把形态确认下来。")
        return 1

    if network_failures or skipped:
        return 1

    print("\n  全部通过。四个源的形态已钉死在上面「结论」一节，"
          "写客户端时按它来，别按记忆里的写法。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
