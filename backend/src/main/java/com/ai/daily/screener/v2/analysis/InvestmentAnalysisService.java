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

    private final LocalDate fixedToday;

    public InvestmentAnalysisService() {
        this(null);
    }

    /**
     * 测试入口：固定「今天」。
     *
     * <p>夹具的日线是写死的（例如 2026-10-16）。不固定今天，真实时间往前走
     * 超过 5 个交易日后，同一份夹具会被判成 {@code PRICE_STALE}，结论被 withheld，
     * 测试就会在某一天毫无改动地开始失败。生产仍走无参构造，按上海时区取当天。
     */
    public InvestmentAnalysisService(LocalDate fixedToday) {
        this.fixedToday = fixedToday;
    }

    public AnalysisResult analyze(FactsSnapshot snapshot) {
        return analyze(snapshot, fixedToday != null ? fixedToday : LocalDate.now(SHANGHAI));
    }

    /** 测试入口：固定日期，保证同一 fixture 的输出逐字段一致。 */
    public static AnalysisResult analyze(FactsSnapshot snapshot, LocalDate today) {
        ScoreResult score = InvestmentScoreModel.evaluate(snapshot, today);
        Verdict verdict = VerdictEngine.evaluate(snapshot, score, today);
        BuyZone buyZone = BuyZoneCalculator.compute(snapshot, score, today);
        Decision decision = DecisionEngine.evaluate(snapshot, score, verdict, buyZone);
        return new AnalysisResult(score, verdict, buyZone, decision,
                ScenarioGenerator.generate(snapshot, score, verdict, buyZone),
                RULES_VERSION);
    }
}
