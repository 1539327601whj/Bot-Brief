#!/usr/bin/env python3
"""探一次 DeepSeek 接口，把**原始返回**打出来——专门用来查「返回正文是空的」。

为什么要有这个脚本：代码查询页的「生成深度解读」在真实环境里出现过这样一段：

    AI 暂未返回内容，请稍后重试。

而这句话在 `AiClientServiceImpl` 里只对应一种情形：**HTTP 200、响应里没有 error、
但 `choices[0].message.content` 是空的**。也就是说请求发出去了、服务端也接了，
只是没给正文。光凭这一句排不出原因，而可能的原因处置完全不同：

  · `choices` 是空数组          → 模型名不对（网关接了但没这个模型）
  · `finish_reason == "length"` → max_tokens 不够，预算被截断吃光
  · 有 `reasoning_content` 没 `content` → 思考型模型，预算先花在思考上
  · 什么都没有、就是空的        → 得看网关那边了

这四种在**原始 JSON** 里一眼可辨，但后端原来那条日志只记了 httpStatus 与消息条数
（200 是必然的，消息条数也不可能是别的原因），等于什么都没说。后端现在已经把
`finish_reason`、`reasoning_content` 长度和原文都记进日志了；这个脚本是**在那之外
再给一条不依赖重启后端的路**——直接问接口本身。

跑法（Windows 上 python 是商店占位符，必须用 py -3）：

    py -3 automation/scripts/verify_llm.py
    py -3 automation/scripts/verify_llm.py --model <另一个模型名>
    py -3 automation/scripts/verify_llm.py --prompt "## 一句话结论" --max-tokens 4000

key 从环境变量 `DEEPSEEK_API_KEY` 取（与后端同一个变量），也可以用 `--api-key` 直接给。
**key 不会被打印出来**，报错信息里也只出现它的长度与前四位。

⚠️ **这个脚本会真的花一次模型调用（默认 1 次）。** 一般情况下**一次就够**：
`--model` 每多给一个就多一次调用（对比两个模型的行为时才用得上）。
它打的不是东财，与行情域名那份限流无关，可以立刻重跑。

只读：不发写请求、不入库、不推送。
退出码 0 = 拿到了**非空正文**；1 = 正文是空的或调用失败（并在结论里指认是哪一种）。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DEFAULT_BASE_URL = "https://api.deepseek.com"
# 与后端 `application.yml`、两个 workflow、daily_report.py、docker-compose.yml 一致。
# 这个默认值**只在环境变量没设时**生效，所以它必须与后端那个默认值是同一个名字，
# 否则探针探到的和线上跑的不是一个模型，白探。
DEFAULT_MODEL = "deepseek-v4-flash"

# 与后端 `AnalysisPrompt.system()` 里「以 ## 小节开头」那条要求一致的最小提示词。
# **刻意不给长材料**：材料本身不是变量，这里要单测的是「模型到底会不会给正文」。
DEFAULT_PROMPT = "请用中文回答，只输出一行，以 ## 开头：今天的天气适合出门吗？"


def mask(key: str) -> str:
    """key 的可见形态：长度 + 前四位。够认出「是不是同一个」，又不足以泄漏。"""
    if not key:
        return "(空)"
    head = key[:4] if len(key) > 4 else ""
    return f"长度 {len(key)}、前四位 {head}…"


def one_call(base_url: str, key: str, model: str, prompt: str,
             max_tokens: int, temperature: float, timeout: float) -> dict:
    """发一次非流式请求，返回**原始** HTTP 状态与正文文本。

    刻意不在这里解析：这一趟的目的就是看原文。解析放在 report() 里，
    而且解析失败也算一种结果（说明返回的形状和我们想的都不一样）。
    """
    body = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": False,  # 与后端一致。后端没开流式，所以这里也不能开——否则测的不是同一条路
    }
    request = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return {"status": response.status, "text": response.read().decode("utf-8", "replace")}
    except urllib.error.HTTPError as e:
        # 4xx/5xx 也要把正文带回来：DeepSeek 的错误信息在 body 里（`{"error":{...}}`），
        # 只报一个状态码等于把唯一一句解释扔了
        return {"status": e.code, "text": e.read().decode("utf-8", "replace")}
    except Exception as e:  # noqa: BLE001 — 连不上、超时、DNS 都要当结果打出来
        return {"status": None, "text": f"(请求没发出去：{type(e).__name__}: {e})"}


def report(result: dict, model: str, max_tokens: int) -> bool:
    """把一次返回打成结论。返回值 = 「这一次算不算通过」。"""
    print(f"\n{'=' * 72}\nmodel = {model}\n{'=' * 72}")
    print(f"HTTP 状态：{result['status']}")
    print("原始正文：")
    try:
        # 能格式化就格式化——分位、reasoning_content 这些嵌套字段好看清
        print(json.dumps(json.loads(result["text"]), ensure_ascii=False, indent=2))
    except Exception:  # noqa: BLE001
        print(result["text"])

    if result["status"] != 200:
        print("\n结论：**调用就没成功**。上面 error.message 是服务端原话。")
        print("      · 401/403 → key 不被接受（换 key）")
        print("      · 404     → 路径不对（base-url 是否多写/少写了什么）")
        print("      · 400 且提到 model → 模型名不对")
        return False

    try:
        root = json.loads(result["text"])
    except Exception as e:  # noqa: BLE001
        print(f"\n结论：200 但不是 JSON（{e}）。多半是被网关/代理拦下换了个页面。")
        return False

    if "error" in root:
        # 与后端 `AiClientServiceImpl` 同一支判据：有 error 就走失败，不看 choices
        print(f"\n结论：服务端在 200 里放了 error：{root['error']}")
        return False

    choices = root.get("choices")
    if not isinstance(choices, list) or not choices:
        print("\n结论：**200、没有 error、但 choices 是空的。**")
        print(f"      最可能是模型名 {model!r} 不存在，网关接下了请求但没给结果。")
        print("      把 DEEPSEEK_MODEL 换成一个确定存在的模型名再试。")
        return False

    message = choices[0].get("message") or {}
    content = message.get("content") or ""
    reasoning = message.get("reasoning_content") or ""
    finish = choices[0].get("finish_reason")

    print(f"\nfinish_reason = {finish}")
    print(f"content 长度 = {len(content)}；reasoning_content 长度 = {len(reasoning)}")

    if content.strip():
        print("\n结论：**拿到了非空正文，这一趟通过。**")
        print(f"      正文前 80 字：{content.strip()[:80]}")
        return True

    print("\n结论：**正文是空的**——这就是页面上那句「AI 暂未返回内容」的来源。成因是下面这条：")
    if finish == "length":
        print(f"      finish_reason=length：预算被截断吃光。这次给的是 max_tokens={max_tokens}")
        print("      调大它（后端配置项 screener.analysis.brief-max-tokens / 环境变量")
        print("      SCREENER_ANALYSIS_BRIEF_MAX_TOKENS，默认已放宽到 4000）。")
    elif reasoning.strip():
        print(f"      只回了思考过程（reasoning {len(reasoning)} 字）、没有正文。")
        print("      这是思考型模型的行为：非流式下预算先花在思考上。两条路——")
        print("      · 把 max_tokens 调大（默认 4000），或")
        print("      · 换一个非思考型模型：DEEPSEEK_MODEL=<模型名>")
    else:
        print("      没有 finish_reason、也没有 reasoning_content，什么都没给。")
        print("      这一支得看服务端了；把上面这段原始正文贴出来。")
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="探一次 DeepSeek，看返回里到底有没有正文")
    parser.add_argument("--base-url", default=os.environ.get("DEEPSEEK_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--api-key", default=os.environ.get("DEEPSEEK_API_KEY", ""))
    parser.add_argument("--model", action="append",
                        help="可以给多次以对比；不给就用 DEEPSEEK_MODEL 或默认值。**每多一个多一次调用**")
    parser.add_argument("--prompt", default=DEFAULT_PROMPT)
    parser.add_argument("--max-tokens", type=int, default=4000,
                        help="默认 4000，与后端放宽后的默认值一致")
    parser.add_argument("--temperature", type=float, default=0.3)
    parser.add_argument("--timeout", type=float, default=60.0)
    args = parser.parse_args()

    models = args.model or [os.environ.get("DEEPSEEK_MODEL", DEFAULT_MODEL)]

    print(f"base-url = {args.base_url}")
    print(f"key      = {mask(args.api_key)}")
    if not args.api_key:
        print("\n没有拿到 key。先设环境变量 DEEPSEEK_API_KEY（Windows 上要在设完之后重开终端），")
        print("或者用 --api-key 直接给。**没有 key 时后端走的是「未配置」那一支，")
        print("根本不会发请求**——所以如果页面上写的是「暂未返回内容」而不是「未配置」，")
        print("说明后端那个进程里 key 是有的，只是和你这个终端里的不一样。")
        return 1

    all_ok = True
    for model in models:
        # 每个模型只打一次：**不重试、不换关键词重问**。与项目里所有外呼的纪律一致，
        # 而且这里重试没有意义——空正文是确定的形状，不是抖动。
        ok = report(one_call(args.base_url, args.api_key, model, args.prompt,
                             args.max_tokens, args.temperature, args.timeout),
                    model, args.max_tokens)
        all_ok = all_ok and ok

    print(f"\n共打了 {len(models)} 次外呼。")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
