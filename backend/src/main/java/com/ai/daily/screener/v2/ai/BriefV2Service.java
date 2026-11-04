package com.ai.daily.screener.v2.ai;

import com.ai.daily.screener.v2.CodeLookupV2DTO;
import com.ai.daily.screener.v2.FactsSnapshot;
import com.ai.daily.screener.v2.InstrumentFactsService;
import com.ai.daily.screener.v2.analysis.AnalysisResult;
import com.ai.daily.screener.v2.analysis.BuyZone;
import com.ai.daily.screener.v2.analysis.Decision;
import com.ai.daily.screener.v2.analysis.InvestmentAnalysisService;
import com.ai.daily.screener.v2.analysis.Reason;
import com.ai.daily.screener.v2.analysis.Verdict;
import com.ai.daily.service.AiClientService;
import com.ai.daily.warehouse.WarehouseRows;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 「生成解读」的编排。**手动触发，不跟首屏一起跑**。
 *
 * <p>顺序固定：快照 → 规则分析 → 证据包 → 缓存 → 大模型 → 校验。
 * 规则分析永远先完成，LLM 只是把已算好的事实改写成中文；
 * 它挂了、被校验拒了、没配 key，首屏的分数与区间都原样可用。
 *
 * <p><b>不把「模型失败」抛成 HTTP 错误。</b>解读是第五个独立失败域：
 * 返回 200 + {@code available=false} + 真实原因，前端只灰掉这一块。
 */
@Slf4j
@Service
public class BriefV2Service {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    static final String TITLE_OK = "AI 解读（证据绑定）";
    static final String TITLE_FALLBACK = "规则解读（模型输出未通过校验）";
    static final String TITLE_UNAVAILABLE = "AI 解读（本次未生成）";
    static final String TITLE_WITHHELD = "规则解读（数据不足）";

    static final String NOTE_OK = "本段由大模型改写，只解释规则结论，不参与判定。";
    static final String NOTE_FALLBACK = "已回退到规则模板文案；规则结论、评分与参考区间不受影响。";
    static final String NOTE_NOT_CONFIGURED = "大模型解读未生成，首屏的规则结论、评分与参考区间不受影响。"
            + "若要启用：在后端配置环境变量 DEEPSEEK_API_KEY 后重启，再点一次本按钮。";
    static final String NOTE_CALL_FAILED = "大模型解读未生成，首屏的规则结论、评分与参考区间不受影响。"
            + "这一次不是「没配 key」——真没配的话这里会直接说明——"
            + "而是本次请求或返回出了问题，下面一行是服务端原话，稍后重试通常可恢复。";
    static final String NOT_CONFIGURED_PREFIX = "AI 服务暂未配置";

    private final InstrumentFactsService factsService;
    private final InvestmentAnalysisService analysisService;
    private final AnalysisCacheService cache;
    private final AiClientService aiClientService;
    private final ObjectMapper objectMapper;
    private final int maxTokens;
    private final int timeoutMs;
    private final Duration cacheTtl;

    public BriefV2Service(
            InstrumentFactsService factsService,
            InvestmentAnalysisService analysisService,
            AnalysisCacheService cache,
            AiClientService aiClientService,
            ObjectMapper objectMapper,
            @Value("${screener.analysis.brief-max-tokens:3000}") int maxTokens,
            @Value("${screener.analysis.brief-timeout-ms:25000}") int timeoutMs,
            @Value("${screener.analysis.brief-cache-seconds:1800}") long cacheSeconds) {
        this.factsService = factsService;
        this.analysisService = analysisService;
        this.cache = cache;
        this.aiClientService = aiClientService;
        this.objectMapper = objectMapper;
        this.maxTokens = maxTokens;
        this.timeoutMs = timeoutMs;
        this.cacheTtl = Duration.ofSeconds(Math.max(60, cacheSeconds));
    }

    public CodeLookupV2DTO.BriefView brief(String rawCode, String requestedSnapshotId) {
        FactsSnapshot snapshot = factsService.snapshot(rawCode);
        AnalysisResult analysis = analysisService.analyze(snapshot);
        boolean changed = requestedSnapshotId != null && !requestedSnapshotId.isBlank()
                && !requestedSnapshotId.equals(snapshot.snapshotId());
        LocalDateTime now = LocalDateTime.now(SHANGHAI);

        if (analysis.verdict().withheld()) {
            return view(snapshot, changed, false, false, false, TITLE_WITHHELD,
                    List.of(), "数据不足，暂不评级，无法生成解读："
                            + analysis.verdict().withheldReason(),
                    "先补齐核心数据后重试；首屏仍会显示缺什么、为什么缺。", now);
        }

        EvidencePack pack = EvidencePackBuilder.build(snapshot, analysis);
        String instrumentCode = snapshot.instrument().key();
        Optional<WarehouseRows.CachedAnalysis> cached = cache.get(instrumentCode,
                snapshot.snapshotId(), analysis.rulesVersion(),
                AnalysisPromptV2.VERSION, now);
        if (cached.isPresent()) {
            CodeLookupV2DTO.BriefView hit = parseCached(cached.get().payloadJson(), changed);
            if (hit != null) {
                return hit;
            }
        }

        String raw = aiClientService.chat(
                List.of(new AiClientService.AiMessage("system", AnalysisPromptV2.system()),
                        new AiClientService.AiMessage("user", AnalysisPromptV2.user(pack))),
                0.2, maxTokens, timeoutMs);

        if (isFailure(raw)) {
            String reason = raw == null || raw.isBlank() ? "AI 未返回内容。" : raw.strip();
            log.warn("代码查询 v2 解读未生成 code={} reason={}", snapshot.instrument().key(), reason);
            return view(snapshot, changed, false, false, false, TITLE_UNAVAILABLE,
                    List.of(), reason, noteFor(reason), now);
        }

        AiAnalysisValidator.Validation validation = AiAnalysisValidator.validate(raw, pack);
        if (!validation.ok()) {
            log.warn("代码查询 v2 解读未通过校验 code={} reason={}",
                    snapshot.instrument().key(), validation.reason());
            return view(snapshot, changed, false, true, false, TITLE_FALLBACK,
                    fallbackSections(analysis, pack),
                    "模型输出未通过校验：" + validation.reason(),
                    NOTE_FALLBACK, now);
        }

        CodeLookupV2DTO.BriefView view = view(snapshot, changed, true, false, false,
                TITLE_OK, sections(validation.sections()), null, NOTE_OK, now);
        store(instrumentCode, snapshot.snapshotId(), analysis.rulesVersion(), view, now);
        return view;
    }

    // ------------------------------------------------------------------
    // 缓存
    // ------------------------------------------------------------------

    private void store(String instrumentCode, String snapshotId, String rulesVersion,
                       CodeLookupV2DTO.BriefView view, LocalDateTime now) {
        try {
            String payload = objectMapper.writeValueAsString(view);
            cache.put(instrumentCode, snapshotId, rulesVersion,
                    AnalysisPromptV2.VERSION, payload, cacheTtl, now);
        } catch (Exception e) {
            // 序列化失败不改变这一次的返回；只是下一次还要再问一遍模型。
            log.warn("序列化解读缓存失败 instrument={}：{}", instrumentCode, e.getMessage());
        }
    }

    /** 缓存内容坏了就当没命中：结构变了、手工改过表，都不该让端点 500。 */
    private CodeLookupV2DTO.BriefView parseCached(String payload, boolean changed) {
        try {
            CodeLookupV2DTO.BriefView hit = objectMapper.readValue(
                    payload, CodeLookupV2DTO.BriefView.class);
            return new CodeLookupV2DTO.BriefView(hit.code(), hit.name(), hit.snapshotId(),
                    changed, hit.available(), hit.fallback(), true, hit.title(),
                    hit.sections(), hit.reason(), hit.note(), hit.rulesVersion(),
                    hit.promptVersion(), hit.generatedAt(), hit.disclaimer());
        } catch (Exception e) {
            log.warn("解读缓存内容无法解析，按未命中处理：{}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 失败与回退
    // ------------------------------------------------------------------

    /**
     * {@link AiClientService} 失败时返回一句以 {@code AI } 开头的中文文案。
     * 成功的输出一定是 JSON，所以额外要求「不含 {」才判失败，
     * 避免模型以「AI ...」开头、后面跟着 JSON 时被误杀。
     */
    static boolean isFailure(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String head = raw.stripLeading();
        return head.startsWith("AI ") && !head.contains("{");
    }

    static String noteFor(String reason) {
        return reason != null && reason.stripLeading().startsWith(NOT_CONFIGURED_PREFIX)
                ? NOTE_NOT_CONFIGURED : NOTE_CALL_FAILED;
    }

    /**
     * 校验失败时的规则文案。**只用规则引擎已经给出的结论与依据**，
     * 不再生成任何新判断；引用编号也只取证据包里真实存在的那些。
     */
    static List<CodeLookupV2DTO.BriefSectionView> fallbackSections(
            AnalysisResult analysis, EvidencePack pack) {
        Verdict verdict = analysis.verdict();
        List<CodeLookupV2DTO.BriefSectionView> sections = new ArrayList<>();

        StringBuilder conclusion = new StringBuilder()
                .append("当前规则结论为「").append(verdict.label()).append("」。");
        if (verdict.score() != null) {
            conclusion.append("综合得分 ").append(plain(verdict.score()));
            if (verdict.scoreLow() != null && verdict.scoreHigh() != null) {
                conclusion.append("（区间 ").append(plain(verdict.scoreLow()))
                        .append("–").append(plain(verdict.scoreHigh())).append("）");
            }
            conclusion.append("。");
        }
        if (verdict.coverage() != null) {
            conclusion.append("数据覆盖率 ").append(plain(verdict.coverage())).append("。");
        }
        sections.add(new CodeLookupV2DTO.BriefSectionView("规则结论", conclusion.toString(),
                filterIds(pack, List.of("ev:verdict:score", "ev:verdict:score_low",
                        "ev:verdict:score_high", "ev:verdict:coverage"))));

        Decision decision = analysis.decision();
        if (decision != null) {
            String decisionText = "位置：" + decision.position().label()
                    + "；时机：" + decision.timing().label()
                    + "；行动：" + decision.action().label()
                    + "；周期：" + decision.horizon().label() + "。"
                    + decision.horizon().summary();
            sections.add(new CodeLookupV2DTO.BriefSectionView("位置 / 时机 / 行动 / 周期",
                    decisionText,
                    filterIds(pack, List.of("ev:decision:position", "ev:decision:timing",
                            "ev:decision:action", "ev:decision:horizon"))));
        }

        List<Reason> reasons = verdict.reasons();
        String reasonText = reasons.isEmpty()
                ? "当前没有达到展示阈值的正向依据，需等待数据进一步确认。"
                : joinReasons(reasons);
        sections.add(new CodeLookupV2DTO.BriefSectionView("主要依据", reasonText,
                reasonIds(pack, reasons, "ev:verdict:score")));

        List<Reason> risks = new ArrayList<>(verdict.risks());
        BuyZone zone = analysis.buyZone();
        if (zone != null) {
            for (Reason basis : zone.basis()) {
                risks.add(basis);
            }
        }
        String riskText = risks.isEmpty()
                ? "未触发硬约束；仍需观察估值分位与趋势是否同向。"
                : joinReasons(risks);
        if (zone != null && !zone.notes().isEmpty()) {
            riskText = riskText + " " + zone.notes().get(0);
        }
        sections.add(new CodeLookupV2DTO.BriefSectionView("风险与观察", riskText,
                reasonIds(pack, risks, "ev:verdict:score")));
        return sections;
    }

    private static String joinReasons(List<Reason> reasons) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Reason reason : reasons) {
            if (reason.text() == null || reason.text().isBlank()) {
                continue;
            }
            if (count >= 3) {
                break;
            }
            if (count > 0) {
                out.append(' ');
            }
            out.append(reason.text().strip());
            count++;
        }
        return out.length() == 0 ? "暂无可展示的依据。" : out.toString();
    }

    private static List<String> reasonIds(EvidencePack pack, List<Reason> reasons,
                                          String fallback) {
        Set<String> ids = new LinkedHashSet<>();
        for (Reason reason : reasons) {
            ids.addAll(reason.evidenceIds());
        }
        List<String> filtered = filterIds(pack, new ArrayList<>(ids));
        return filtered.isEmpty() ? filterIds(pack, List.of(fallback)) : filtered;
    }

    private static List<String> filterIds(EvidencePack pack, List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (pack.contains(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------

    private static List<CodeLookupV2DTO.BriefSectionView> sections(
            List<AiAnalysisValidator.Section> sections) {
        List<CodeLookupV2DTO.BriefSectionView> out = new ArrayList<>();
        for (AiAnalysisValidator.Section section : sections) {
            out.add(new CodeLookupV2DTO.BriefSectionView(
                    section.heading(), section.text(), section.evidenceIds()));
        }
        return List.copyOf(out);
    }

    private static CodeLookupV2DTO.BriefView view(
            FactsSnapshot snapshot, boolean changed, boolean available, boolean fallback,
            boolean cached, String title, List<CodeLookupV2DTO.BriefSectionView> sections,
            String reason, String note, LocalDateTime now) {
        return new CodeLookupV2DTO.BriefView(
                snapshot.instrument().rawCode(), snapshot.identity().name(),
                snapshot.snapshotId(), changed, available, fallback, cached, title,
                sections, reason, note, InvestmentAnalysisService.RULES_VERSION,
                AnalysisPromptV2.VERSION, now.toString(), CodeLookupV2DTO.DISCLAIMER);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
