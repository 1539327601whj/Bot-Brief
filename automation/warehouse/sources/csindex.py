# -*- coding: utf-8 -*-
"""中证指数官网（csindex.com.cn）取数。

指数估值按来源分组是硬口径：csindex 系指数**只能**走这里，不能拿蛋卷的 PE 去补，
也不能用东财的估值当同一序列。这里取三样东西：

* ``indexCsiDsPe``：指数 PE(TTM) 日频历史（一次约 320KB，分位由 Java 侧按统一口径算）；
* ``index-basic-info``：指数简称、发布日期、成分数量；
* ``weight/top10new`` 与 ``weight/industry-weight``：前十大成分与一级行业权重。

注意路径：页面 SPA 的 baseURL 是 ``/csindex-home``，权重接口在 ``/index/weight/...``；
旧文档里的 ``/indexInfo/weight/...`` 是 404。
"""

from __future__ import annotations

import logging
import re
from datetime import date
from typing import Any, Sequence
from urllib.parse import quote

from ..config import Config
from ..normalize import (
    clean_text,
    parse_date,
    to_decimal,
    to_number,
    truncate,
)
from ..rate_limit import HttpSource, SourceError

logger = logging.getLogger(__name__)

PROVIDER = "csindex"
SOURCE_PE = "csindex-pe"
SOURCE_PROFILE = "csindex-basic-info"
SOURCE_TOP10 = "csindex-top10"
SOURCE_INDUSTRY = "csindex-industry-weight"

BASE = "https://www.csindex.com.cn/csindex-home"
PE_URL = f"{BASE}/perf/indexCsiDsPe"
BASIC_INFO_URL = f"{BASE}/indexInfo/index-basic-info/{{code}}"
TOP10_URL = f"{BASE}/index/weight/top10new/{{code}}"
INDUSTRY_WEIGHT_URL = f"{BASE}/index/weight/industry-weight/{{code}}"

HEADERS = {
    "Referer": "https://www.csindex.com.cn/",
    "Accept": "application/json, text/plain, */*",
}

_SLUG = re.compile(r"[^a-z0-9]+")


def _unwrap(body: dict[str, Any], what: str) -> Any:
    """校验 ``{code, msg, data, success}`` 包装；业务失败抛 SourceError。"""
    code = body.get("code")
    success = body.get("success")
    if success is False or (code is not None and str(code) != "200"):
        raise SourceError(PROVIDER, f"{what}业务错误: code={code} msg={body.get('msg')}")
    data = body.get("data")
    if data is None:
        raise SourceError(PROVIDER, f"{what}未返回 data")
    return data


def industry_code_for(name_en: Any, name_cn: Any) -> str:
    """CSIndex 一级行业没有公开代码，用英文名的 slug 做稳定键。

    与东财行业代码不是同一套分类，**不要拿它去 join** ``md_industry_board``；
    表里保留 ``industry_name`` 供展示，代码只保证同一行业在不同日期/指数间一致。
    """
    english = clean_text(name_en)
    if english:
        slug = _SLUG.sub("-", english.strip().lower()).strip("-")
        if slug:
            return f"cics1-{slug}"
    chinese = clean_text(name_cn) or "unknown"
    # 没有英文名时用中文的十六进制编码，仍保持确定性（不用哈希，便于肉眼排查）。
    return "cics1-" + chinese.encode("utf-8").hex()[:32]


def parse_pe_history(body: dict[str, Any], today: date | None = None) -> list[dict[str, Any]]:
    data = _unwrap(body, "指数PE历史")
    rows = []
    for item in data or []:
        if not isinstance(item, dict):
            continue
        trade_date = parse_date(item.get("tradeDate"))
        # 上游字段就叫 peg，是 PE(TTM)，不是 PEG 指标；不要按字面改。
        pe_ttm = to_number(item.get("peg"))
        if trade_date is None or pe_ttm is None or pe_ttm <= 0:
            continue
        if today is not None and trade_date > today:
            continue
        rows.append({"trade_date": trade_date, "pe_ttm": pe_ttm})
    rows.sort(key=lambda item: item["trade_date"])
    return rows


def parse_basic_info(body: dict[str, Any]) -> dict[str, Any]:
    data = _unwrap(body, "指数基本信息")
    return {
        "name": clean_text(data.get("indexShortNameCn")),
        "full_name": clean_text(data.get("indexFullNameCn")),
        "constituent_count": to_number(data.get("consNumber")),
        "publish_date": parse_date(data.get("publishDate")),
        "basic_date": parse_date(data.get("basicDate")),
    }


def parse_top10(body: dict[str, Any]) -> dict[str, Any]:
    data = _unwrap(body, "指数前十大权重")
    rows = []
    as_of = parse_date(data.get("updateDate"))
    for item in data.get("weightList") or []:
        if not isinstance(item, dict):
            continue
        stock_code = clean_text(item.get("securityCode"))
        if not stock_code:
            continue
        row_date = parse_date(item.get("tradeDate")) or as_of
        as_of = as_of or row_date
        weight = to_number(item.get("weight"))
        if weight is None:
            weight = to_number(item.get("preciseWeight"))
        rows.append({
            "stock_code": stock_code.upper(),
            "stock_name": clean_text(item.get("securityName")),
            "weight": weight,
            "industry_name": clean_text(item.get("cics1stName") or item.get("csiTypeL1")),
            "industry_name_en": clean_text(item.get("cics1stNameEn") or item.get("csiTypeL1En")),
            "trade_date": row_date,
        })
    top10_sum = to_number(data.get("top10Sum"))
    if top10_sum is None and rows:
        known = [row["weight"] for row in rows if row["weight"] is not None]
        top10_sum = sum(known) if known else None
    return {"as_of": as_of, "top10_weight": top10_sum, "rows": rows}


def parse_industry_weight(body: dict[str, Any]) -> dict[str, Any]:
    data = _unwrap(body, "指数行业权重")
    as_of = parse_date(data.get("updateDate"))
    rows = []
    for item in data.get("industryWeightList") or []:
        if not isinstance(item, dict):
            continue
        name_cn = clean_text(item.get("csiTypeL1"))
        name_en = clean_text(item.get("csiTypeL1En"))
        weight = to_number(item.get("weightPct"))
        if weight is None:
            weight = to_number(item.get("preciseWeight"))
        if not name_cn or weight is None or weight < 0:
            continue
        as_of = as_of or parse_date(item.get("tradeDate"))
        rows.append({
            "industry_code": industry_code_for(name_en, name_cn),
            "industry_name": truncate(name_cn, 100),
            "weight": weight,
        })
    rows.sort(key=lambda item: item["weight"], reverse=True)
    return {"as_of": as_of, "rows": rows}


class CsindexClient:
    def __init__(self, http: HttpSource, config: Config):
        self.http = http
        self.config = config

    def _get(self, url: str, params: dict[str, str] | None = None, what: str = "") -> dict[str, Any]:
        body = self.http.get_json(PROVIDER, url, params=params, headers=HEADERS)
        if not isinstance(body, dict):
            raise SourceError(PROVIDER, f"{what}返回体不是对象")
        return body

    def fetch_pe_history(self, csindex_code: str, *, today: date | None = None) -> list[dict[str, Any]]:
        body = self._get(PE_URL, {"indexCode": csindex_code}, "指数PE历史")
        return parse_pe_history(body, today)

    def fetch_basic_info(self, csindex_code: str) -> dict[str, Any]:
        return parse_basic_info(self._get(BASIC_INFO_URL.format(code=quote(csindex_code)), what="指数基本信息"))

    def fetch_top10(self, csindex_code: str) -> dict[str, Any]:
        return parse_top10(self._get(TOP10_URL.format(code=quote(csindex_code)), what="指数前十大权重"))

    def fetch_industry_weight(self, csindex_code: str) -> dict[str, Any]:
        return parse_industry_weight(
            self._get(INDUSTRY_WEIGHT_URL.format(code=quote(csindex_code)), what="指数行业权重")
        )


__all__ = [
    "CsindexClient",
    "PROVIDER",
    "SOURCE_INDUSTRY",
    "SOURCE_PE",
    "SOURCE_PROFILE",
    "SOURCE_TOP10",
    "industry_code_for",
    "parse_basic_info",
    "parse_industry_weight",
    "parse_pe_history",
    "parse_top10",
]
