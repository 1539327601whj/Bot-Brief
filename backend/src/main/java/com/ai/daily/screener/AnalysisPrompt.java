package com.ai.daily.screener;

import java.util.List;

/**
 * 「深度解读」的提示词拼装。**纯函数，没有网络也没有 Spring**，所以它可以单测。
 *
 * <h2>喂结构化材料块，不喂 DTO 的 JSON</h2>
 *
 * <p>把 {@link CodeLookupAnalysisDTO} 序列化成 JSON 丢给模型，等于要求它自己从字段名里
 * 猜每个数是什么意思、哪个是判定哪个是原始值。那样出来的解读必然要重算一遍，
 * 而**算数必须只有一处**（章程 §9.1）：模型算的数没人能复核，本地的数能。
 * 所以这里把「已经算好的结论」和「原始材料」分成两块明写出来，
 * 并逐条声明哪一块是只能复述的。
 *
 * <h2>缺什么要明说</h2>
 *
 * <p>材料里没有的数字，模型最容易用常识补一个（「光伏行业平均 PE 约 25 倍」）。
 * 那样出来的解读看起来完整、读起来可信，而**一个数字都复核不了**。
 * 所以缺口清单是材料的一部分，不是可选项。
 */
final class AnalysisPrompt {

    private AnalysisPrompt() {
    }

    /**
     * 系统提示词。每一条都对应一种「不写就会发生」的具体失败：
     *
     * <ul>
     *   <li>不写「只能用材料里的数字」→ 模型补常识数字，无法复核；</li>
     *   <li>不写「禁止预测」→ 章程 §7 明确不许推断未来价格/业绩，而模型的默认行为正是预测；</li>
     *   <li>不写「事实与推断分开、推断句以『推断：』开头」→ 两者混在一段里，
     *       读的人无法判断哪句是可核的；</li>
     *   <li>不写「规则判定只能复述」→ 模型会「综合来看其实更乐观」，把本地算的结论推翻，
     *       而页面上两张卡会互相矛盾；</li>
     *   <li>不写「缺了就写材料未提供」→ 缺口被「一般来说」填上；</li>
     *   <li>不写小节固定 → 输出结构每次不同，前端没法稳定渲染，也没法对比两次结果。</li>
     * </ul>
     */
    static String system() {
        return """
                你是一名严谨的证券市场资料整理助手。你会收到一份本地已经算好的结构化材料，\
                你的任务是把材料**如实整理成可读的说明**，不是自己分析行情。

                必须遵守：
                1. 只能使用材料里出现的数字与文字。材料里没有的数字、事实、公司信息一律不得出现，\
                也不得用行业常识或记忆补全。
                2. 任何数字都要带上它的出处与数据日期。材料里每个数字都来自同一个数据时刻，\
                引用时写明这个时刻；材料里标了「未确认」「未取到」的，照原样说，不要绕开。
                3. 禁止预测未来股价、涨跌、业绩，禁止给出目标价、买卖建议、仓位建议。\
                用户若需要「未来怎么走」，只能以「需要人工核实的问题」形式给出。
                4. 必须把**事实**与**推断**分开。推断句一律以「推断：」开头，且推断只能来自材料里的事实。
                5. 材料里的【规则判定】是本地程序算好的结论，**只能复述，不得改动、不得重新打分、\
                不得给出与之相反的总体判断**。
                6. 材料里没有的内容写「材料未提供」，不要用一般情况代替。
                7. 严格按下面的小节输出 Markdown，一节都不能少；某节没有内容就写「无」。
                   ## 一句话结论
                   ## 各维度怎么摆
                   ## 主要依据
                   ## 反面风险
                   ## 需要你人工核实
                8. 语气克制，不煽动，不承诺。全文不超过 800 字。""";
    }

    /**
     * 材料正文。
     *
     * @param maxChars 上限，超了**从尾部截断并留下明确标记**。截断标记不是客套：
     *                 不写的话模型会把「材料到此为止」当成「材料就这么多」，
     *                 而缺的那几路源恰恰是它最该说「材料未提供」的地方
     */
    static String material(CodeLookupAnalysisDTO dto, NewsDigestService.Digest digest, int maxChars) {
        StringBuilder sb = new StringBuilder(2048);

        sb.append("【标的】").append(dto.code());
        if (dto.name() != null && !dto.name().isBlank()) {
            sb.append(' ').append(dto.name());
        }
        sb.append("（").append(dto.kindLabel() == null ? "类型未确认" : dto.kindLabel()).append("）\n");

        sb.append("【数据时刻】").append(dto.snapshotAt())
                .append("（").append(dto.fromCache() ? "取自进程内缓存，不是现在重新取的" : "本次实时取到")
                .append("）。**下列所有数字都来自这一刻，没有更新的一份。**\n");

        if (!dto.notes().isEmpty()) {
            sb.append("【口径声明】\n");
            for (String note : dto.notes()) {
                sb.append("- ").append(note).append('\n');
            }
        }

        sb.append("\n【规则判定（本地程序算好的，只能复述，不许改动）】\n");
        appendDimensions(sb, dto.dimensions());

        sb.append("\n").append(tendencyLine(dto.tendency())).append('\n');
        for (String reason : dto.tendency().reasons()) {
            sb.append("  · ").append(reason).append('\n');
        }
        if (!dto.tendency().excluded().isEmpty()) {
            sb.append("  未计入本次倾向：").append(String.join("；", dto.tendency().excluded())).append('\n');
        }

        sb.append("\n【排雷体检】");
        if (!dto.checkup().applicable()) {
            sb.append("不适用。").append(nullToEmpty(dto.checkup().note())).append('\n');
        } else {
            long hits = dto.checkup().items().stream().filter(AnalysisRules.CheckupItem::hit).count();
            sb.append("共 ").append(dto.checkup().items().size()).append(" 条适用，命中 ")
                    .append(hits).append(" 条。\n");
            for (AnalysisRules.CheckupItem item : dto.checkup().items()) {
                if (item.hit()) {
                    sb.append("  · [命中] ").append(item.rule()).append(' ')
                            .append(item.title()).append('：').append(item.detail()).append('\n');
                }
            }
            if (hits == 0) {
                sb.append("  · 没有命中项。**这不代表没问题**：只代表这些门槛都没越过。\n");
            }
        }
        if (!dto.checkup().hardVetoes().isEmpty()) {
            sb.append("  硬否决：").append(String.join("；", dto.checkup().hardVetoes())).append('\n');
        }

        // 缺口清单。分两部分：规则层没取到的格，与新闻层各源实际回了什么。
        sb.append("\n【缺口清单：本次哪些内容没取到】\n");
        List<String> missing = missingMetrics(dto.dimensions());
        if (missing.isEmpty()) {
            sb.append("- 规则判定用到的格全部取到了。\n");
        } else {
            sb.append("- 规则判定有 ").append(missing.size()).append(" 格没取到：\n");
            for (String line : missing) {
                sb.append("  · ").append(line).append('\n');
            }
        }
        // 按 Category 的声明顺序遍历，不遍历 Map 的键集：EnumMap 的顺序恰好对，
        // 但换成别的实现就不一定了，而材料里「公告 / 新闻 / 政策」的次序乱跳没有理由。
        for (NewsItem.Category category : NewsItem.Category.values()) {
            String note = digest.noteOf(category);
            if (!note.isEmpty()) {
                sb.append("- ").append(note).append('\n');
            }
        }
        if (!dto.pendingChecks().isEmpty()) {
            sb.append("- 需要人工核实（材料本身给不出答案）：\n");
            for (String check : dto.pendingChecks()) {
                sb.append("  · ").append(check).append('\n');
            }
        }

        appendNews(sb, "公告", digest.notices());
        appendNews(sb, "新闻", digest.news());
        appendNews(sb, "政策", digest.policies());

        return truncate(sb.toString(), maxChars);
    }

    private static void appendDimensions(StringBuilder sb, List<AnalysisRules.DimensionCard> dimensions) {
        for (AnalysisRules.DimensionCard card : dimensions) {
            sb.append("- ").append(card.label()).append(" [").append(card.stance().label()).append(']');
            if (!card.counts()) {
                sb.append("（未计入倾向）");
            }
            sb.append('\n');
            for (AnalysisRules.Metric metric : card.metrics()) {
                sb.append("  · ").append(metric.label()).append(" = ")
                        .append(metric.display() == null ? "未取到" : metric.display())
                        .append("（").append(metric.stance().label()).append("）：")
                        .append(metric.basis()).append('\n');
            }
            for (String reason : card.reasons()) {
                sb.append("  · 说明：").append(reason).append('\n');
            }
        }
    }

    /** 「现有 N 条，最近一条是…」——**逐条给标题、来源、日期**，不给正文（章程 §9.10）。 */
    private static void appendNews(StringBuilder sb, String label, List<NewsItem> items) {
        sb.append("\n【").append(label).append("】");
        if (items.isEmpty()) {
            // 「本次没有取到条目」与「源挂了」在这一层已经分不出来了（digest 里 notes 说清了），
            // 所以这句只说事实：这一组没东西可列。
            sb.append("本次没有可列出的条目（原因见上面的缺口清单）。\n");
            return;
        }
        sb.append("共 ").append(items.size()).append(" 条：\n");
        for (NewsItem item : items) {
            sb.append("- ").append(item.publishedOn()).append(' ')
                    .append(item.source() == null ? item.originLabel() : item.source())
                    .append('：').append(item.title());
            if (item.noticeCategory() != null) {
                sb.append("（分类：").append(item.noticeCategory()).append("）");
            }
            sb.append('\n');
        }
    }

    private static String tendencyLine(AnalysisRules.TendencyVerdict verdict) {
        return "【倾向（本地规则算出，不许推翻）】" + verdict.tendency().label()
                + "（净分 " + (verdict.netScore() >= 0 ? "+" : "") + verdict.netScore()
                + "，参判维度 " + verdict.countedDimensions() + "）";
    }

    /**
     * 没取到值的格子。**只列「本该有值但没有」的**：{@code NOT_APPLICABLE} 是「这类标的
     * 本来就没有这一维」（指数没有财报），把它算进缺口会让模型以为材料残缺，
     * 进而对着一只指数写「缺少财务数据」——那是错的，指数字段本来就该缺。
     */
    private static List<String> missingMetrics(List<AnalysisRules.DimensionCard> dimensions) {
        return dimensions.stream()
                .flatMap(card -> card.metrics().stream()
                        .filter(m -> m.stance() == AnalysisRules.Stance.INSUFFICIENT)
                        .map(m -> card.label() + " · " + m.label() + "：" + m.basis()))
                .toList();
    }

    /**
     * 超长就截断，并把「被截了」写进材料本身。
     *
     * <p>上限是**硬上限**：调用方是照着字符数折算 token 预算的，超出去的部分会真的花掉钱。
     * 标记本身也可能比上限长（上限可以配得很小），那种情况下保标记不如保上限——
     * 上限被破，预算就是假的；而标记退化成一句短提示，至少还说明「后面还有东西没给你」。
     */
    static String truncate(String text, int maxChars) {
        int limit = Math.max(0, maxChars);
        if (text.length() <= limit) {
            return text;
        }
        String marker = "\n\n【材料已截断】以下内容未包含在本次材料中，凡是这里没有的，"
                + "一律按「材料未提供」处理，不得用常识补全。";
        if (marker.length() >= limit) {
            String shortMarker = "【材料已截断】";
            if (shortMarker.length() >= limit) {
                return text.substring(0, limit);
            }
            return text.substring(0, limit - shortMarker.length()) + shortMarker;
        }
        int keep = limit - marker.length();
        return text.substring(0, keep) + marker;
    }

    private static String nullToEmpty(String raw) {
        return raw == null ? "" : raw;
    }
}
