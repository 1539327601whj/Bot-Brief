# -*- coding: utf-8 -*-
"""把源层结果写进 ``md_*``。

每个同步函数返回 :class:`SyncOutcome`，状态语义与页面一致：

* ``OK``：取到并写入；
* ``PARTIAL``：写入了已知部分，但至少一个子段失败（例如四张财务报表达成三张）；
* ``EMPTY``：源明确返回 0 条（ETF 公告恒为空属于这一类，正常）；
* ``FAILED``：一段都没写成；
* ``SKIPPED``：按幂等/新鲜度判断不需要跑。

**单段失败不抛到顶层**：一个财务接口挂了，不应该让资金流也白抓。只有「被限流」和
「数据库不可用」会向上冒泡——前者必须让任务进入冷却，后者重试也没有意义。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

STATUS_ORDER = {"FAILED": 4, "PARTIAL": 3, "EMPTY": 2, "OK": 1, "SKIPPED": 0}


@dataclass
class SyncOutcome:
    status: str
    rows: int = 0
    source: str | None = None
    error: str | None = None
    detail: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        payload = {"status": self.status, "rows": self.rows, "source": self.source}
        if self.error:
            payload["error"] = self.error
        if self.detail:
            payload["detail"] = self.detail
        return payload


def worst(*outcomes: SyncOutcome) -> str:
    return max(outcomes, key=lambda item: STATUS_ORDER.get(item.status, 0), default=SyncOutcome("SKIPPED")).status


def combine(name: str, parts: dict[str, SyncOutcome]) -> SyncOutcome:
    """把多个子段合成为一个总状态；子段明细放进 ``detail``。"""
    total_rows = sum(part.rows for part in parts.values())
    errors = [f"{key}: {part.error}" for key, part in parts.items() if part.error]
    return SyncOutcome(
        status=worst(*parts.values()) if parts else "SKIPPED",
        rows=total_rows,
        source=name,
        error="; ".join(errors)[:1000] or None,
        detail={key: part.as_dict() for key, part in parts.items()},
    )


__all__ = ["STATUS_ORDER", "SyncOutcome", "combine", "worst"]
