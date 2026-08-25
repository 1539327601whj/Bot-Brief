# -*- coding: utf-8 -*-
"""
沪深300 / 纳指100 ETF 定额配置规则引擎。

只根据 PE 分位、价格位置、当天溢价给出「今天买多少」。
不调用大模型，不预测涨跌，不比较两只指数的估值高低。
规定见 automation/agents/etf_allocation_rules.md。
"""

from __future__ import annotations

from typing import Any, Optional, TypedDict

ACTION_UNAVAILABLE = "无法判断"
ACTION_PAUSE = "暂停买"
ACTION_BUY_LESS = "少买"
ACTION_BUY_PLAN = "按计划买"
ACTION_BUY_MORE = "多买"
ACTION_TRIM = "极端高估，可考虑减持"

WEIGHT_PE = 0.55
WEIGHT_PRICE = 0.30
WEIGHT_PREMIUM = 0.15

PE_SCORE_CENTER = 50.0
PE_SCORE_SCALE = 25.0
PRICE_DRAWDOWN_SCALE = 7.5
RANGE_SCORE_CENTER = 50.0
RANGE_SCORE_SCALE = 25.0

SCORE_BUY_MORE = 0.85
SCORE_BUY_PLAN = -0.25
SCORE_BUY_LESS = -0.85

TRIM_PE_PERCENTILE = 90.0
TRIM_MAX_DRAWDOWN = -3.0
TRIM_MIN_RANGE_POSITION = 85.0

QDII_CODES = {"513100"}
METHOD_LABELS = {
    "CSI_PE_TTM_ROLLING_10Y": "中证 PE(TTM) 滚动10年分位",
    "DANJUAN_PE_TTM_PROVIDER": "蛋卷提供方 PE(TTM) 分位",
}
MANDATE_LINES = [
    "- 角色：长期（5–10年）定额配置官。只评估今天相对原定额买多少，不预测涨跌，不给点位。",
    "- 覆盖：510300 是 A 股宽基底仓；513100 是 QDII 美股成长仓。指数估值和 ETF 成交价不是同一件事，两只也不可横比。",
    "- 依据：指数 PE 分位 55%，ETF 近月价格位置 30%，当天 IOPV 溢价 15%。主数据缺失则无法判断；结论会进入真实资金，不补估。",
]


class DimensionScore(TypedDict):
    key: str
    label: str
    score: Optional[float]
    used: bool
    reason: str


class AllocationMemo(TypedDict):
    code: str
    name: str
    short_name: str
    action: str
    score: Optional[float]
    degraded: bool
    vetoes: list[str]
    reasons: list[str]
    used_dimensions: list[str]


def clamp(value: float, low: float = -2.0, high: float = 2.0) -> float:
    return max(low, min(high, value))


def finite_number(value: Any) -> Optional[float]:
    if value in (None, "", "-"):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number != number or number in (float("inf"), float("-inf")):
        return None
    return number


def short_etf_name(name: str) -> str:
    return (name or "").split()[0] or "ETF"


def is_qdii(code: str, reference_only: bool) -> bool:
    return code in QDII_CODES or reference_only


def text_or_unknown(value: Any, fallback: str = "不可确认") -> str:
    text = str(value or "").strip()
    return text if text and text not in ("None", "不可确认") else fallback


def method_label(method: Optional[str]) -> str:
    if not method:
        return "口径未声明"
    return METHOD_LABELS.get(method, method)


def pe_score(
    percentile: Optional[float],
    pe_ttm: Optional[float] = None,
    method: Optional[str] = None,
    source: Optional[str] = None,
    as_of: Optional[str] = None,
) -> tuple[Optional[float], str]:
    provenance = (
        f"口径 {method_label(method)}，估值日 {text_or_unknown(as_of)}，"
        f"来源 {text_or_unknown(source)}"
    )
    if percentile is None or not 0 <= percentile <= 100:
        return None, f"主数据缺失：指数 PE 分位不可确认（{provenance}）。按章程整单不下动作。"
    score = clamp((PE_SCORE_CENTER - percentile) / PE_SCORE_SCALE)
    if percentile < 30:
        tone = "分位偏低，估值支持高于定额"
    elif percentile <= 70:
        tone = "处于自身历史中性区，估值支持按计划买"
    else:
        tone = "分位偏高，估值倾向少买或暂停"
    pe_text = f"PE(TTM) {pe_ttm:.2f}，" if pe_ttm is not None else ""
    return score, f"{pe_text}自身历史分位 {percentile:.0f}%（{provenance}）。{tone}。这是指数估值，不是 ETF 成交价。"


def price_drawdown(
    distance_from_month_high: Optional[float],
    latest_price: Optional[float],
    month_high: Optional[float],
) -> Optional[float]:
    distance = finite_number(distance_from_month_high)
    if distance is not None:
        return distance
    price = finite_number(latest_price)
    high = finite_number(month_high)
    if price is None or high is None or high <= 0:
        return None
    return (price / high - 1) * 100


def price_score(
    drawdown: Optional[float],
    range_position: Optional[float],
    latest_price: Optional[float] = None,
    month_high: Optional[float] = None,
    source: Optional[str] = None,
    as_of: Optional[str] = None,
) -> tuple[Optional[float], str, bool]:
    provenance = f"价格截至 {text_or_unknown(as_of)}，来源 {text_or_unknown(source)}"
    price_text = ""
    if latest_price is not None:
        high_text = f"，近月高点 {month_high:.3f}" if month_high is not None else ""
        price_text = f"行情价 {latest_price:.3f}{high_text}；"
    if drawdown is not None:
        score = clamp(-drawdown / PRICE_DRAWDOWN_SCALE)
        if drawdown <= -8:
            tone = "相对近月高点回撤较大，只作为低估的确认，不能单独加钱"
        elif drawdown <= -3:
            tone = "相对近月高点有回撤，价格位置中性"
        else:
            tone = "接近近月高点，价格不支持把定额抬高"
        return score, f"{price_text}距月高 {drawdown:+.2f}%（{provenance}）。{tone}。", True
    if range_position is not None and 0 <= range_position <= 100:
        score = clamp((RANGE_SCORE_CENTER - range_position) / RANGE_SCORE_SCALE)
        return (
            score,
            f"{price_text}近月区间位置 {range_position:.0f}%（{provenance}）。缺少距月高，退而使用区间位置。",
            True,
        )
    return 0.0, f"价格位置不可确认（{provenance}）。该维按中性 0 分，不把单日涨跌当成位置。", False


def premium_score(
    rate: Optional[float],
    qdii: bool,
    source: Optional[str] = None,
    as_of: Optional[str] = None,
) -> tuple[Optional[float], str, bool]:
    provenance = f"IOPV 时间 {text_or_unknown(as_of)}，来源 {text_or_unknown(source)}"
    if rate is None:
        if qdii:
            return (
                None,
                f"当天溢价不可确认（{provenance}）。跨境IOPV常与 A 股行情对不齐，本次只用估值和价格，且不能升到多买。",
                False,
            )
        return None, f"当天溢价不可确认（{provenance}）。本次只用估值和价格，不沿用旧溢价。", False
    note = "这是 QDII 壳成本，跨境IOPV仅供参考。" if qdii else "这是当天相对净值的执行成本，不是指数涨跌。"
    if qdii:
        if rate <= 0:
            score, tone = 0.8, "相对净值不贵"
        elif rate <= 2:
            score, tone = 0.0, "溢价温和"
        elif rate <= 4:
            score, tone = -0.8, "溢价偏高"
        elif rate <= 6:
            score, tone = -1.5, "溢价较高"
        else:
            score, tone = -2.0, "溢价过高"
    else:
        if rate <= -0.5:
            score, tone = 1.5, "折价，有利于按净值买入"
        elif rate <= -0.3:
            score, tone = 0.8, "略折价"
        elif rate <= 0.5:
            score, tone = 0.0, "接近净值"
        elif rate <= 1.5:
            score, tone = -0.8, "小幅溢价"
        elif rate <= 2.5:
            score, tone = -1.5, "溢价偏高"
        else:
            score, tone = -2.0, "溢价过高"
    return score, f"当天溢价 {rate:+.2f}%（{provenance}）。{tone}。{note}", True


def premium_constraints(rate: Optional[float], qdii: bool) -> tuple[bool, bool]:
    """返回 (不能升到多买, 强制暂停买)。"""
    if rate is None:
        return qdii, False
    if qdii:
        return rate > 4, rate > 6
    return rate > 2, rate > 3


def trim_triggered(
    percentile: float,
    drawdown: Optional[float],
    range_position: Optional[float],
    premium_rate: Optional[float],
    qdii: bool,
) -> bool:
    if percentile < TRIM_PE_PERCENTILE:
        return False
    near_high = False
    if drawdown is not None and drawdown > TRIM_MAX_DRAWDOWN:
        near_high = True
    if range_position is not None and range_position >= TRIM_MIN_RANGE_POSITION:
        near_high = True
    if not near_high:
        return False
    if premium_rate is None:
        return True
    expensive_wrapper = premium_rate >= (2.0 if qdii else 0.5)
    return expensive_wrapper


def action_from_score(score: float) -> str:
    if score >= SCORE_BUY_MORE:
        return ACTION_BUY_MORE
    if score >= SCORE_BUY_PLAN:
        return ACTION_BUY_PLAN
    if score >= SCORE_BUY_LESS:
        return ACTION_BUY_LESS
    return ACTION_PAUSE


def weighted_score(parts: list[DimensionScore]) -> Optional[float]:
    used = [part for part in parts if part["used"] and part["score"] is not None]
    if not used:
        return None
    weight_map = {"pe": WEIGHT_PE, "price": WEIGHT_PRICE, "premium": WEIGHT_PREMIUM}
    total_weight = sum(weight_map[part["key"]] for part in used)
    if total_weight <= 0:
        return None
    return sum(weight_map[part["key"]] * (part["score"] or 0.0) for part in used) / total_weight


def analyze_allocation(
    code: str,
    name: str,
    pe_percentile: Any,
    pe_ttm: Any = None,
    distance_from_month_high: Any = None,
    month_range_position: Any = None,
    latest_price: Any = None,
    month_high: Any = None,
    premium_rate: Any = None,
    premium_reference_only: bool = False,
    pe_method: Any = None,
    pe_source: Any = None,
    pe_as_of: Any = None,
    price_source: Any = None,
    price_as_of: Any = None,
    premium_source: Any = None,
    premium_as_of: Any = None,
) -> AllocationMemo:
    percentile = finite_number(pe_percentile)
    if percentile is not None and not 0 <= percentile <= 100:
        percentile = None
    drawdown = price_drawdown(
        finite_number(distance_from_month_high),
        finite_number(latest_price),
        finite_number(month_high),
    )
    range_position = finite_number(month_range_position)
    rate = finite_number(premium_rate)
    qdii = is_qdii(code, premium_reference_only)

    pe_value, pe_reason = pe_score(
        percentile,
        finite_number(pe_ttm),
        None if pe_method in (None, "") else str(pe_method),
        None if pe_source in (None, "") else str(pe_source),
        None if pe_as_of in (None, "") else str(pe_as_of),
    )
    price_value, price_reason, price_used = price_score(
        drawdown,
        range_position,
        finite_number(latest_price),
        finite_number(month_high),
        None if price_source in (None, "") else str(price_source),
        None if price_as_of in (None, "") else str(price_as_of),
    )
    premium_value, premium_reason, premium_used = premium_score(
        rate,
        qdii,
        None if premium_source in (None, "") else str(premium_source),
        None if premium_as_of in (None, "") else str(premium_as_of),
    )

    dimensions: list[DimensionScore] = [
        {"key": "pe", "label": "PE", "score": pe_value, "used": pe_value is not None, "reason": pe_reason},
        {"key": "price", "label": "价格", "score": price_value, "used": price_used, "reason": price_reason},
        {"key": "premium", "label": "溢价", "score": premium_value, "used": premium_used, "reason": premium_reason},
    ]
    reasons = [part["reason"] for part in dimensions]
    used = [part["label"] for part in dimensions if part["used"]]
    degraded = any(not part["used"] for part in dimensions)
    vetoes: list[str] = []

    if pe_value is None:
        return {
            "code": code,
            "name": name,
            "short_name": short_etf_name(name),
            "action": ACTION_UNAVAILABLE,
            "score": None,
            "degraded": True,
            "vetoes": ["PE分位缺失"],
            "reasons": reasons,
            "used_dimensions": used,
        }

    score = weighted_score(dimensions)
    action = action_from_score(score if score is not None else 0.0)
    block_more, force_pause = premium_constraints(rate, qdii)

    if trim_triggered(percentile or 0.0, drawdown, range_position, rate, qdii):
        action = ACTION_TRIM
        vetoes.append("估值、价格位置与壳成本同时偏贵，触发极端高估减持备忘")
    elif force_pause:
        action = ACTION_PAUSE
        vetoes.append("当天溢价过高，强制暂停买，避免用贵壳去买指数")
    elif action == ACTION_BUY_MORE and block_more:
        action = ACTION_BUY_PLAN
        vetoes.append("溢价约束：壳成本偏高或跨境溢价缺失，不能升到多买")

    return {
        "code": code,
        "name": name,
        "short_name": short_etf_name(name),
        "action": action,
        "score": None if score is None else round(score, 3),
        "degraded": degraded,
        "vetoes": vetoes,
        "reasons": reasons,
        "used_dimensions": used,
    }


def analyze_snapshot(snapshot: dict[str, Any]) -> AllocationMemo:
    etf = snapshot.get("etf") or {}
    valuation = snapshot.get("valuation") or {}
    context = snapshot.get("price_context") or {}
    premium = snapshot.get("premium") or {}
    quote = snapshot.get("quote") or {}
    return analyze_allocation(
        code=str(etf.get("code") or ""),
        name=str(etf.get("name") or ""),
        pe_percentile=valuation.get("pe_percentile"),
        pe_ttm=valuation.get("pe_ttm"),
        distance_from_month_high=context.get("distance_from_month_high"),
        month_range_position=context.get("month_range_position"),
        latest_price=quote.get("latest_price"),
        month_high=context.get("month_high"),
        premium_rate=premium.get("premium_rate"),
        premium_reference_only=bool(premium.get("reference_only")),
        pe_method=valuation.get("percentile_method") or valuation.get("percentileMethod"),
        pe_source=valuation.get("source"),
        pe_as_of=valuation.get("updated_at"),
        price_source=context.get("source"),
        price_as_of=quote.get("data_time") or context.get("as_of"),
        premium_source=premium.get("source"),
        premium_as_of=premium.get("data_time"),
    )


def format_allocation_conclusion(memos: list[AllocationMemo]) -> str:
    if not memos:
        return "仓位备忘：本次无可用标的。"
    parts = [f"{memo['short_name']} {memo['action']}" for memo in memos]
    return "仓位备忘：" + "；".join(parts) + "。"


def format_allocation_section(memos: list[AllocationMemo]) -> list[str]:
    lines = ["## 仓位备忘", *MANDATE_LINES]
    for memo in memos:
        lines.append(f"### {memo['short_name']}（{memo['code']}）")
        lines.append(f"- 规则动作：{memo['action']}")
        if memo["degraded"]:
            lines.append("- 数据降级：部分维度缺失，已按章程降级，不补估、不沿用旧数。")
        for veto in memo["vetoes"]:
            lines.append(f"- 约束：{veto}。")
        labels = ("估值", "价格", "溢价")
        for label, reason in zip(labels, memo["reasons"]):
            lines.append(f"- {label}：{reason}")
    lines.append(
        "- 本小节由规则引擎生成，属于个人研究备忘，不构成投资建议。"
        "下单前仍需核对估值日、溢价是否为当天，以及自己的现金流。"
    )
    return lines
