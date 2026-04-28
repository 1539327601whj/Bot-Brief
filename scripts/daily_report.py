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

def push_to_backend(edition, title, content, summary, run_id):
    """将简报 POST 到 Spring Boot 后端 API 存储"""
    import requests as req
    backend_url = os.environ.get("BACKEND_API_URL", "")
    if not backend_url:
        print("  ⚠️ 未配置 BACKEND_API_URL，跳过后端存储")
        return False

    # Render 免费层限制请求体约 1MB，截断 content 避免 400 错误
    # 保留约 100KB 安全余量（约 3万字符）
    max_content_length = 30000
    truncated_content = content if len(content) <= max_content_length else content[:max_content_length] + "\n\n> ...(内容已截断，完整版请查看企业微信)"

    payload = {
        "edition": edition,
        "title": title,
        "content": truncated_content,
        "summary": summary,
        "runId": run_id
    }
    try:
        resp = req.post(f"{backend_url}/api/reports", json=payload, timeout=10)
        if resp.status_code == 200:
            print(f"  ✅ 已同步到后端 API")
            return True
        else:
            print(f"  ⚠️ 后端 API 返回 {resp.status_code}: {resp.text}")
            return False
    except Exception as e:
        print(f"  ⚠️ 后端 API 同步失败: {e}")
        return False


# ─── 企业微信推送 ───────────────────────────────────────────────

def push_to_wechat(content, webhook_url):
    """通过企业微信 Webhook 发送 Markdown 消息"""
    import requests
    payload = {
        "msgtype": "markdown",
        "markdown": {"content": content}
    }
    headers = {"Content-Type": "application/json; charset=utf-8"}
    resp = requests.post(webhook_url, json=payload, headers=headers, timeout=15)
    data = resp.json()
    if data.get("errcode") == 0:
        print(f"✅ 推送成功 ({len(content.encode('utf-8'))} bytes)")
        return True
    else:
        print(f"❌ 推送失败: {data}")
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
    ("Hacker News", "https://hnrss.org/frontpage"),
    ("MIT Tech Review", "https://www.technologyreview.com/feed/"),
    ("TechCrunch", "https://techcrunch.com/feed/"),
    ("VentureBeat AI", "https://venturebeat.com/category/ai/feed/"),
    ("机器之心", "https://www.jiqizhixin.com/rss"),
]

AI_KEYWORDS = [
    "ai", "artificial intelligence", "machine learning", "deep learning",
    "llm", "gpt", "gemini", "claude", "openai", "anthropic",
    "大模型", "人工智能", "深度学习", "langchain", "dify", "rag",
    "agent", "智能体", "embedding", "vector", "chatgpt", "deepseek",
    "copilot", "神经网络", "transformer", "diffusion", "stable diffusion",
    "mistral", "qwen", "llama", "ollama", "vector database"
]


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


def extract_ai_news(max_feeds=5, max_items=50):
    """从 RSS 源中提取 AI 相关新闻"""
    print("📡 正在抓取资讯源...")
    all_items = []

    for name, url in RSS_FEEDS[:max_feeds]:
        xml = fetch_feed(url)
        if not xml:
            continue
        try:
            feed = feedparser.parse(xml)
            for entry in feed.entries[:max_items]:
                title = entry.get("title", "")
                summary = entry.get("summary", "") or entry.get("description", "")
                link = entry.get("link", "")
                published = entry.get("published", "")[:16] if entry.get("published") else ""

                # 清理 HTML 标签
                summary = re.sub(r'<[^>]+>', '', summary)[:300]

                # 关键词匹配（不区分大小写）
                text = (title + " " + summary).lower()
                if any(kw.lower() in text for kw in AI_KEYWORDS):
                    all_items.append({
                        "source": name,
                        "title": title.strip(),
                        "summary": summary.strip(),
                        "link": link,
                        "published": published,
                        "score": sum(1 for kw in AI_KEYWORDS if kw.lower() in text)
                    })
            print(f"  ✅ {name}: {len(feed.entries)} 条，抓取到 {sum(1 for i in all_items if i['source']==name)} 条 AI 相关")
        except Exception as e:
            print(f"  ⚠️ 解析失败 {name}: {e}")

    # 按关键词匹配分数排序，取前 20 条
    all_items.sort(key=lambda x: x["score"], reverse=True)
    return all_items[:20]


def format_news_for_prompt(items):
    """把新闻格式化为给 Gemini 的文本"""
    today = now_beijing().strftime("%Y-%m-%d")
    lines = [f"📅 日期：{today}\n", "=" * 40, "\n今日 AI 相关资讯（共抓取到 {} 条，选取最重要的 5 条）：\n".format(len(items))]
    for i, item in enumerate(items[:5], 1):
        lines.append(f"[{i}] 来源：{item['source']}")
        lines.append(f"标题：{item['title']}")
        lines.append(f"摘要：{item['summary'][:200]}")
        lines.append(f"链接：{item['link']}")
        lines.append("")
    return "\n".join(lines)


# ─── DeepSeek API ─────────────────────────────────────────────────

# 模型列表，按优先级排列
DEEPSEEK_MODELS = [
    "deepseek-chat",              # 最新强模型，推荐
]


def call_llm_with_retry(prompt, api_key, max_retries=3):
    """
    调用 DeepSeek API，带重试机制
    """
    from openai import OpenAI

    client = OpenAI(
        api_key=api_key,
        base_url="https://api.deepseek.com/"
    )

    for model_index, model_name in enumerate(DEEPSEEK_MODELS):
        retries = max_retries if model_index == 0 else max_retries - 1

        for attempt in range(retries + 1):
            try:
                print(f"🤖 正在调用 {model_name} (尝试 {attempt + 1}/{retries + 1})...")

                response = client.chat.completions.create(
                    model=model_name,
                    messages=[
                        {
                            "role": "user",
                            "content": prompt
                        }
                    ],
                    temperature=0.3,
                    max_tokens=2000
                )

                content = response.choices[0].message.content.strip()
                # 去掉可能的 markdown 代码块包裹
                if content.startswith("```"):
                    content = re.sub(r"^```(?:markdown)?\n?", "", content)
                    content = re.sub(r"\n?```$", "", content)

                print(f"✅ 成功使用模型: {model_name}")
                return content

            except Exception as e:
                error_str = str(e).lower()

                # 判断是否是服务繁忙错误
                is_busy = (
                    "503" in str(e) or
                    "rate limit" in error_str or
                    "unavailable" in error_str or
                    "timeout" in error_str
                )

                if is_busy and attempt < retries:
                    wait_time = (attempt + 1) * 3
                    print(f"⚠️ {model_name} 服务繁忙，等待 {wait_time} 秒后重试...")
                    time.sleep(wait_time)
                    continue

                # 最后一个重试也失败
                if attempt >= retries:
                    if model_index < len(DEEPSEEK_MODELS) - 1:
                        print(f"❌ {model_name} 不可用，切换到备用模型...")
                        break
                    else:
                        print(f"❌ 所有模型均不可用: {e}")
                        raise

    raise Exception("所有 DeepSeek 模型均不可用，请稍后再试")


SYSTEM_PROMPT_MORNING = """你是一位资深的 AI 领域技术架构师与科技媒体主编。
你的任务是对提供的资讯进行深度筛选和总结，为资深 Java/AI 开发者输出最有价值的 AI 行业动态。

【筛选标准 - 极其重要】
请严格按照以下标准过滤，宁缺毋滥：
1. 拒绝水文：排除无实质技术内容的公关文、股市波动、毫无根据的未来预测
2. 聚焦硬核技术：优先保留主流大模型（DeepSeek/OpenAI/Claude/Qwen等）的发布/版本更新、重大技术突破
3. 关注工程落地：优先保留与 AI 应用开发框架（Spring AI/LangChain/Dify）、向量数据库、Agent 编排工具相关的更新
4. 关注实用工具：优先保留能极大提升程序员开发效率的 AI 工具

【输出格式 - 必须严格遵守】
生成 4 条最核心的资讯，每条格式：

## N. 标题（一句话概括核心事件）

**热度评级：** 🔥 现象级 / ⭐ 值得关注

**核心摘要：** 80-100字，说明发布了什么、核心功能、技术亮点

**对开发者的价值：** 50-80字，说明对开发工作的具体帮助

最后附上数据来源行。

注意：只输出最终简报内容，每条内容要精简，总字数控制在 800 字以内。不要解释你的选择过程。"""


SYSTEM_PROMPT_EVENING = """你是一位资深的 AI 领域技术架构师与科技媒体主编。
你的任务是汇总当日（北京时间）全天最重要的 AI 行业动态，为资深 Java/AI 开发者输出精准的晚间总结。

【筛选标准 - 极其重要】
请严格按照以下标准过滤，宁缺毋滥：
1. 拒绝水文：排除无实质技术内容的公关文、股市波动、毫无根据的未来预测
2. 聚焦硬核技术：优先保留主流大模型（DeepSeek/OpenAI/Claude/Qwen等）的发布/版本更新、重大技术突破
3. 关注工程落地：优先保留与 AI 应用开发框架（Spring AI/LangChain/Dify）、向量数据库、Agent 编排工具相关的更新
4. 关注实用工具：优先保留能极大提升程序员开发效率的 AI 工具
5. 【晚间版特别说明】如果资讯中提及某条是"今早发布的"，在标题前标注"[NEW]"标记

【输出格式 - 必须严格遵守】
生成 4 条最核心的资讯，每条格式：

## N. 标题（一句话概括核心事件）

**热度评级：** 🔥 现象级 / ⭐ 值得关注

**核心摘要：** 80-100字，说明发布了什么、核心功能、技术亮点

**对开发者的价值：** 50-80字，说明对开发工作的具体帮助

最后附上数据来源行。

注意：只输出最终简报内容，每条内容要精简，总字数控制在 800 字以内。不要解释你的选择过程。"""


SYSTEM_PROMPT = SYSTEM_PROMPT_MORNING  # 默认值，后续会根据 edition 动态选择


def build_prompt(news_text, edition="morning"):
    """构建发给 Gemini 的完整 prompt"""
    system_prompt = SYSTEM_PROMPT_EVENING if edition == "evening" else SYSTEM_PROMPT_MORNING
    edition_hint = "今日晚间总结" if edition == "evening" else "今日早间简报"
    return f"""{system_prompt}

---

{news_text}

请根据以上资讯，生成今日的 {edition_hint}。"""


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

    api_key = os.environ.get("DEEPSEEK_API_KEY", "")
    webhook_url = os.environ.get("WECHAT_WEBHOOK", "")

    if not api_key:
        print("❌ 缺少 DEEPSEEK_API_KEY 环境变量")
        sys.exit(1)
    if not webhook_url:
        print("❌ 缺少 WECHAT_WEBHOOK 环境变量")
        sys.exit(1)

    # Step 1: 抓取资讯
    news_items = extract_ai_news()

    if not news_items:
        print("⚠️ 未抓取到任何 AI 相关资讯，发送提示")
        fallback = f"> ⚠️ AI 简报\n\n未抓取到今日资讯，请检查网络或 RSS 源是否正常。"
        push_to_wechat(fallback, webhook_url)
        sys.exit(1)

    print(f"\n📊 共抓取到 {len(news_items)} 条 AI 相关资讯\n")

    # Step 2: 用 Gemini 生成简报
    news_text = format_news_for_prompt(news_items)
    prompt = build_prompt(news_text, edition)

    try:
        report = call_llm_with_retry(prompt, api_key)
    except Exception as e:
        print(f"❌ LLM API 调用失败: {e}")
        sys.exit(1)

    # Step 3: 保存到文件
    header = f"# 🤖 AI 每日高价值简报 · {today}（{edition_suffix}）\n\n---\n\n"
    with open(report_file, "w", encoding="utf-8") as f:
        f.write(header + report)
    print(f"💾 已保存: {report_file}")

    # Step 4: 推送企业微信
    wx_content = convert_to_wework_markdown(header + report)
    push_to_wechat(wx_content, webhook_url)

    # Step 5: 同步到 Spring Boot 后端（Web 页面数据源）
    run_id = os.environ.get("GITHUB_RUN_ID", "local")
    title_text = f"【{edition_suffix}】AI 每日简报 {today}"
    # 取前 100 字作摘要
    summary_text = report[:100] + "..." if len(report) > 100 else report
    push_to_backend(edition, title_text, header + report, summary_text, run_id)

    print(f"\n✅ 今日简报完成！({now_beijing().strftime('%H:%M:%S')})")


if __name__ == "__main__":
    main()
