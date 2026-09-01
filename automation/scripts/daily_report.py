# -*- coding: utf-8 -*-
"""
AI 每日高价值简报生成脚本
用于 GitHub Actions 定时执行
功能：抓取 AI 资讯 → Gemini 生成精炼简报 → 推送企业微信
"""

import os
import json
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from email.utils import parsedate_to_datetime
import feedparser
from datetime import datetime, timezone, timedelta
from urllib.parse import quote
from urllib.request import urlopen, Request
from urllib.error import URLError, HTTPError

# 北京时区 (UTC+8)
BEIJING_TZ = timezone(timedelta(hours=8))

def now_beijing():
    """获取当前北京时间"""
    return datetime.now(BEIJING_TZ)

# ─── 推送 Spring Boot 后端 ─────────────────────────────────────────

RETRYABLE_STATUS_CODES = {408, 429, 500, 502, 503, 504}
WECHAT_RETRYABLE_ERRCODES = {-1, 45009}


def push_to_backend(edition, report_date, title, content, summary, run_id):
    """将简报 POST 到 Spring Boot 后端 API 存储。"""
    import requests as req
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url:
        print("  ⚠️ 未配置 BACKEND_API_URL，跳过后端存储")
        return False
    if not ingest_token:
        print("  ⚠️ 未配置 REPORT_INGEST_TOKEN，跳过后端存储")
        return False

    max_content_length = 30000
    truncated_content = content if len(content) <= max_content_length else content[:max_content_length] + "\n\n> ...(内容已截断，完整版请查看企业微信)"
    payload = {
        "edition": edition,
        "reportDate": report_date,
        "title": title,
        "content": truncated_content,
        "summary": summary,
        "runId": run_id
    }

    max_retries = 3
    for attempt in range(max_retries):
        try:
            resp = req.post(
                f"{backend_url}/api/reports/ingest",
                json=payload,
                headers={"X-Ingest-Token": ingest_token},
                timeout=(5, 60)
            )
            try:
                body = resp.json()
            except ValueError:
                body = None
            if resp.status_code == 200 and isinstance(body, dict) and body.get("code") == 200:
                print(f"  ✅ 已同步到后端 API（第 {attempt + 1} 次尝试）")
                return True
            message = body.get("message") if isinstance(body, dict) else "响应不是有效 JSON"
            print(f"  ⚠️ 后端 API 返回 HTTP {resp.status_code}，业务响应: {message}")
            business_code = body.get("code") if isinstance(body, dict) else None
            retryable = resp.status_code in RETRYABLE_STATUS_CODES or (
                resp.status_code == 200 and isinstance(business_code, int) and business_code >= 500
            )
            if not retryable:
                return False
        except (req.ConnectionError, req.Timeout) as e:
            print(f"  ⚠️ 后端 API 同步失败: {e}")
        except req.RequestException as e:
            print(f"  ⚠️ 后端 API 同步失败且不可重试: {e}")
            return False
        if attempt < max_retries - 1:
            wait_time = (attempt + 1) * 5
            print(f"  ⏳ {wait_time} 秒后重试...")
            time.sleep(wait_time)
    return False


def dispatch_due_pushes():
    """到点后补推：生成可以提前，推送必须按用户订阅时刻。"""
    import requests as req
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not ingest_token:
        return False
    try:
        resp = req.post(
            f"{backend_url}/api/reports/dispatch-due",
            headers={"X-Ingest-Token": ingest_token},
            timeout=(5, 60),
        )
        try:
            body = resp.json()
        except ValueError:
            body = None
        ok = resp.status_code == 200 and isinstance(body, dict) and body.get("code") == 200
        if not ok:
            print(f"  ⚠️ 到期推送补扫失败: HTTP {resp.status_code}")
        return ok
    except req.RequestException as e:
        print(f"  ⚠️ 到期推送补扫失败: {e}")
        return False


def post_poller_heartbeat(detail="ok"):
    import requests as req
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not ingest_token:
        return False
    try:
        resp = req.post(
            f"{backend_url}/api/reports/poller-heartbeat",
            json={"detail": detail},
            headers={"X-Ingest-Token": ingest_token},
            timeout=(5, 15),
        )
        return resp.status_code == 200
    except req.RequestException as e:
        print(f"  ⚠️ 心跳上报失败: {e}")
        return False


def report_generation_status(edition, report_date, topic, status, message="", run_id=""):
    import requests as req
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not ingest_token:
        return False
    payload = {
        "edition": edition,
        "reportDate": report_date,
        "topic": topic,
        "status": status,
        "message": message,
        "runId": run_id,
    }
    try:
        resp = req.post(
            f"{backend_url}/api/reports/generation-status",
            json=payload,
            headers={"X-Ingest-Token": ingest_token},
            timeout=(5, 20),
        )
        try:
            body = resp.json()
        except ValueError:
            body = None
        return resp.status_code == 200 and isinstance(body, dict) and body.get("code") == 200
    except req.RequestException as e:
        print(f"  ⚠️ 主题状态上报失败: {e}")
        return False


def fetch_due_generations(report_date=None):
    """询问后端：当前已到最早订阅时刻、尚未生成的主题段。"""
    import requests as req
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not ingest_token:
        return []
    params = {}
    if report_date:
        params["date"] = report_date
    try:
        resp = req.get(
            f"{backend_url}/api/reports/due-generations",
            params=params,
            headers={"X-Ingest-Token": ingest_token},
            timeout=(5, 30),
        )
        try:
            body = resp.json()
        except ValueError:
            body = None
        if resp.status_code == 200 and isinstance(body, dict) and body.get("code") == 200:
            data = body.get("data")
            items = data.get("items") if isinstance(data, dict) else data
            if isinstance(items, list):
                due = []
                for item in items:
                    if not isinstance(item, dict):
                        continue
                    topic = str(item.get("topic") or "").strip()
                    window = str(item.get("window") or "").strip()
                    generate_at = str(item.get("generateAt") or "").strip()
                    if topic and window:
                        due.append({
                            "window": window,
                            "topic": topic,
                            "generateAt": generate_at,
                            "intent": normalize_intent(item.get("intent")),
                        })
                return due
        print(f"  ⚠️ 获取到期主题失败: HTTP {resp.status_code}")
    except req.RequestException as e:
        print(f"  ⚠️ 获取到期主题失败: {e}")
    return []


def fetch_subscribed_topics(edition):
    """询问后端：当天该版次有哪些主题被普通用户勾选。"""
    import requests as req
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not ingest_token:
        return []
    try:
        resp = req.get(
            f"{backend_url}/api/reports/subscribed-topics",
            params={"edition": edition},
            headers={"X-Ingest-Token": ingest_token},
            timeout=(5, 30),
        )
        try:
            body = resp.json()
        except ValueError:
            body = None
        if resp.status_code == 200 and isinstance(body, dict) and body.get("code") == 200:
            data = body.get("data")
            topics = data.get("topics") if isinstance(data, dict) else data
            if isinstance(topics, list):
                return [topic.strip() for topic in topics if isinstance(topic, str) and topic.strip()]
        print(f"  ⚠️ 获取订阅主题失败: HTTP {resp.status_code}")
    except req.RequestException as e:
        print(f"  ⚠️ 获取订阅主题失败: {e}")
    return []


def push_topic_section(edition, report_date, topic, title, content, summary, run_id):
    """将单个主题段落入库。失败不影响其他主题和公共简报。"""
    import requests as req
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not ingest_token:
        return False
    payload = {
        "edition": edition,
        "reportDate": report_date,
        "topic": topic,
        "title": title,
        "content": content,
        "summary": summary,
        "runId": run_id,
    }
    try:
        resp = req.post(
            f"{backend_url}/api/reports/sections/ingest",
            json=payload,
            headers={"X-Ingest-Token": ingest_token},
            timeout=(5, 60),
        )
        try:
            body = resp.json()
        except ValueError:
            body = None
        if resp.status_code == 200 and isinstance(body, dict) and body.get("code") == 200:
            print(f"  ✅ 主题「{topic}」已入库")
            return True
        message = body.get("message") if isinstance(body, dict) else "响应不是有效 JSON"
        print(f"  ⚠️ 主题「{topic}」入库失败: {message}")
    except req.RequestException as e:
        print(f"  ⚠️ 主题「{topic}」入库失败: {e}")
    return False


# ─── 企业微信推送 ───────────────────────────────────────────────

def push_to_wechat(content, webhook_url, max_retries=3):
    """通过企业微信 Webhook 发送 Markdown 消息。"""
    import requests
    payload = {
        "msgtype": "markdown",
        "markdown": {"content": content}
    }
    headers = {"Content-Type": "application/json; charset=utf-8"}
    for attempt in range(max_retries):
        try:
            resp = requests.post(webhook_url, json=payload, headers=headers, timeout=15)
            try:
                data = resp.json()
            except ValueError:
                data = None
            errcode = data.get("errcode") if isinstance(data, dict) else None
            if resp.status_code == 200 and errcode == 0:
                print(f"✅ 推送成功 ({len(content.encode('utf-8'))} bytes)")
                return True
            print(f"❌ 推送失败: HTTP {resp.status_code}, errcode={errcode}")
            if resp.status_code not in RETRYABLE_STATUS_CODES and errcode not in WECHAT_RETRYABLE_ERRCODES:
                return False
        except (requests.ConnectionError, requests.Timeout) as e:
            print(f"⚠️ 企业微信推送失败: {e}")
        except requests.RequestException as e:
            print(f"❌ 企业微信推送失败且不可重试: {e}")
            return False
        if attempt < max_retries - 1:
            time.sleep(attempt + 1)
    return False


def convert_to_wework_markdown(md_text):
    """将标准 Markdown 转为企业微信兼容格式，优化长度控制"""
    lines = md_text.split("\n")
    out = []
    for line in lines:
        stripped = line.strip()
        if not stripped:
            out.append("")
            continue
        # 旧版引用脚注仍去掉；「今日来源」里的可点链接要保留
        if stripped.startswith(">") and ("下次推送" in stripped or (
                "数据来源" in stripped and "](" not in stripped)):
            continue
        if stripped == "---":
            out.append("---")
            continue
        if stripped.startswith("### "):
            out.append(f"**{stripped[4:]}**")
        elif stripped.startswith("## "):
            out.append(f"> **{stripped[3:]}**")
        elif stripped.startswith("# "):
            out.append(f"> **{stripped[2:]}**")
        elif stripped.startswith("|") and stripped.endswith("|"):
            continue
        else:
            out.append(stripped)
    result = "\n".join(out)
    
    # 企业微信限制约 4000 字节，预留安全余量
    MAX_BYTES = 3800
    encoded = result.encode("utf-8")
    
    if len(encoded) > MAX_BYTES:
        # 智能截断：尝试在段落边界截断
        current_bytes = 0
        truncated_lines = []
        for line in out:
            line_bytes = len(line.encode("utf-8")) + 1  # +1 for newline
            if current_bytes + line_bytes > MAX_BYTES - 100:  # 预留结尾空间
                break
            truncated_lines.append(line)
            current_bytes += line_bytes
        
        result = "\n".join(truncated_lines) + "\n\n> ...(剩余内容请查看完整报告)"
    
    return result


# ─── 资讯抓取 ───────────────────────────────────────────────────

RSS_FEEDS = [
    ("机器之心", "https://www.jiqizhixin.com/rss"),
    ("量子位", "https://www.qbitai.com/feed"),
    ("Hacker News", "https://hnrss.org/frontpage"),
    ("VentureBeat AI", "https://venturebeat.com/category/ai/feed/"),
    ("MIT Tech Review", "https://www.technologyreview.com/feed/"),
    ("TechCrunch", "https://techcrunch.com/feed/"),
]

AI_KEYWORDS = [
    "ai", "artificial intelligence", "machine learning", "deep learning",
    "llm", "gpt", "gemini", "claude", "openai", "anthropic",
    "大模型", "人工智能", "深度学习", "langchain", "dify", "rag",
    "agent", "智能体", "embedding", "vector", "chatgpt", "deepseek",
    "copilot", "神经网络", "transformer", "diffusion", "stable diffusion",
    "mistral", "qwen", "llama", "ollama", "vector database",
    "kimi", "moonshot", "月之暗面", "通义", "千问", "智谱", "glm",
    "豆包", "doubao", "minimax", "阶跃星辰", "stepfun", "混元", "hunyuan",
    "文心", "ernie", "讯飞星火", "百炼", "千帆", "开源模型", "多模态"
]

DOMESTIC_AI_KEYWORDS = [
    "kimi", "moonshot", "月之暗面", "deepseek", "qwen", "通义", "千问",
    "智谱", "glm", "豆包", "doubao", "minimax", "阶跃星辰", "stepfun",
    "混元", "hunyuan", "文心", "ernie", "讯飞星火", "百炼", "千帆"
]

HIGH_VALUE_KEYWORDS = [
    "发布", "推出", "开源", "升级", "模型", "api", "agent", "rag", "多模态",
    "推理", "上下文", "编程", "代码", "框架", "benchmark", "release", "launch",
    "open source", "reasoning", "coding", "developer", "framework"
]

SOURCE_WEIGHTS = {
    "机器之心": 5,
    "量子位": 5,
    "VentureBeat AI": 3,
    "MIT Tech Review": 3,
    "TechCrunch": 2,
    "Hacker News": 2,
}


RSS_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept": "application/rss+xml, application/atom+xml, application/xml, text/xml, */*",
}


def fetch_feed(feed_url, timeout=10):
    """抓取单个公开 RSS Feed"""
    try:
        req = Request(feed_url, headers=RSS_HEADERS)
        with urlopen(req, timeout=timeout) as resp:
            charset = resp.headers.get_content_charset() or "utf-8"
            return resp.read().decode(charset, errors="replace")
    except (URLError, HTTPError, Exception) as e:
        print(f"  ⚠️ 抓取失败 {feed_url}: {e}")
        return None


def fetch_feeds_parallel(named_urls, timeout=8, max_workers=8):
    jobs = list(named_urls or [])
    if not jobs:
        return []
    results = [None] * len(jobs)
    workers = min(max_workers, len(jobs))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        future_map = {
            pool.submit(fetch_feed, url, timeout): index
            for index, (_name, url) in enumerate(jobs)
        }
        for future in as_completed(future_map):
            index = future_map[future]
            try:
                results[index] = future.result()
            except Exception:
                results[index] = None
    return [(jobs[index][0], jobs[index][1], results[index]) for index in range(len(jobs))]


def parse_entry_datetime(entry):
    for key in ("published_parsed", "updated_parsed"):
        parsed = entry.get(key) if hasattr(entry, "get") else getattr(entry, key, None)
        if parsed:
            try:
                return datetime(*parsed[:6], tzinfo=timezone.utc).astimezone(BEIJING_TZ)
            except (TypeError, ValueError, OverflowError):
                pass
    for key in ("published", "updated"):
        text = entry.get(key) if hasattr(entry, "get") else getattr(entry, key, None)
        parsed = parse_published_text(text)
        if parsed:
            return parsed
    return None


def parse_published_text(text):
    raw = " ".join(str(text or "").split())
    if not raw:
        return None
    try:
        parsed = parsedate_to_datetime(raw)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=BEIJING_TZ)
        return parsed.astimezone(BEIJING_TZ)
    except (TypeError, ValueError, OverflowError):
        pass
    iso = raw.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(iso)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=BEIJING_TZ)
        return parsed.astimezone(BEIJING_TZ)
    except ValueError:
        pass
    for fmt, size in (("%Y-%m-%d %H:%M", 16), ("%Y-%m-%d", 10)):
        try:
            parsed = datetime.strptime(raw[:size], fmt)
            return parsed.replace(tzinfo=BEIJING_TZ)
        except ValueError:
            continue
    return None


def item_published_at(item):
    value = (item or {}).get("published_at")
    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=BEIJING_TZ)
        return value.astimezone(BEIJING_TZ)
    return parse_published_text((item or {}).get("published") or "")


def filter_recent_items(items, now=None, fresh_hours=48, stale_hours=168, min_keep=3):
    """日报优先近 48 小时；不够时放宽到 7 天。超过 7 天的旧闻在有候选时丢掉。"""
    now = now or now_beijing()
    dated_fresh = []
    dated_mid = []
    undated = []
    for item in items or []:
        published = item_published_at(item)
        if published is None:
            undated.append(item)
            continue
        age_hours = (now - published).total_seconds() / 3600
        if age_hours <= fresh_hours:
            dated_fresh.append(item)
        elif age_hours <= stale_hours:
            dated_mid.append(item)
    if len(dated_fresh) >= min_keep:
        return dated_fresh + undated
    kept = dated_fresh + dated_mid + undated
    return kept


def parse_rss_items(xml, source, max_items=20):
    items = []
    feed = feedparser.parse(xml)
    for entry in feed.entries[:max_items]:
        title = (entry.get("title") or "").strip()
        summary = entry.get("summary", "") or entry.get("description", "")
        link = entry.get("link", "")
        published_at = parse_entry_datetime(entry)
        published = published_at.strftime("%Y-%m-%d %H:%M") if published_at else (
            entry.get("published", "")[:16] if entry.get("published") else ""
        )
        summary = re.sub(r"<[^>]+>", "", summary)
        summary = re.sub(r"\s+", " ", summary).strip()[:180]
        if not title:
            continue
        items.append({
            "source": source,
            "title": title,
            "summary": summary,
            "link": link,
            "published": published,
            "published_at": published_at,
            "score": score_news_item(source, title, summary),
        })
    return items


def normalize_title(title):
    title = re.sub(r"[\W_]+", "", title.lower())
    return title[:40]


def canonical_link(link):
    raw = (link or "").strip()
    if not raw:
        return ""
    raw = raw.split("#", 1)[0]
    raw = re.sub(r"/+$", "", raw)
    if "?" in raw:
        base, query = raw.split("?", 1)
        kept = [part for part in query.split("&") if part and not part.lower().startswith("utm_")]
        raw = base + (("?" + "&".join(kept)) if kept else "")
    return raw.lower()


def score_news_item(source, title, summary):
    text = f"{title} {summary}".lower()
    score = SOURCE_WEIGHTS.get(source, 1)
    score += sum(1 for kw in AI_KEYWORDS if kw.lower() in text)
    score += sum(8 for kw in DOMESTIC_AI_KEYWORDS if kw.lower() in text)
    score += sum(3 for kw in HIGH_VALUE_KEYWORDS if kw.lower() in text)
    if source in ("机器之心", "量子位"):
        score += 4
    return score


def is_domestic_item(item):
    text = f"{item['title']} {item['summary']}".lower()
    return item["source"] in ("机器之心", "量子位") or any(kw.lower() in text for kw in DOMESTIC_AI_KEYWORDS)


def dedupe_news_items(items):
    seen_titles = set()
    seen_links = set()
    deduped = []
    for item in sorted(items, key=lambda x: x.get("score", 0), reverse=True):
        title_key = normalize_title(item.get("title") or "")
        link_key = canonical_link(item.get("link") or "")
        if title_key and title_key in seen_titles:
            continue
        if link_key and link_key in seen_links:
            continue
        if not title_key and not link_key:
            continue
        if title_key:
            seen_titles.add(title_key)
        if link_key:
            seen_links.add(link_key)
        deduped.append(item)
    return deduped


def select_news_for_prompt(items, edition):
    deduped = dedupe_news_items(items)
    domestic = [item for item in deduped if is_domestic_item(item)]
    global_items = [item for item in deduped if not is_domestic_item(item)]

    if edition == "morning":
        selected = domestic[:3] + global_items[:3]
    else:
        selected = domestic[:4] + global_items[:3]

    selected_keys = {item["link"] or item["title"] for item in selected}
    for item in deduped:
        key = item["link"] or item["title"]
        if key not in selected_keys:
            selected.append(item)
            selected_keys.add(key)
        if len(selected) >= 8:
            break
    return selected[:8]


def extract_ai_news(max_feeds=None, max_items=60):
    """从 RSS 源中提取 AI 相关新闻"""
    print("📡 正在抓取资讯源...")
    all_items = []
    feeds = RSS_FEEDS if max_feeds is None else RSS_FEEDS[:max_feeds]

    for name, url in feeds:
        xml = fetch_feed(url)
        if not xml:
            continue
        source_count = 0
        try:
            parsed = parse_rss_items(xml, name, max_items=max_items)
            for item in parsed:
                text = f"{item['title']} {item['summary']}".lower()
                if any(kw.lower() in text for kw in AI_KEYWORDS):
                    source_count += 1
                    all_items.append(item)
            print(f"  ✅ {name}: 解析 {len(parsed)} 条，抓取到 {source_count} 条 AI 相关")
        except Exception as e:
            print(f"  ⚠️ 解析失败 {name}: {e}")

    all_items.sort(key=lambda x: x["score"], reverse=True)
    return all_items[:40]


def format_news_for_prompt(items, edition="morning"):
    """把筛选后的新闻压缩成给 LLM 的低 token 输入"""
    today = now_beijing().strftime("%Y-%m-%d")
    selected = select_news_for_prompt(items, edition)
    edition_focus = "昨日夜间 + 今日早晨重点" if edition == "morning" else "全天总结 + 国内热点补充，避免重复早报"
    lines = [
        f"日期：{today}",
        f"版本策略：{edition_focus}",
        f"候选资讯：共 {len(items)} 条，以下为加权去重后的 {len(selected)} 条。",
        ""
    ]
    for i, item in enumerate(selected, 1):
        domestic_mark = "国内热点" if is_domestic_item(item) else "全球动态"
        lines.append(f"[{i}] {domestic_mark}｜{item['source']}｜score={item['score']}")
        lines.append(f"标题：{item['title']}")
        lines.append(f"摘要：{item['summary']}")
        if item.get("published"):
            lines.append(f"时间：{item['published']}")
        if item.get("link"):
            lines.append(f"链接：{item['link']}")
        lines.append("")
    return "\n".join(lines)


# ─── LLM API 调用（支持多模型降级策略）────────────────────────────────────

# 模型配置列表，按优先级排列
# 支持：DeepSeek、OpenAI、Azure OpenAI 等兼容 OpenAI 协议的模型
LLM_MODELS = [
    {
        "name": os.environ.get("DEEPSEEK_MODEL", "deepseek-v4-flash"),
        "base_url": "https://api.deepseek.com/",
        "api_key_env": "DEEPSEEK_API_KEY",
        "description": "DeepSeek V4 Flash - 日报模型"
    },
    # 可选：配置 GPT-3.5 作为降级备选（取消注释并配置 OPENAI_API_KEY 即可启用）
    # {
    #     "name": "gpt-3.5-turbo",
    #     "base_url": "https://api.openai.com/v1",
    #     "api_key_env": "OPENAI_API_KEY",
    #     "description": "GPT-3.5 - 备用模型"
    # },
]


class InvalidLLMResponseError(RuntimeError):
    pass


def extract_llm_content(response, description):
    choices = getattr(response, "choices", None)
    choice_count = len(choices) if choices is not None else 0
    if not choices:
        raise InvalidLLMResponseError(f"{description} 响应不包含 choices")
    choice = choices[0]
    message = getattr(choice, "message", None)
    raw_content = getattr(message, "content", None) if message is not None else None
    finish_reason = getattr(choice, "finish_reason", None)
    raw_length = len(raw_content) if isinstance(raw_content, str) else 0
    if not isinstance(raw_content, str):
        raise InvalidLLMResponseError(
            f"{description} 正文类型无效: choices={choice_count}, finish_reason={finish_reason}"
        )
    content = raw_content.strip()
    if content.startswith("```"):
        content = re.sub(r"^```(?:markdown)?\s*", "", content, flags=re.IGNORECASE)
        content = re.sub(r"\s*```\s*$", "", content)
        content = content.strip()
    print(
        f"  响应诊断: choices={choice_count}, finish_reason={finish_reason}, "
        f"raw_chars={raw_length}, content_chars={len(content)}"
    )
    if not content:
        raise InvalidLLMResponseError(f"{description} 返回空正文")
    return content


def has_substantive_report_content(content):
    for line in content.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(">") or re.fullmatch(r"[-*_—=\s]+", stripped):
            continue
        if re.fullmatch(r"#{1,6}\s+.+", stripped):
            continue
        if re.search(r"[一-鿿A-Za-z0-9]", stripped):
            return True
    return False


def news_link(item):
    link = str((item or {}).get("link") or "").strip()
    if link.startswith("https://") or link.startswith("http://"):
        return link
    return ""


def news_title(item):
    title = re.sub(r"\s+", " ", str((item or {}).get("title") or "").strip())
    return title.replace("[", "［").replace("]", "］") or "未命名"


def source_items(items, limit=6):
    seen = set()
    sources = []
    for item in items or []:
        link = news_link(item)
        if not link:
            continue
        key = canonical_link(link)
        if key in seen:
            continue
        seen.add(key)
        sources.append({
            "title": news_title(item),
            "link": link,
            "source": str((item or {}).get("source") or "").strip(),
        })
        if len(sources) >= limit:
            break
    return sources


def render_source_section(items, limit=6):
    sources = source_items(items, limit)
    if not sources:
        return ""
    lines = ["### 今日来源", ""]
    for index, item in enumerate(sources, 1):
        label = f"{item['source']} · {item['title']}" if item["source"] else item["title"]
        lines.append(f"{index}. [{label}]({item['link']})")
    return "\n".join(lines) + "\n"


def strip_generated_source_footer(content):
    text = content or ""
    last = None
    for found in re.finditer(r"(?im)^#{1,6}\s*(今日来源|数据来源)\s*$", text):
        last = found
    if last:
        text = text[:last.start()].rstrip()
    lines = text.splitlines()
    while lines:
        stripped = lines[-1].strip()
        if not stripped:
            lines.pop()
            continue
        if stripped.startswith(">") and "数据来源" in stripped and "](" not in stripped:
            lines.pop()
            continue
        break
    return "\n".join(lines).rstrip()


def attach_sources(content, items, limit=6):
    body = strip_generated_source_footer(content)
    section = render_source_section(items, limit)
    if not section:
        return body + ("\n" if body and not body.endswith("\n") else "")
    if body:
        return body + "\n\n" + section
    return section


def call_llm_with_retry(prompt, max_retries=3):
    """调用 LLM API，并在空响应或可恢复错误时重试。"""
    from openai import OpenAI

    last_error = None
    for model_index, model_config in enumerate(LLM_MODELS):
        model_name = model_config["name"]
        base_url = model_config["base_url"]
        api_key_env = model_config["api_key_env"]
        description = model_config.get("description", model_name)
        api_key = os.environ.get(api_key_env, "")
        if not api_key:
            print(f"⚠️ 未配置 {api_key_env}，跳过 {description}")
            continue

        client = OpenAI(api_key=api_key, base_url=base_url)
        retries = max_retries if model_index == 0 else max(1, max_retries - 1)
        for attempt in range(retries + 1):
            try:
                print(f"🤖 正在调用 {description} (尝试 {attempt + 1}/{retries + 1})...")
                response = client.chat.completions.create(
                    model=model_name,
                    messages=[{"role": "user", "content": prompt}],
                    temperature=0.3,
                    max_tokens=2000,
                    extra_body={"thinking": {"type": "disabled"}}
                )
                content = extract_llm_content(response, description)
                print(f"✅ 成功使用模型: {description}")
                return content
            except Exception as e:
                last_error = e
                error_str = str(e).lower()
                retryable = isinstance(e, InvalidLLMResponseError) or any(marker in error_str for marker in (
                    "408", "429", "500", "502", "503", "504", "rate limit",
                    "unavailable", "timeout", "connection"
                ))
                if retryable and attempt < retries:
                    wait_time = (attempt + 1) * 3
                    print(f"⚠️ {description} 响应无效或服务繁忙，等待 {wait_time} 秒后重试: {e}")
                    time.sleep(wait_time)
                    continue
                print(f"❌ {description} 本轮失败: {e}")
                break
        if model_index < len(LLM_MODELS) - 1:
            next_model = LLM_MODELS[model_index + 1].get("description", "备用模型")
            print(f"⚠️ 降级到 {next_model}...")

    raise RuntimeError(f"所有 LLM 模型均不可用: {last_error or '没有可用模型配置'}")


SYSTEM_PROMPT_MORNING = """你是一位资深 AI 技术架构师与科技媒体主编，为资深 Java/AI 开发者写早间 AI 简报。

【早报策略】
1. 早报偏快讯，只回答：昨日夜间到今天早晨有什么值得开发者知道。
2. 优先选择新发布、新开源、API/模型能力变化、开发框架变化，不做全天复盘式总结。
3. 国内大模型动态必须优先考虑：Kimi/月之暗面、DeepSeek、通义/Qwen、智谱 GLM、豆包、MiniMax、阶跃星辰、混元、文心、讯飞星火等。
4. 国外动态只保留对开发者影响明显的模型、API、开源、框架、Agent/RAG/工具链变化。
5. 拒绝水文、公关稿、股价新闻、泛泛预测。

【输出格式】
生成 4 条最核心资讯，尽量包含“国内大模型 / 国外模型 / 工程落地工具”三个方向。每条格式：

## N. 标题（一句话概括核心事件）

**热度评级：** 🔥 现象级 / ⭐ 值得关注

**核心摘要：** 70-90字，说明发生了什么、关键能力或技术变化。

**对开发者的价值：** 40-70字，说明对应用开发、架构选型或效率工具的具体影响。

不要编造链接，也不要写数据来源行。原文地址由系统按候选资讯附加。总字数控制在 800 字以内，只输出最终简报。"""


SYSTEM_PROMPT_EVENING = """你是一位资深 AI 技术架构师与科技媒体主编，为资深 Java/AI 开发者写晚间 AI 总结。

【晚报策略】
1. 晚报偏复盘，只回答：今天哪些真正重要，哪些对开发者有影响。
2. 晚报不是早报重写；不要重复早报式标题，除非候选中出现新的进展、补充信息或更明确的开发者影响。
3. 如果候选中有国内大模型热点，优先选择早报未必覆盖充分的国内动态，如 Kimi/月之暗面、DeepSeek、通义/Qwen、智谱 GLM、豆包、MiniMax、阶跃星辰、混元、文心、讯飞星火。
4. 对和早报可能重复的通用国外资讯，要么换成工程影响/生态影响角度，要么降级不选。
5. 重点保留当天新发布、开源、API 升级、模型能力变化、Agent/RAG/开发框架变化。
6. 拒绝水文、公关稿、股价新闻、泛泛预测。

【输出格式】
生成 4 条最核心资讯，优先形成“国内热点补充 + 全天工程价值总结”。每条格式：

## N. 标题（一句话概括核心事件）

**热度评级：** 🔥 现象级 / ⭐ 值得关注

**核心摘要：** 70-90字，说明发生了什么、关键能力或技术变化。

**对开发者的价值：** 40-70字，说明对应用开发、架构选型或效率工具的具体影响。

不要编造链接，也不要写数据来源行。原文地址由系统按候选资讯附加。总字数控制在 800 字以内，只输出最终简报。"""


SYSTEM_PROMPT = SYSTEM_PROMPT_MORNING  # 默认值，后续会根据 edition 动态选择


def build_prompt(news_text, edition="morning"):
    """构建发给 LLM 的完整 prompt"""
    system_prompt = SYSTEM_PROMPT_EVENING if edition == "evening" else SYSTEM_PROMPT_MORNING
    edition_hint = "今日晚间总结" if edition == "evening" else "今日早间简报"
    return f"""{system_prompt}

---

{news_text}

请根据以上候选资讯，生成{edition_hint}。优先使用候选中的高分和国内热点，但不要机械照搬候选顺序。"""


TOPIC_KEYWORDS = {
    "AI大模型": ["人工智能", "大模型", "llm", "gpt", "claude", "gemini", "deepseek", "qwen", "通义", "智谱"],
    "Web开发": ["web", "前端", "后端", "浏览器", "javascript", "typescript", "react", "vue", "spring", "api"],
    "移动端": ["移动端", "android", "ios", "flutter", "react native"],
    "云原生": ["云原生", "kubernetes", "k8s", "容器", "docker", "serverless"],
    "数据库": ["数据库", "mysql", "postgresql", "redis", "向量数据库"],
    "安全": ["安全", "漏洞", "攻击", "隐私", "鉴权", "供应链安全"],
    "DevOps": ["devops", "ci/cd", "github actions", "部署", "可观测性"],
    "数据分析": ["数据分析", "数据工程", "bi", "分析平台"],
    "机器学习": ["机器学习", "深度学习", "训练", "推理", "mlops"],
    "区块链": ["区块链", "web3", "智能合约", "加密货币"],
}

# 自定义主题的公开别名，只覆盖常见人名/公司，避免把任意词扩得太宽。
TOPIC_ALIAS_GROUPS = [
    ["黄仁勋", "jensen huang", "nvidia", "英伟达", "nvda"],
    ["马斯克", "elon musk", "musk", "特斯拉", "tesla", "spacex", "xai"],
    ["openai", "chatgpt", "山姆·奥特曼", "sam altman"],
    ["deepseek", "深度求索"],
    ["anthropic", "claude"],
    ["谷歌", "google", "gemini", "deepmind"],
    ["微软", "microsoft", "msft"],
    ["苹果", "apple", "aapl"],
    ["meta", "llama", "扎克伯格", "zuckerberg"],
]

TOPIC_FRESH_HOURS = 48
TOPIC_STALE_HOURS = 168


def normalize_intent(intent):
    text = " ".join(str(intent or "").split())
    return text[:120]


INTENT_NOISE = {
    "只要", "不要", "关注", "例如", "最近", "最火", "最火的", "热点", "相关",
    "资讯", "新闻", "内容", "方面", "一点", "一些", "一下", "看看", "就行",
    "就好", "即可", "我想看", "的",
}

# 「我想看」里的角度词，用来优先检索，不是硬门槛。
INTENT_FOCUS_ALIASES = {
    "演讲": ["speech", "keynote", "talk", "interview", "采访", "发言"],
    "发言": ["speech", "remarks", "采访"],
    "采访": ["interview"],
    "发布": ["launch", "release", "unveil", "发布会", "announces"],
    "发布会": ["launch", "keynote", "event"],
    "芯片": ["chip", "gpu", "semiconductor"],
    "航天": ["space", "spacex", "rocket"],
}


def intent_terms(intent):
    text = normalize_intent(intent)
    if not text:
        return []
    cleaned = re.sub(r"^(只要|不要|关注|例如)[:：]?", "", text).strip() or text
    parts = [part.strip() for part in re.split(r"[，,；;、/|]+|或者|和|与|及|或", cleaned) if part.strip()]
    skip = {"只要", "不要", "关注", "例如"}
    terms = []
    for part in parts:
        if part in skip or part.startswith("不要"):
            continue
        leftover = part
        for noise in sorted(INTENT_NOISE, key=len, reverse=True):
            leftover = leftover.replace(noise, " ")
        leftover = " ".join(leftover.split()).strip()
        if leftover and leftover not in skip and leftover not in INTENT_NOISE:
            terms.append(leftover)
    return terms


def alias_terms_for(term):
    key = " ".join(str(term or "").split()).lower()
    if not key:
        return []
    matched = []
    seen = set()
    for group in TOPIC_ALIAS_GROUPS:
        if any((alias or "").strip().lower() == key for alias in group):
            for alias in group:
                normalized = " ".join(str(alias or "").split())
                alias_key = normalized.lower()
                if not normalized or alias_key in seen:
                    continue
                seen.add(alias_key)
                matched.append(normalized)
    return matched


def expand_topic_terms(topic, intent=None):
    terms = []
    seen = set()

    def add(term):
        normalized = " ".join(str(term or "").split())
        key = normalized.lower()
        if not normalized or key in seen:
            return
        seen.add(key)
        terms.append(normalized)

    add(topic)
    for alias in alias_terms_for(topic):
        add(alias)
    for term in intent_terms(intent):
        add(term)
        for alias in alias_terms_for(term):
            add(alias)
    return terms


def topic_keywords(topic, intent=None):
    if is_preset_topic(topic):
        keys = list(TOPIC_KEYWORDS.get(topic, [topic]))
        for term in intent_terms(intent):
            if term not in keys:
                keys.append(term)
        return keys
    return expand_topic_terms(topic, intent)


def intent_focus_terms(topic, intent=None):
    topic_name = " ".join(str(topic or "").split())
    focuses = []
    seen = set()

    def add(term):
        normalized = " ".join(str(term or "").split())
        key = normalized.lower()
        if not normalized or len(normalized) > 20 or key in seen or key == topic_name.lower():
            return
        seen.add(key)
        focuses.append(normalized)

    for term in intent_terms(intent):
        trimmed = term
        if topic_name and trimmed.lower().startswith(topic_name.lower()):
            trimmed = trimmed[len(topic_name):].strip()
        add(trimmed)
        for alias in INTENT_FOCUS_ALIASES.get((trimmed or "").lower(), []):
            add(alias)
    return focuses


def news_matches_intent(item, topic, intent=None):
    text = f"{item.get('title', '')} {item.get('summary', '')}".lower()
    return any(term.lower() in text for term in intent_focus_terms(topic, intent))


def topic_search_queries(topic, intent=None):
    topic_name = " ".join(str(topic or "").split())
    queries = []
    seen = set()

    def add(query):
        normalized = " ".join(str(query or "").split())
        key = normalized.lower()
        if not normalized or key in seen:
            return
        seen.add(key)
        queries.append(normalized)

    add(topic_name)
    latin = next((term for term in expand_topic_terms(topic_name) if re.search(r"[A-Za-z]", term)), "")
    add(latin)
    focuses = intent_focus_terms(topic_name, intent)
    if topic_name and focuses:
        add(f"{topic_name} {focuses[0]}")
    elif focuses:
        add(focuses[0])
    return queries[:3]


def topic_match_score(item, topic, intent=None):
    text = f"{item.get('title', '')} {item.get('summary', '')}".lower()
    return sum(1 for keyword in topic_keywords(topic, intent) if keyword.lower() in text)


def select_news_for_topic(items, topic, limit=8, intent=None):
    scored = []
    for item in items:
        hits = topic_match_score(item, topic, intent)
        if hits > 0:
            scored.append((hits, item.get("score", 0), item))
    scored.sort(key=lambda row: (-row[0], -row[1]))
    return [item for _, _, item in scored[:limit]]


def is_preset_topic(topic):
    if not topic:
        return False
    key = topic.strip().lower()
    return any(name.lower() == key for name in TOPIC_KEYWORDS)


TOPIC_SCAN_FEEDS = [
    ("The Verge", "https://www.theverge.com/rss/index.xml"),
    ("Electrek", "https://electrek.co/feed/"),
    ("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
    ("BBC科技", "https://feeds.bbci.co.uk/news/technology/rss.xml"),
    ("Space.com", "https://www.space.com/feeds/all"),
    ("Engadget", "https://www.engadget.com/rss.xml"),
    ("IT之家", "https://www.ithome.com/rss/"),
    ("少数派", "https://sspai.com/feed"),
    ("Solidot", "https://www.solidot.org/index.rss"),
]


def topic_search_urls(topic):
    raw = " ".join((topic or "").split())
    query = quote(raw)
    timed = quote(f"{raw} when:7d")
    return [
        ("Google中文", f"https://news.google.com/rss/search?q={timed}&hl=zh-CN&gl=CN&ceid=CN:zh-Hans"),
        ("Google英文", f"https://news.google.com/rss/search?q={timed}&hl=en-US&gl=US&ceid=US:en"),
        ("Bing新闻", f"https://www.bing.com/news/search?q={query}&format=rss"),
        ("Hacker News", f"https://hnrss.org/newest?q={query}&count=20"),
        ("Reddit", f"https://www.reddit.com/search.rss?q={query}&sort=new&t=week"),
    ]


def topic_scan_feeds():
    seen = set()
    feeds = []
    for name, url in list(RSS_FEEDS) + list(TOPIC_SCAN_FEEDS):
        if url in seen:
            continue
        seen.add(url)
        feeds.append((name, url))
    return feeds


def news_mentions_topic(item, topic, intent=None):
    text = f"{item.get('title', '')} {item.get('summary', '')}".lower()
    token = (topic or "").strip().lower()
    if token and token in text:
        return True
    return topic_match_score(item, topic, intent) >= 1


def fetch_topic_search_news(topic, limit=8, intent=None):
    """按主题词和别名检索公开 RSS，并扫描科技媒体源，只留近两天相关条目。"""
    extra = normalize_intent(intent)
    jobs = []
    for query in topic_search_queries(topic, extra):
        for source, url in topic_search_urls(query):
            jobs.append((source, url, query))
    for source, url in topic_scan_feeds():
        jobs.append((source, url, ""))
    fetched = fetch_feeds_parallel([(name, url) for name, url, _query in jobs], timeout=10)
    collected = []
    for (source, _url, query), (_name, _fetched_url, xml) in zip(jobs, fetched):
        if not xml:
            continue
        try:
            for item in parse_rss_items(xml, source, max_items=12):
                if query:
                    item["query"] = query
                collected.append(item)
        except Exception as e:
            print(f"  ⚠️ 主题检索解析失败 {source}: {e}")
    recent = filter_recent_items(collected)
    filtered = [
        item for item in dedupe_news_items(recent)
        if news_mentions_topic(item, topic, extra)
    ]
    return filtered[:limit]


def collect_topic_candidates(news_items, topic, intent=None):
    """先按「我想看」收窄，对不上再退回主题相近资讯。只有主题本身没稿才为空。"""
    extra = normalize_intent(intent)
    focuses = intent_focus_terms(topic, extra)
    selected = select_news_for_topic(news_items, topic, intent=extra or None)
    should_search = (not is_preset_topic(topic)) or bool(extra)
    if not should_search:
        return selected, "topic" if selected else "none"
    label = f"{topic}" + (f"（{extra}）" if extra else "")
    if not selected:
        print(f"  🔍 主题「{label}」在公共资讯池无匹配，改为按词检索")
    else:
        print(f"  🔍 主题「{label}」合并公开检索，补近两天资讯")
    searched = fetch_topic_search_news(topic, intent=extra) if extra else fetch_topic_search_news(topic)
    merged = filter_recent_items(dedupe_news_items(list(selected) + list(searched or [])))
    merged = [item for item in merged if news_mentions_topic(item, topic, extra)]
    on_topic = [item for item in merged if news_mentions_topic(item, topic)]
    if focuses:
        focused = [item for item in merged if news_matches_intent(item, topic, extra)]
        focused_on_topic = [item for item in focused if news_mentions_topic(item, topic)]
        if focused_on_topic:
            print(f"  🎯 主题「{topic}」按想法优先，命中 {len(focused_on_topic)} 条")
            return focused_on_topic[:8], "intent"
        if focused and not alias_terms_for(topic) and not is_preset_topic(topic):
            print(f"  🎯 主题「{topic}」按想法检索，命中 {len(focused)} 条")
            return focused[:8], "intent"
        if on_topic:
            print(f"  🔄 主题「{topic}」未命中想法，回退主题相近资讯 {len(on_topic)} 条")
            return on_topic[:8], "topic"
        if focused:
            return focused[:8], "intent"
    if on_topic:
        return on_topic[:8], "topic"
    if merged:
        return merged[:8], "topic"
    return selected, "topic" if selected else "none"


def collect_news_for_topic(news_items, topic, intent=None):
    items, _match = collect_topic_candidates(news_items, topic, intent)
    return items


def window_digest_style(window):
    if window in ("evening", "w12_18", "w18_24"):
        return "evening"
    return "morning"


def format_topic_news_for_prompt(items, topic, edition="morning", intent=None, match=None):
    today = now_beijing().strftime("%Y-%m-%d")
    style = window_digest_style(edition)
    edition_focus = "昨日夜间 + 今日早晨" if style == "morning" else "今日全天"
    extra = normalize_intent(intent)
    if extra and match == "topic":
        match_line = "匹配方式：未找到该想法的直接资讯，下列是主题相近候选"
    elif extra:
        match_line = "匹配方式：已按用户想法筛到相关候选"
    else:
        match_line = "匹配方式：按主题默认"
    lines = [
        f"日期：{today}",
        f"主题：{topic}",
        f"用户想法：{extra or '未填写，按主题默认范围'}",
        match_line,
        f"版本：{edition_focus}",
        f"候选资讯：{len(items)} 条",
        "",
    ]
    for i, item in enumerate(items, 1):
        lines.append(f"[{i}] {item.get('source', '')}")
        lines.append(f"标题：{item.get('title', '')}")
        lines.append(f"摘要：{item.get('summary', '')}")
        if item.get("link"):
            lines.append(f"链接：{item['link']}")
        lines.append("")
    return "\n".join(lines)


def build_topic_prompt(news_text, topic, edition="morning", intent=None, match=None):
    edition_hint = "晚间" if window_digest_style(edition) == "evening" else "早间"
    extra = normalize_intent(intent)
    if extra and match == "topic":
        intent_rule = (
            f"4. 用户希望重点看：{extra}。今天候选里没有足够贴这个角度的资讯，"
            f"请用现有与「{topic}」相关的候选写一版最接近的简报。"
            "必须基于候选事实，不要编造用户提到但候选没有的演讲、发布或其他事件。"
            "开头可用一句说明今天没找到该角度的直接资讯。"
        )
    elif extra:
        intent_rule = (
            f"4. 用户希望重点看：{extra}。优先写贴近这个角度的内容，不必每条都措辞相同；"
            "明确排除用户不想看的方面。不要编造候选里没有的事件。"
        )
    else:
        intent_rule = "4. 用户没有额外想法，按该主题的默认范围来写。"
    return f"""你是资深科技编辑，只写与「{topic}」相关的{edition_hint}简报段落。

【要求】
1. 只覆盖与该主题直接相关的资讯，不要写成全站综合简报。
2. 拒绝水文、公关稿、股价新闻。
3. 若候选资讯不够相关，宁可少写，不要硬凑。
{intent_rule}

【输出格式】
## {topic}

**要点：** 70-90字，说明今天这个主题发生了什么。

**影响：** 40-70字，说明对开发者或从业者的具体影响。

不要编造链接或来源行。原文地址由系统按候选资讯附加。只输出这一段，总字数控制在 280 字以内。

---

{news_text}
"""


def generate_due_topic_sections(news_items, report_date, run_id, due=None):
    """按四个时间段的最早到期时刻生成主题段。"""
    if due is None:
        due = fetch_due_generations(report_date)
    if not due:
        print("🧩 当前没有到期的订阅主题，跳过主题段生成")
        return 0
    grouped = {}
    for item in due:
        grouped.setdefault(item["window"], []).append(item)
    saved = 0
    for window, jobs in grouped.items():
        print(f"🧩 {window} 将为 {len(jobs)} 个到期主题生成段落")
        saved += generate_topic_sections(news_items, window, jobs, report_date, run_id)
    return saved


def generation_concurrency():
    raw = os.environ.get("GENERATION_CONCURRENCY", "4")
    try:
        return max(1, min(8, int(raw)))
    except ValueError:
        return 4


def is_digest_topic(topic, intent=None):
    if normalize_intent(intent):
        return False
    name = (topic or "").strip().lower().replace(" ", "")
    return name in {"ai科技", "科技", "纳指标普沪深300etf", "etf", "市场观察"}


def is_ai_digest_topic(topic):
    name = (topic or "").strip().lower().replace(" ", "")
    return name in {"ai科技", "科技"}


def is_etf_digest_topic(topic):
    name = (topic or "").strip().lower().replace(" ", "")
    return name in {"纳指标普沪深300etf", "etf", "市场观察"}


def generate_public_ai_digest(news_items, edition, report_date, run_id):
    """用原来的早晚报 prompt 生成全站科技日报并入库。"""
    if not news_items:
        print("  skip AI digest: no news")
        return False
    edition_suffix = "早间版" if edition == "morning" else "晚间版"
    selected = select_news_for_prompt(news_items, edition)
    news_text = format_news_for_prompt(news_items, edition)
    prompt = build_prompt(news_text, edition)
    try:
        body = call_llm_with_retry(prompt)
    except Exception as error:
        print(f"  AI digest LLM failed: {error}")
        return False
    if not has_substantive_report_content(body):
        print("  skip AI digest: empty body")
        return False
    header = f"# 🤖 AI 每日高价值简报 · {report_date}（{edition_suffix}）\n\n---\n\n"
    full_report = header + attach_sources(body, selected)
    title = f"【{edition_suffix}】AI 每日简报 {report_date}"
    summary = body[:100] + "..." if len(body) > 100 else body
    return push_to_backend(edition, report_date, title, full_report, summary, run_id)


def generate_due_digest_reports(due, news_items, report_date, run_id):
    """到期的 AI科技 / ETF 走原文日报，不写短段落。"""
    saved = 0
    seen = set()
    for item in due or []:
        topic = item.get("topic")
        window = item.get("window")
        if normalize_intent(item.get("intent")):
            continue
        if is_ai_digest_topic(topic):
            edition = window_digest_style(window)
            key = f"ai|{edition}"
            if key in seen:
                continue
            seen.add(key)
            print(f"  generate AI digest {edition} for {topic}")
            if generate_public_ai_digest(news_items, edition, report_date, run_id):
                report_generation_status(window, report_date, topic, "ready", "已按早晚报原文生成", run_id)
                saved += 1
            else:
                report_generation_status(window, report_date, topic, "failed", "科技日报原文生成失败", run_id)
        elif is_etf_digest_topic(topic):
            key = "etf"
            if key in seen:
                continue
            seen.add(key)
            print(f"  generate ETF digest for {topic}")
            try:
                from etf_report import generate_and_ingest, now_beijing, should_skip_weekend_report
                if should_skip_weekend_report(now_beijing(), False):
                    print("  skip ETF digest: weekend")
                    report_generation_status(window, report_date, topic, "skipped_no_news", "周末休市，今日不生成 ETF 日报", run_id)
                    continue
                ok = generate_and_ingest()
            except Exception as error:
                print(f"  ETF digest failed: {error}")
                ok = False
            if ok:
                report_generation_status(window, report_date, topic, "ready", "已按 ETF 原文生成", run_id)
                saved += 1
            else:
                report_generation_status(window, report_date, topic, "failed", "ETF 日报原文生成失败", run_id)
    return saved


def generate_one_topic_section(news_items, edition, topic, report_date, run_id, intent=None):
    """生成并入库单个主题。失败返回 False，不影响其他主题。"""
    extra = normalize_intent(intent)
    if is_digest_topic(topic, extra):
        print("  skip digest topic: use public morning/evening/etf report")
        return False
    selected, match = collect_topic_candidates(news_items, topic, extra)
    if not selected:
        print(f"  ⏭️ 主题「{topic}」没有匹配资讯，跳过生成")
        report_generation_status(edition, report_date, topic, "skipped_no_news", "今天没有抓到与该主题直接相关的资讯", run_id)
        return False
    prompt = build_topic_prompt(
        format_topic_news_for_prompt(selected, topic, edition, extra, match),
        topic,
        edition,
        extra,
        match,
    )
    try:
        content = call_llm_with_retry(prompt)
    except Exception as e:
        print(f"  ⚠️ 主题「{topic}」生成失败，跳过: {e}")
        report_generation_status(edition, report_date, topic, "failed", "模型生成失败，稍后重试", run_id)
        return False
    if not has_substantive_report_content(content):
        print(f"  ⏭️ 主题「{topic}」没有实质正文，跳过")
        report_generation_status(edition, report_date, topic, "failed", "模型没有写出实质正文", run_id)
        return False
    if not content.lstrip().startswith("##"):
        content = f"## {topic}\n\n{content.strip()}"
    if match == "topic" and extra:
        content = content.rstrip() + f"\n\n> 今天没有找到更贴「{extra}」的直接资讯，已按「{topic}」相近内容整理。\n"
    content = attach_sources(content, selected)
    title = f"{topic} · {report_date}"
    summary = content[:100] + "..." if len(content) > 100 else content
    if push_topic_section(edition, report_date, topic, title, content, summary, run_id):
        return True
    report_generation_status(edition, report_date, topic, "failed", "内容已生成但入库失败", run_id)
    return False


def generate_topic_sections(news_items, edition, topics, report_date, run_id):
    """只为有人勾选的主题生成段落；多个主题并行，单个失败不影响其余主题。"""
    unique_jobs = []
    seen = set()
    for entry in topics:
        if isinstance(entry, dict):
            topic = str(entry.get("topic") or "").strip()
            intent = normalize_intent(entry.get("intent"))
        else:
            topic = str(entry or "").strip()
            intent = ""
        key = (topic, intent)
        if not topic or key in seen:
            continue
        seen.add(key)
        unique_jobs.append((topic, intent))
    if not unique_jobs:
        return 0
    workers = min(generation_concurrency(), len(unique_jobs))
    if workers == 1:
        return sum(
            1 for topic, intent in unique_jobs
            if generate_one_topic_section(news_items, edition, topic, report_date, run_id, intent)
        )
    saved = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [
            pool.submit(generate_one_topic_section, news_items, edition, topic, report_date, run_id, intent)
            for topic, intent in unique_jobs
        ]
        for future in as_completed(futures):
            try:
                if future.result():
                    saved += 1
            except Exception as e:
                print(f"  ⚠️ 主题生成线程异常，跳过: {e}")
    return saved


def detect_edition():
    """
    自动检测是早间版还是晚间版
    也支持通过 EDITION 环境变量手动指定
    """
    manual = os.environ.get("EDITION", "auto").lower()
    if manual in ("morning", "evening"):
        return manual

    # 使用明确的北京时间
    now = now_beijing()
    hour = now.hour
    # 北京时间 0-12 点之间执行为早间版，12-24 点为晚间版
    if hour < 12:
        return "morning"
    else:
        return "evening"


# ─── 主流程 ─────────────────────────────────────────────────────

def main():
    today = now_beijing().strftime("%Y-%m-%d")
    mode = os.environ.get("MODE", "full").lower()
    edition = detect_edition()
    edition_name = "轮询到期主题" if mode == "poll" else ("早间版" if edition == "morning" else "晚间版")

    print(f"\n{'='*50}")
    print(f"🤖 AI 每日简报 · {today}（{edition_name}）")
    print(f"{'='*50}\n")

    # 报告文件名区分早晚报
    edition_suffix = "早间版" if edition == "morning" else "晚间版"
    report_file = f"AI日报_{today}（{edition_suffix}）.md"

    webhook_url = os.environ.get("WECHAT_WEBHOOK", "")
    backend_configured = bool(os.environ.get("BACKEND_API_URL") and os.environ.get("REPORT_INGEST_TOKEN"))

    # 检查是否有任意一个模型的 API Key 配置
    has_api_key = any(os.environ.get(m["api_key_env"]) for m in LLM_MODELS)
    if not has_api_key:
        print("❌ 缺少 API Key 环境变量，请配置 DEEPSEEK_API_KEY")
        sys.exit(1)
    if mode == "poll":
        post_poller_heartbeat("checking")
        if not backend_configured:
            print("❌ 轮询模式需要配置 BACKEND_API_URL 和 REPORT_INGEST_TOKEN")
            sys.exit(1)
    elif not backend_configured and not webhook_url:
        print("❌ 缺少 WECHAT_WEBHOOK，且未配置后端入库")
        sys.exit(1)

    if mode == "poll":
        due = fetch_due_generations(today)
        if not due:
            print("🧩 当前没有到期的订阅主题，跳过爬取和生成")
        else:
            digest_due = [item for item in due if is_digest_topic(item.get("topic"), item.get("intent"))]
            topic_due = [item for item in due if not is_digest_topic(item.get("topic"), item.get("intent"))]
            run_id = os.environ.get("GITHUB_RUN_ID", "local")
            news_items = []
            if topic_due or any(is_ai_digest_topic(item.get("topic")) for item in digest_due):
                print(f"🧩 检测到 {len(due)} 个到期主题，开始抓取资讯")
                news_items = extract_ai_news()
            if digest_due:
                generate_due_digest_reports(digest_due, news_items, today, run_id)
            if topic_due:
                generate_due_topic_sections(news_items, today, run_id, due=topic_due)
            print(f"\n✅ 到期主题轮询完成！({now_beijing().strftime('%H:%M:%S')})")
        dispatch_due_pushes()
        return

    # Step 1: 抓取资讯
    news_items = extract_ai_news()

    if not news_items:
        print("❌ 未抓取到任何 AI 相关资讯，不生成、不入库、不推送")
        sys.exit(1)

    print(f"\n📊 共抓取到 {len(news_items)} 条 AI 相关资讯\n")

    # Step 2: 用 LLM 生成简报（支持多模型降级）
    news_text = format_news_for_prompt(news_items, edition)
    prompt = build_prompt(news_text, edition)

    try:
        report = call_llm_with_retry(prompt)
    except Exception as e:
        print(f"❌ LLM API 调用失败: {e}")
        sys.exit(1)

    if not has_substantive_report_content(report):
        print("❌ LLM 未生成实质正文，不写文件、不入库、不推送")
        sys.exit(1)

    header = f"# 🤖 AI 每日高价值简报 · {today}（{edition_suffix}）\n\n---\n\n"
    full_report = header + report
    run_id = os.environ.get("GITHUB_RUN_ID", "local")
    title_text = f"【{edition_suffix}】AI 每日简报 {today}"
    summary_text = report[:100] + "..." if len(report) > 100 else report

    if backend_configured:
        generate_due_topic_sections(news_items, today, run_id)
        if not push_to_backend(edition, today, title_text, full_report, summary_text, run_id):
            print("❌ 同步到后端失败，本次日报不继续推送")
            sys.exit(1)
        print("📬 推送由后端订阅渠道负责，跳过脚本直推企业微信")
    else:
        wx_content = convert_to_wework_markdown(full_report)
        if not push_to_wechat(wx_content, webhook_url):
            print("❌ 企业微信推送失败")
            sys.exit(1)

    try:
        with open(report_file, "w", encoding="utf-8") as f:
            f.write(full_report)
        print(f"💾 已保存: {report_file}")
    except OSError as e:
        print(f"⚠️ 报告已入库并推送，但本地文件保存失败: {e}")
    print(f"\n✅ 今日简报完成！({now_beijing().strftime('%H:%M:%S')})")


if __name__ == "__main__":
    main()
