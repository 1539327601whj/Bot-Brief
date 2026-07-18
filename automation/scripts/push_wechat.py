#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
本地 AI 日报推送脚本
读取今日 AI 日报 Markdown 文件，推送到企业微信群
同时清理 15 天前的旧日报文件
"""

import os
import re
import sys
import glob
from datetime import datetime, timezone, timedelta

# 北京时区
BEIJING_TZ = timezone(timedelta(hours=8))

def now_beijing():
    return datetime.now(BEIJING_TZ)

def get_report_file(date_str=None):
    """获取今日日报文件路径"""
    if date_str is None:
        date_str = now_beijing().strftime("%Y-%m-%d")
    
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_dir = os.path.dirname(script_dir)
    
    report_path = os.path.join(project_dir, f"AI日报_{date_str}.md")
    return report_path

def read_report(file_path):
    """读取日报文件内容"""
    if not os.path.exists(file_path):
        print(f"❌ 日报文件不存在: {file_path}")
        return None
    with open(file_path, "r", encoding="utf-8") as f:
        return f.read()

def convert_to_wework_markdown(md_text):
    """将标准 Markdown 转为企业微信兼容格式，控制长度"""
    lines = md_text.split("\n")
    out = []
    for line in lines:
        stripped = line.strip()
        if not stripped:
            out.append("")
            continue
        # 跳过数据来源/分隔行
        if stripped.startswith(">") and ("数据来源" in stripped or "下次推送" in stripped):
            continue
        if stripped == "---":
            out.append("---")
            continue
        # Markdown 标题转企业微信格式
        if stripped.startswith("### "):
            out.append(f"**{stripped[4:]}**")
        elif stripped.startswith("## "):
            out.append(f"> **{stripped[3:]}**")
        elif stripped.startswith("# "):
            out.append(f"> **{stripped[2:]}**")
        elif stripped.startswith("|") and stripped.endswith("|"):
            # 跳过 Markdown 表格
            continue
        else:
            out.append(stripped)

    result = "\n".join(out)

    # 企业微信限制约 4000 字节，预留安全余量
    MAX_BYTES = 3800
    encoded = result.encode("utf-8")

    if len(encoded) > MAX_BYTES:
        current_bytes = 0
        truncated_lines = []
        for line in out:
            line_bytes = len(line.encode("utf-8")) + 1
            if current_bytes + line_bytes > MAX_BYTES - 100:
                break
            truncated_lines.append(line)
            current_bytes += line_bytes
        result = "\n".join(truncated_lines) + "\n\n> ...(剩余内容请查看完整日报文件)"

    return result

def push_to_wechat(content, webhook_url):
    """通过企业微信 Webhook 推送 Markdown 消息"""
    try:
        import urllib.request
        import json

        payload = {
            "msgtype": "markdown",
            "markdown": {"content": content}
        }
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            webhook_url,
            data=data,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            result = json.loads(resp.read().decode("utf-8"))
        
        if result.get("errcode") == 0:
            print(f"✅ 企业微信推送成功 ({len(data)} bytes)")
            return True
        else:
            print(f"❌ 企业微信推送失败: {result}")
            return False
    except Exception as e:
        print(f"❌ 推送异常: {e}")
        return False

def cleanup_old_reports(days=15):
    """清理 N 天前的旧日报文件"""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_dir = os.path.dirname(script_dir)
    
    pattern = os.path.join(project_dir, "AI日报_*.md")
    files = glob.glob(pattern)
    
    cutoff = now_beijing() - timedelta(days=days)
    removed = 0
    
    for f in files:
        basename = os.path.basename(f)
        # 提取日期部分 AI日报_YYYY-MM-DD.md
        m = re.search(r"AI日报_(\d{4}-\d{2}-\d{2})", basename)
        if m:
            file_date = datetime.strptime(m.group(1), "%Y-%m-%d").replace(tzinfo=BEIJING_TZ)
            if file_date < cutoff:
                os.remove(f)
                print(f"🗑️ 已清理旧日报: {basename}")
                removed += 1
    
    if removed == 0:
        print("✅ 无需清理旧日报文件")
    else:
        print(f"✅ 共清理 {removed} 个旧日报文件")

def main():
    today = now_beijing().strftime("%Y-%m-%d")
    print(f"\n{'='*50}")
    print(f"📤 AI 日报推送脚本 · {today}")
    print(f"{'='*50}\n")

    # 获取 webhook URL（优先从环境变量，其次从配置文件）
    webhook_url = os.environ.get("WECHAT_WEBHOOK", "")
    
    if not webhook_url:
        # 尝试从项目根目录的 .env.local 文件读取
        script_dir = os.path.dirname(os.path.abspath(__file__))
        project_dir = os.path.dirname(script_dir)
        env_file = os.path.join(project_dir, ".env.local")
        if os.path.exists(env_file):
            with open(env_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line.startswith("WECHAT_WEBHOOK="):
                        webhook_url = line[len("WECHAT_WEBHOOK="):].strip().strip('"').strip("'")
                        break

    if not webhook_url:
        print("⚠️ 未配置企业微信 Webhook URL")
        print("请通过以下方式之一配置：")
        print("  1. 设置环境变量: WECHAT_WEBHOOK=https://qyapi.weixin.qq.com/...")
        print("  2. 在项目根目录创建 .env.local 文件，写入: WECHAT_WEBHOOK=https://...")
        print("\n📋 日报内容已保存到文件，跳过推送步骤")
        # 仍然执行清理
        cleanup_old_reports()
        return

    # 读取日报文件
    report_file = get_report_file(today)
    content = read_report(report_file)
    if not content:
        sys.exit(1)

    print(f"📄 已读取日报: {os.path.basename(report_file)}")

    # 转换并推送
    wx_content = convert_to_wework_markdown(content)
    push_to_wechat(wx_content, webhook_url)

    # 清理旧文件
    print()
    cleanup_old_reports(days=15)

    print(f"\n✅ 推送流程完成！({now_beijing().strftime('%H:%M:%S')})")

if __name__ == "__main__":
    main()
