# -*- coding: utf-8 -*-
"""日期、数值、编码的统一归一。

上游最常给的两种「看起来有值」的东西：``"-"`` / ``""`` / ``"None"``，以及带逗号或
百分号的字符串。它们如果被 ``float()`` 成 0，页面会把「没取到」显示成「极差」。
所以本模块的约定是：**识别不了就返回 None**，绝不当 0。
"""

from __future__ import annotations

import hashlib
import html
import math
import re
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any, Iterable, Iterator, Sequence

BEIJING_OFFSET = timezone(timedelta(hours=8))

MISSING_STRINGS = {
    "", "-", "--", "—", "－", "none", "null", "nan", "n/a", "na", "undefined",
}

_HTML_TAG = re.compile(r"<[^>]+>")
_WHITESPACE = re.compile(r"\s+")


def is_missing(value: Any) -> bool:
    if value is None:
        return True
    if isinstance(value, str):
        return value.strip().lower() in MISSING_STRINGS
    if isinstance(value, float):
        return math.isnan(value) or math.isinf(value)
    return False


def clean_text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if text.lower() in MISSING_STRINGS:
        return None
    return text


def strip_html(value: Any) -> str | None:
    text = clean_text(value)
    if text is None:
        return None
    text = html.unescape(_HTML_TAG.sub("", text))
    text = _WHITESPACE.sub(" ", text).strip()
    return text or None


def truncate(value: str | None, limit: int) -> str | None:
    if value is None:
        return None
    return value if len(value) <= limit else value[: max(0, limit - 1)] + "…"


def to_number(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        number = float(value)
    else:
        text = clean_text(value)
        if text is None:
            return None
        text = text.replace(",", "").replace("，", "").replace("%", "").strip()
        if not text:
            return None
        try:
            number = float(text)
        except (TypeError, ValueError):
            return None
    if math.isnan(number) or math.isinf(number):
        return None
    return number


def to_int(value: Any) -> int | None:
    number = to_number(value)
    if number is None:
        return None
    return int(round(number))


def to_decimal(value: Any, scale: int = 4) -> str | None:
    """返回十进制字符串（交给 PyMySQL 写入 DECIMAL 列），无法识别时 None。"""
    number = to_number(value)
    if number is None:
        return None
    try:
        decimal_value = Decimal(str(number))
        quant = Decimal(1).scaleb(-scale)
        return str(decimal_value.quantize(quant, rounding=ROUND_HALF_UP))
    except (InvalidOperation, ValueError):
        return None


def parse_date(value: Any) -> date | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    text = clean_text(value)
    if text is None:
        return None
    text = text.split(" ")[0].split("T")[0]
    for fmt in ("%Y-%m-%d", "%Y%m%d", "%Y/%m/%d"):
        try:
            return datetime.strptime(text, fmt).date()
        except ValueError:
            continue
    # epoch 毫秒 / 秒
    number = to_number(text)
    if number is not None and number > 0:
        seconds = number / 1000.0 if number > 10_000_000_000 else number
        try:
            return datetime.fromtimestamp(seconds, BEIJING_OFFSET).date()
        except (OverflowError, OSError, ValueError):
            return None
    return None


def parse_datetime(value: Any) -> datetime | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.replace(tzinfo=None) if value.tzinfo is None else value.astimezone(BEIJING_OFFSET).replace(tzinfo=None)
    text = clean_text(value)
    if text is None:
        return None
    normalized = text.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
        if parsed.tzinfo is not None:
            parsed = parsed.astimezone(BEIJING_OFFSET).replace(tzinfo=None)
        return parsed
    except ValueError:
        pass
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M:%S", "%Y-%m-%d", "%Y%m%d"):
        try:
            return datetime.strptime(text, fmt)
        except ValueError:
            continue
    return None


def combine_beijing(day: date | None, clock: time | None) -> datetime | None:
    if day is None:
        return None
    return datetime.combine(day, clock or time(0, 0))


def today_beijing() -> date:
    return datetime.now(BEIJING_OFFSET).date()


def now_beijing() -> datetime:
    return datetime.now(BEIJING_OFFSET).replace(tzinfo=None)


def sha256_hex(*parts: Any) -> str:
    digest = hashlib.sha256()
    for part in parts:
        digest.update(b"\x1f")
        digest.update(str(part if part is not None else "").encode("utf-8"))
    return digest.hexdigest()


def asset_instrument_code(asset_type: str, raw_code: str) -> str:
    asset = (asset_type or "").strip().upper()
    raw = (raw_code or "").strip().upper()
    if not asset or not raw:
        raise ValueError("asset_type 与 raw_code 不能为空")
    return f"{asset}:{raw}"


def split_exchange_prefix(code: str) -> tuple[str | None, str]:
    value = (code or "").strip().upper()
    if value.startswith(("SH", "SZ")):
        return value[:2], value[2:]
    return None, value


def guess_market(raw_code: str, asset_type: str = "STOCK") -> str | None:
    prefix, code = split_exchange_prefix(raw_code)
    if prefix:
        return prefix
    asset = (asset_type or "").strip().upper()
    if not code:
        return None
    if asset == "ETF":
        if code.startswith(("5", "56", "58")):
            return "SH"
        if code.startswith(("1", "15", "16", "18")):
            return "SZ"
        return None
    if asset == "INDEX":
        if code.startswith("399"):
            return "SZ"
        if code.startswith(("000", "950", "951")):
            return "SH"
        if code.startswith(("93", "H3")):
            return "CSI"
        if code.startswith(("CSI", "HK", "GDAXI", "NDX", "SP500", "HSFML")):
            return "OFFSHORE"
        return None
    first = code[0]
    if first in ("6", "9"):
        return "SH"
    if first in ("0", "2", "3"):
        return "SZ"
    if first in ("4", "8"):
        return "BJ"
    return None


def eastmoney_secid(asset_type: str, raw_code: str, market: str | None = None) -> str:
    """东财 secid：1=沪，0=深，2=中证/其他指数。"""
    asset = (asset_type or "").strip().upper()
    prefix, code = split_exchange_prefix(raw_code)
    resolved_market = (market or prefix or guess_market(code, asset) or "").upper()
    if asset == "INDEX":
        if resolved_market == "SZ" or code.startswith("399"):
            return f"0.{code}"
        if resolved_market == "CSI" or code.startswith(("93", "H3")):
            return f"2.{code}"
        return f"1.{code}"
    if resolved_market == "SH":
        return f"1.{code}"
    return f"0.{code}"


def tencent_symbol(raw_code: str, market: str | None = None) -> str:
    prefix, code = split_exchange_prefix(raw_code)
    resolved = (market or prefix or guess_market(code) or "SH").upper()
    return f"{'sh' if resolved == 'SH' else 'sz'}{code}"


def report_type_of(report_date: date | None) -> str | None:
    if report_date is None:
        return None
    month_day = (report_date.month, report_date.day)
    return {
        (3, 31): "Q1",
        (6, 30): "H1",
        (9, 30): "Q3",
        (12, 31): "ANNUAL",
    }.get(month_day)


def chunked(items: Sequence[Any], size: int) -> Iterator[Sequence[Any]]:
    if size <= 0:
        raise ValueError("chunk size 必须大于 0")
    for start in range(0, len(items), size):
        yield items[start:start + size]


def unique_preserving_order(items: Iterable[Any]) -> list[Any]:
    seen = set()
    result = []
    for item in items:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result
