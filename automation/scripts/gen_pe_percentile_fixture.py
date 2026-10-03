#!/usr/bin/env python3
"""生成跨语言分位对照数据：``automation/tests/fixtures/pe_percentile_parity.json``。

Java 侧 `RollingPercentile` 与 Python 侧 `etf_report.rolling_percentiles` 是同一个算法的
两份实现。两份实现的危险不在于会报错，而在于**差 0.3 个百分点而没人发现**——
页面上只会显示一个略微不同的数。

所以：这份 fixture 由 **Python 的生产函数**生成（不是手写的期望值），
Java 与 Python 两边各读同一份跑一遍：
  * `automation/tests/test_rolling_percentile.py` 验证 fixture 与 Python 仍逐点一致
    ——改了 Python 而忘了重新生成，它会红；
  * `backend/.../RollingPercentileTest.java` 验证 Java 与 fixture 逐点一致
    ——改了 Java，它会红。
两头都钉住，中间那份数据就没法悄悄漂移。

重新生成（只有**有意**改了口径时才做，并且要一并检查 Java 那边为何不一致）：

    py -3 automation/scripts/gen_pe_percentile_fixture.py
"""

import importlib.util
import json
import sys
from datetime import date, timedelta
from pathlib import Path

_SCRIPTS_DIR = Path(__file__).resolve().parent
if str(_SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS_DIR))

SPEC = importlib.util.spec_from_file_location("etf_report", _SCRIPTS_DIR / "etf_report.py")
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)

FIXTURE_PATH = _SCRIPTS_DIR.parent / "tests" / "fixtures" / "pe_percentile_parity.json"

# 起始日与步长刻意选得不像真实交易日历：这份数据只用来对齐**算法**，
# 日期在这里只是一串可比较的键。真正需要交易日语义的地方（停牌、长假）
# 由 LookbackCalculator 的 15 天陈旧窗口负责，那是另一组测试。
START = date(2014, 1, 6)
STEP_DAYS = 3


def series(count: int = 1600) -> list[tuple[date, float]]:
    """确定性序列，刻意包含三件算法上会出错的事：

    1. **重复值**——日频 PE 里估值没变的日子很常见。用 ``bisect_right``（≤）与用
       ``bisect_left``（<）在这里会算出不同的数，而只喂递增数据的测试看不出来。
    2. **超过 10 年的跨度**——这样窗口左端真的会开始淘汰旧点。跨度不足 10 年的话，
       淘汰那段代码一次都不会执行，等于没测。
    3. **明显的升降段**——保证分位不止在一个窄区间里晃。
    """
    points: list[tuple[date, float]] = []
    for i in range(count):
        if i % 7 == 0:
            value = 12.5                    # 固定重复值
        elif i % 23 == 0:
            value = points[-1][1]           # 连续重复：制造相邻同值
        else:
            # 一条缓慢的波浪 + 阶梯，幅度跨 6–40，足以让分位走过整个区间
            wave = 23 + 9 * ((i % 61) / 60 - 0.5)
            stair = -6 if (i // 150) % 2 else 6
            value = round(wave + stair + (i % 5) * 0.37, 4)
        points.append((START + timedelta(days=STEP_DAYS * i), value))
    return points


def main() -> int:
    points = series()
    percentiles = report.rolling_percentiles(points)
    assert len(points) == len(percentiles)

    # 跨度必须**超过**一个窗口，否则窗口左端永远不会淘汰旧点，
    # 两端实现里最容易写错的那一段（按日期淘汰）就一次都不会被执行——测试看着通过，
    # 其实什么都没测。第一版就是 1200 个点、跨度 9.85 年，正好踩在这个坑上。
    window_start = report.subtract_years(points[-1][0], report.CSI300_PE_WINDOW_YEARS)
    evicted = sum(1 for day, _ in points if day < window_start)
    assert evicted > 0, (
        f"序列跨度 {(points[-1][0] - points[0][0]).days / 365.25:.2f} 年不足一个窗口，"
        "窗口淘汰那段代码不会被执行——把 count 调大再生成"
    )

    span_days = (points[-1][0] - points[0][0]).days
    payload = {
        "_comment": (
            "由 automation/scripts/gen_pe_percentile_fixture.py 从 "
            "etf_report.rolling_percentiles 生成。Java 的 RollingPercentile 与它逐点对齐。"
            "不要手改这个文件；改口径请改两端实现并重新生成。"
        ),
        "windowYears": report.CSI300_PE_WINDOW_YEARS,
        "spanYears": round(span_days / 365.25, 2),
        "points": [{"date": d.isoformat(), "value": v} for d, v in points],
        # **刻意不 round**：这里曾经写成 round(p, 6)，结果制造出一个不存在的进位——
        # 真值 25.93984962406015 收成 6 位变成 25.93985，再收 4 位就上进到 25.9399，
        # 而直接收 4 位是 25.9398。两次取整夹在中间，测试报的那个「差 0.0001」是假的，
        # 修的是测试而不是算法。存原始浮点，让两边各自按自己的生产精度收一次。
        # json 的 float 是 repr 往返，读回来二进制完全相同，Python 那边可以精确比较。
        "percentiles": list(percentiles),
    }

    FIXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    # 排序键固定 + 末尾换行：这样重新生成不会产生无意义的 diff
    with open(FIXTURE_PATH, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=1, sort_keys=False)
        handle.write("\n")

    print(f"已写入 {FIXTURE_PATH}")
    print(f"  点数 {len(points)}，跨度约 {payload['spanYears']} 年，窗口 {payload['windowYears']} 年")
    print(f"  末点当日已被窗口淘汰的旧点 {evicted} 个（淘汰分支确实跑到了）")
    print(f"  分位范围 {min(percentiles):.4f} ~ {max(percentiles):.4f}")
    print(f"  首点 {points[0][0]} 分位 {percentiles[0]:.4f}；"
          f"末点 {points[-1][0]} 分位 {percentiles[-1]:.4f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())