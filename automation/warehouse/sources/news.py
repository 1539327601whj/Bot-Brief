# -*- coding: utf-8 -*-
"""新闻 / 公告 / 政策三类事件源。

三路都是「只列事实」：标题、日期、链接、媒体。**不读正文、不做情绪打分**；
事件类型与极性由 Java 规则层在读取时判定，仓库只存原文。这样同一批事件在
规则版本升级后可以重新分类，不需要重新抓取。

* 东财资讯搜索：按关键词，个股/ETF/指数都能用；`param` 是塞进 URL 的 JSON 字符串，
  中文保持原样（不做 ``\\uXXXX`` 转义），只编码一次。
* 东财公告：法定披露，权重最高；ETF 恒为空，这是正常结果，不是失败。
* Google News RSS：政策类，查询词必须带 ``when:30d``，否则返回的绝大多数条目
  落在时间窗之外。
"""

from __future__ import annotations

import json
import logging
import re
import xml.etree.ElementTree as ET
from datetime import datetime
from email.utils import parsedate_to_datetime
from typing import Any

from ..config import Config
from ..normalize import (
    BEIJING_OFFSET,
    clean_text,
    parse_datetime,
    sha256_hex,
    strip_html,
    truncate,
)
from ..rate_limit import HttpSource, SourceError

logger = logging.getLogger(__name__)

PROVIDER_NEWS = "eastmoney-news"
PROVIDER_NOTICE = "eastmoney-notice"
PROVIDER_POLICY = "google-news"

SOURCE_NEWS = "eastmoney-news"
SOURCE_NOTICE = "eastmoney-notice"
SOURCE_POLICY = "google-news"

NEWS_HOST = "https://search-api-web.eastmoney.com/search/jsonp"
NEWS_REFERER = "https://so.eastmoney.com/"
NEWS_TYPE = "cmsArticleWebOld"
NEWS_PAGE_SIZE = 20
NEWS_CALLBACK = "cb"

NOTICE_HOST = "https://np-anotice-stock.eastmoney.com/api/security/ann"
NOTICE_REFERER = "https://data.eastmoney.com/notices/"
NOTICE_PAGE_SIZE = 20

POLICY_HOST = "https://news.google.com/rss/search"
POLICY_REFERER = "https://news.google.com/"
POLICY_WINDOW_DAYS = 30

_JSONP = re.compile(r"^[A-Za-z_$][\w$]*\((.*)\)\s*;?\s*$", re.DOTALL)


def news_search_param(keyword: str, page_size: int = NEWS_PAGE_SIZE) -> str:
    """东财搜索的 `param`。手拼 JSON，保证中文是原样 UTF-8，而不是转义序列。"""
    escaped = json.dumps(keyword, ensure_ascii=False)[1:-1]
    return (
        '{"uid":"",'
        f'"keyword":"{escaped}",'
        f'"type":["{NEWS_TYPE}"],'
        '"client":"web","clientType":"web","clientVersion":"curr",'
        f'"param":{{"{NEWS_TYPE}":{{'
        '"searchScope":"default","sort":"default","pageIndex":1,'
        f'"pageSize":{page_size},'
        '"preTag":"<em>","postTag":"</em>"}}}}'
    )


def strip_jsonp(text: str, callback: str = NEWS_CALLBACK) -> str:
    body = (text or "").strip()
    if not body.startswith(callback + "("):
        raise SourceError(PROVIDER_NEWS, "东财资讯返回的不是预期的 JSONP")
    match = _JSONP.match(body)
    if not match:
        raise SourceError(PROVIDER_NEWS, "东财资讯 JSONP 外壳无法解析")
    return match.group(1)


def parse_news_jsonp(text: str) -> list[dict[str, Any]]:
    body = json.loads(strip_jsonp(text))
    rows = (body.get("result") or {}).get(NEWS_TYPE)
    if not isinstance(rows, list):
        message = clean_text(body.get("msg"))
        raise SourceError(
            PROVIDER_NEWS,
            f"资讯源返回结构异常（result.{NEWS_TYPE} 不存在）" + (f"：{message}" if message else ""),
        )
    items: list[dict[str, Any]] = []
    for row in rows:
        title = strip_html(row.get("title"))
        url = clean_text(row.get("url"))
        published_at = parse_datetime(row.get("date"))
        if not title or not url or published_at is None:
            continue
        items.append({
            "category": "NEWS",
            "event_type": None,
            "title": truncate(title, 500),
            "summary": None,
            "url": truncate(url, 1000),
            "published_at": published_at,
            "source": SOURCE_NEWS,
            "media": truncate(clean_text(row.get("mediaName")), 100),
        })
    return items


def notice_url(code: str) -> str:
    return (
        f"{NOTICE_HOST}?sr=-1&page_size={NOTICE_PAGE_SIZE}&page_index=1"
        f"&ann_type=A&client_source=web&stock_list={code}"
    )


def _notice_category(row: dict[str, Any]) -> str | None:
    columns = row.get("columns")
    if not isinstance(columns, list):
        return None
    names = []
    for column in columns:
        if isinstance(column, dict):
            name = clean_text(column.get("column_name"))
            if name and name not in names:
                names.append(name)
    return "、".join(names) if names else None


def parse_notices(code: str, body: dict[str, Any]) -> list[dict[str, Any]]:
    data = body.get("data")
    if not isinstance(data, dict):
        raise SourceError(PROVIDER_NOTICE, "公告源返回结构异常（没有 data 段）")
    rows = data.get("list")
    if not isinstance(rows, list):
        raise SourceError(PROVIDER_NOTICE, "公告源返回结构异常（data 里没有 list）")
    items = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        title = clean_text(row.get("title")) or clean_text(row.get("title_ch"))
        art_code = clean_text(row.get("art_code"))
        published_at = parse_datetime(row.get("notice_date"))
        if not title or not art_code or published_at is None:
            continue
        own_code = code
        codes = row.get("codes")
        if isinstance(codes, list) and codes and isinstance(codes[0], dict):
            own_code = clean_text(codes[0].get("stock_code")) or code
        items.append({
            "category": "NOTICE",
            "event_type": truncate(_notice_category(row), 64),
            "title": truncate(title, 500),
            "summary": None,
            "url": truncate(f"https://data.eastmoney.com/notices/detail/{own_code}/{art_code}.html", 1000),
            "published_at": published_at,
            "source": SOURCE_NOTICE,
            "media": "东财公告",
        })
    return items


def policy_query(subject: str) -> str:
    return f"{subject} 政策 when:{POLICY_WINDOW_DAYS}d"


def _rss_datetime(value: Any) -> datetime | None:
    text = clean_text(value)
    if not text:
        return None
    try:
        parsed = parsedate_to_datetime(text)
    except (TypeError, ValueError):
        return parse_datetime(value)
    if parsed is None:
        return None
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(BEIJING_OFFSET).replace(tzinfo=None)
    return parsed


def parse_policy_rss(xml_text: str) -> list[dict[str, Any]]:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise SourceError(PROVIDER_POLICY, f"Google News RSS 解析失败: {exc}") from exc
    items = []
    for item in root.iter("item"):
        title = strip_html(item.findtext("title"))
        link = clean_text(item.findtext("link"))
        published_at = _rss_datetime(item.findtext("pubDate"))
        if not title or not link or published_at is None:
            continue
        items.append({
            "category": "POLICY",
            "event_type": None,
            "title": truncate(title, 500),
            "summary": None,
            "url": truncate(link, 1000),
            "published_at": published_at,
            "source": SOURCE_POLICY,
            "media": truncate(clean_text(item.findtext("source")), 100),
        })
    return items


def event_row(item: dict[str, Any], instrument_code: str | None, fetched_at: datetime) -> dict[str, Any]:
    """源层结果 → ``md_event`` 行。``content_hash`` 是唯一键。"""
    published_at = item.get("published_at")
    digest = sha256_hex(
        item.get("category"),
        item.get("source"),
        item.get("title"),
        item.get("url"),
        published_at.date() if isinstance(published_at, datetime) else published_at,
    )
    return {
        "instrument_code": instrument_code,
        "category": item.get("category"),
        "event_type": item.get("event_type"),
        "title": item.get("title"),
        "summary": item.get("summary"),
        "url": item.get("url"),
        "published_at": published_at,
        "source": item.get("source"),
        "importance": "NORMAL",
        "polarity": "NEUTRAL",
        "content_hash": digest,
        "fetched_at": fetched_at,
    }


class NewsClients:
    def __init__(self, http: HttpSource, config: Config):
        self.http = http
        self.config = config

    def search_news(self, keyword: str) -> list[dict[str, Any]]:
        text = self.http.get_text(
            PROVIDER_NEWS, NEWS_HOST,
            params={"cb": NEWS_CALLBACK, "param": news_search_param(keyword)},
            headers={"Referer": NEWS_REFERER},
        )
        return parse_news_jsonp(text)

    def fetch_notices(self, stock_code: str) -> list[dict[str, Any]]:
        body = self.http.get_json(
            PROVIDER_NOTICE, NOTICE_HOST,
            params={
                "sr": "-1",
                "page_size": str(NOTICE_PAGE_SIZE),
                "page_index": "1",
                "ann_type": "A",
                "client_source": "web",
                "stock_list": stock_code,
            },
            headers={"Referer": NOTICE_REFERER},
        )
        return parse_notices(stock_code, body)

    def search_policy(self, subject: str) -> list[dict[str, Any]]:
        text = self.http.get_text(
            PROVIDER_POLICY, POLICY_HOST,
            params={
                "q": policy_query(subject),
                "hl": "zh-CN",
                "gl": "CN",
                "ceid": "CN:zh-Hans",
            },
            headers={"Referer": POLICY_REFERER},
        )
        return parse_policy_rss(text)


__all__ = [
    "NewsClients",
    "PROVIDER_NEWS",
    "PROVIDER_NOTICE",
    "PROVIDER_POLICY",
    "SOURCE_NEWS",
    "SOURCE_NOTICE",
    "SOURCE_POLICY",
    "event_row",
    "news_search_param",
    "parse_news_jsonp",
    "parse_notices",
    "parse_policy_rss",
    "policy_query",
]
