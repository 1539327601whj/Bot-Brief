package com.ai.daily.screener;

/**
 * 「生成深度解读」的返回体。**与 {@link CodeLookupAnalysisDTO} 是两个端点**。
 *
 * <p>分开的理由是这四件事：大模型那一次调用几秒到几十秒、要花钱、未配 key 时永远空着、
 * 而它失败一次不该让整页重来。合成一个端点的话，用户每次刷新判定卡都要连带触发一次
 * 大模型调用——那是拿钱换一次没人要的重复生成。
 *
 * <p>{@code snapshotAt} 与 {@code fromCache} **照抄那一份底层快照**，不是这个端点自己的时刻：
 * 解读里的每个数字都来自那份快照，前端要能核出「这份解读对应的数字是哪一刻的」，
 * 以及「它和我手上那份判定卡是不是同一份快照」。**这是能看出解读过期的唯一途径**——
 * 快照变了，解读里的数字就与页面上的对不上了（章程 §8）。
 *
 * @param brief 解读本身。{@code available == false} 时 {@code text} 放的是失败原因原文，
 *              它是要显示给管理员的排查信息，**不能当成解读正文渲染**
 */
public record AnalysisBriefDTO(
        String code,
        String resolvedCode,
        String name,
        String snapshotAt,
        boolean fromCache,
        AnalysisBrief brief
) {
}
