package com.ai.daily.screener;

import java.util.List;

/**
 * 「代码查询 · 深度分析」的返回体。
 *
 * <p><b>它自己不含任何计算</b>：每个字段都是 {@link AnalysisRules} 算完的结果原样端出来。
 * 这样「数字在哪儿算的」永远有唯一答案——本地规则算，不是大模型算（章程 §9.1、§9.6）。
 *
 * <p><b>和主查询 {@link CodeLookupDTO} 是两个端点、两份返回体</b>，不是一份的两个区块。
 * 原因是失败域必须分开：主查询的进程内缓存**只缓存未降级的结果**，把新闻这类高频独立失败域
 * 合进主链，任何一次新闻源抽风都会把整条链标成降级，于是每次点击都重新外呼行情源
 * （章程 §9.7）。所以这里额外带上 {@code snapshotAt} 与 {@code fromCache}——
 * 页面要能说明**这份判定是建在哪一时刻的数字上**的（章程 §8）。
 *
 * @param snapshotAt 底层数字的取数时刻，**直接抄自主查询的同一份快照**，不是这个端点自己的时刻
 * @param fromCache  底层数字是否取自主查询的 300 秒进程内缓存
 * @param notes      口径声明。**必须显示**：§9.2 那条「绝对口径，不可与低估精选的池内分位互相印证」
 *                   就靠它端到页面
 * @param dimensions 维度卡。含 {@code NOT_APPLICABLE} 的那些——它们**不渲染成判定卡**，
 *                   但要在「未计入」里留痕（章程 §6 第 4 条）
 * @param tendency   倾向标签。**由规则算出**，大模型只能复述
 * @param disclaimer 免责声明，逐字取自章程 §9.13。放在返回体里而不是写死在页面上，
 *                   是为了让它没法被前端顺手改掉
 */
public record CodeLookupAnalysisDTO(
        String code,
        String resolvedCode,
        String name,
        String kind,
        String kindLabel,
        String snapshotAt,
        boolean fromCache,
        List<String> notes,
        List<AnalysisRules.DimensionCard> dimensions,
        AnalysisRules.Checkup checkup,
        AnalysisRules.TendencyVerdict tendency,
        List<String> pendingChecks,
        List<NewsGroupView> news,
        String disclaimer
) {

    /**
     * 新闻面的**一组**（公告 / 新闻 / 政策各一组）。
     *
     * <p><b>为什么按组给状态，而不是给一个总的成功与否</b>：这三组是三个独立失败域
     * （章程 §9.7）。给一个总状态的话，「公告源挂了」和「最近没有公告」在页面上无法区分——
     * 而这两件事的处置完全相反：前者要去看源，后者是正常结果。
     *
     * <p>{@code EMPTY} 与 {@code FAILED} 必须分开的理由，在 {@link NewsSourceResult}
     * 的注释里写得更细：源回了 20 条、17 条缺日期被丢掉，那是**解析坏了**，
     * 不是「最近没有」。
     *
     * @param category 与 {@link NewsItem.Category#name()} 一致，前端据此选图标与排序
     * @param label    页面写法（「公告」/「新闻」/「政策」）
     * @param items    已清洗、已去重、已按重要性排序并截断的条目。<b>只有标题+来源+日期+链接</b>
     * @param state    这一组的状态。**决定这张卡是正常渲染还是变灰**
     * @param note     这一组的口径说明：源回了几条、丢弃各多少、为什么。**必须显示**——
     *                 没有它，「本次取到 2 条」就说不清是源少还是被丢掉了
     */
    public record NewsGroupView(String category, String label, List<NewsItem> items,
                                State state, String note) {

        public enum State {
            /** 有条目。 */
            OK,
            /** 源正常回了，但这一组确实没有条目。**不是失败。** */
            EMPTY,
            /** 这一路失败了或没去取。卡片变灰并显示 {@code note}。 */
            FAILED
        }
    }

    /** 章程 §9.13，逐字。改这句话要同时改章程。 */
    public static final String DISCLAIMER =
            "本页仅为公开数据的规则化整理结果，不构成投资建议、证券推荐或买卖依据。";

    /** §9.2 那条声明。**每次都要随结果给出去**，不许只在章程里写着。 */
    public static final String CALIBER_ABSOLUTE =
            "本页的阈值判定是绝对口径（绝对阈值带），与「低估精选」的池内相对分位不可互相印证。";

    /** 判定按哪一档阈值跑。本页没有档位输入，取严的一档，并且要说出来。 */
    public static final String CALIBER_CHECKUP_BUCKET =
            "排雷体检按章程 §3 的稳健档阈值执行（本页没有档位输入，取严的一档）。";
}
