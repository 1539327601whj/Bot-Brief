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
import feedparser
from datetime import datetime, timezone, timedelta
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


def push_to_backend(edition, title, content, summary, run_id):
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
        # 跳过数据来源行
        if stripped.startswith(">") and ("数据来源" in stripped or "下次推送" in stripped):
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


def fetch_feed(feed_url, timeout=10):
    """抓取单个 RSS Feed"""
    try:
        req = Request(feed_url, headers={"User-Agent": "Mozilla/5.0"})
        with urlopen(req, timeout=timeout) as resp:
            charset = resp.headers.get_content_charset() or "utf-8"
            return resp.read().decode(charset, errors="replace")
    except (URLError, HTTPError, Exception) as e:
        print(f"  ⚠️ 抓取失败 {feed_url}: {e}")
        return None


def normalize_title(title):
    title = re.sub(r"[\W_]+", "", title.lower())
    return title[:40]


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
    seen = set()
    deduped = []
    for item in sorted(items, key=lambda x: x["score"], reverse=True):
        key = normalize_title(item["title"])
        if not key or key in seen:
            continue
        seen.add(key)
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
            feed = feedparser.parse(xml)
            for entry in feed.entries[:max_items]:
                title = entry.get("title", "")
                summary = entry.get("summary", "") or entry.get("description", "")
                link = entry.get("link", "")
                published = entry.get("published", "")[:16] if entry.get("published") else ""

                summary = re.sub(r'<[^>]+>', '', summary)
                summary = re.sub(r"\s+", " ", summary).strip()[:180]

                text = (title + " " + summary).lower()
                if any(kw.lower() in text for kw in AI_KEYWORDS):
                    source_count += 1
                    all_items.append({
                        "source": name,
                        "title": title.strip(),
                        "summary": summary,
                        "link": link,
                        "published": published,
                        "score": score_news_item(name, title, summary)
                    })
            print(f"  ✅ {name}: {len(feed.entries)} 条，抓取到 {source_count} 条 AI 相关")
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

最后附上数据来源行。总字数控制在 800 字以内，只输出最终简报。"""


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

最后附上数据来源行。总字数控制在 800 字以内，只输出最终简报。"""


SYSTEM_PROMPT = SYSTEM_PROMPT_MORNING  # 默认值，后续会根据 edition 动态选择


def build_prompt(news_text, edition="morning"):
    """构建发给 LLM 的完整 prompt"""
    system_prompt = SYSTEM_PROMPT_EVENING if edition == "evening" else SYSTEM_PROMPT_MORNING
    edition_hint = "今日晚间总结" if edition == "evening" else "今日早间简报"
    return f"""{system_prompt}

---

{news_text}

请根据以上候选资讯，生成{edition_hint}。优先使用候选中的高分和国内热点，但不要机械照搬候选顺序。"""


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
    edition = detect_edition()
    edition_name = "早间版" if edition == "morning" else "晚间版"

    print(f"\n{'='*50}")
    print(f"🤖 AI 每日简报 · {today}（{edition_name}）")
    print(f"{'='*50}\n")

    # 报告文件名区分早晚报
    edition_suffix = "早间版" if edition == "morning" else "晚间版"
    report_file = f"AI日报_{today}（{edition_suffix}）.md"

    webhook_url = os.environ.get("WECHAT_WEBHOOK", "")

    # 检查是否有任意一个模型的 API Key 配置
    has_api_key = any(os.environ.get(m["api_key_env"]) for m in LLM_MODELS)
    if not has_api_key:
        print("❌ 缺少 API Key 环境变量，请配置 DEEPSEEK_API_KEY")
        sys.exit(1)
    if not webhook_url:
        print("❌ 缺少 WECHAT_WEBHOOK 环境变量")
        sys.exit(1)

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

    if not push_to_backend(edition, title_text, full_report, summary_text, run_id):
        print("❌ 同步到后端失败，本次日报不继续推送")
        sys.exit(1)

    wx_content = convert_to_wework_markdown(full_report)
    if not push_to_wechat(wx_content, webhook_url):
        print("❌ 企业微信推送失败，报告已入库")
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
