package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.FactsSnapshot;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * P4 的唯一入口：{@link FactsSnapshot} → {@link AnalysisResult}。
 *
 * <p>这层只负责**取一次「今天」并固定下来**。四个计算器都是纯函数，同一天、
 * 同一份快照必须给出同一份结果；`today` 只影响事件窗口与新鲜度，
 * 不会渗进分数本身。
 *
 * <p>顺序不能换：分数 → 结论（封顶会改标签）→ 区间（要读覆盖率与硬约束）
 * → 情景（要读结论是否 withheld）。
 */
@Service
public class InvestmentAnalysisService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    public static final String RULES_VERSION = InvestmentScoreModel.RULES_VERSION;

    public AnalysisResult analyze(FactsSnapshot snapshot) {
        return analyze(snapshot, LocalDate.now(SHANGHAI));
    }

    /** 测试入口：固定日期，保证同一 fixture 的输出逐字段一致。 */
    public static AnalysisResult analyze(FactsSnapshot snapshot, LocalDate today) {
        ScoreResult score = InvestmentScoreModel.evaluate(snapshot, today);
        Verdict verdict = VerdictEngine.evaluate(snapshot, score, today);
        BuyZone buyZone = BuyZoneCalculator.compute(snapshot, score, today);
        return new AnalysisResult(score, verdict, buyZone,
                ScenarioGenerator.generate(snapshot, score, verdict, buyZone),
                RULES_VERSION);
    }
}
