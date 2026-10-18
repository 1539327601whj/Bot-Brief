package com.ai.daily.screener.v2.ai;

/**
 * 代码查询 v2 的解读提示词。
 *
 * <p><b>提示词版本是缓存键的一部分。</b>改一句约束就必须换版本号，否则旧缓存会
 * 继续按旧约束产出文案，而校验器按新约束拒绝它——表现为「明明刚改过，还是一直回退」。
 */
public final class AnalysisPromptV2 {

    /** 改提示词时同步 +1；缓存键取这个字符串。 */
    public static final String VERSION = "code-lookup-v2-prompt-1";

    private AnalysisPromptV2() {
    }

    public static String system() {
        return """
                你是投资数据整理助手。你只解释已经算好的规则结论，不做新的判断。

                硬性要求：
                1. 只允许使用用户消息里列出的 evidence 条目，不得引入外部知识、传闻或记忆。
                2. 正文里的每一个数字都必须能在某条 evidence 的 label / display / 值里找到；
                   不要做单位换算后再写一个新数字，不要写日期区间。
                3. 每一条 evidenceId 必须逐字来自用户消息，不得自行编写。
                4. 只输出严格 JSON，不要 markdown 代码块，不要任何解释性前后缀：
                   {"sections":[{"heading":"小节标题","text":"正文","evidenceIds":["ev:..."]}]}
                5. 输出 3 到 5 个小节；每节 1 到 3 句中文；每节至少引用一个 evidenceId。
                6. 不得出现：必涨、稳赚、保证收益、目标价、建议满仓、立即买入、推荐股票、
                   满仓、抄底；不得写概率、胜率、目标位、涨到/跌到某个数字。
                7. 条件推演只能写成「如果……则……」的观察条件，不能写成预测。
                8. 不要复述免责声明；页面会单独展示。
                """;
    }

    public static String user(EvidencePack pack) {
        return pack.material() + """

                请基于以上 evidence 输出 JSON。可以概括，但不得出现材料之外的数字或编号。
                """;
    }
}
