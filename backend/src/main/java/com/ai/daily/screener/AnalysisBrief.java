package com.ai.daily.screener;

/**
 * 「深度解读」这一段的结果。**大模型是第五个独立失败域**（章程 §9.7）：
 * 它不可用时，页面上其余四块（判定卡、体检、新闻、人工核实）原样都在，
 * 只有这一块换成一句「未启用」和一段配置说明。
 *
 * <h2>怎么判断「模型没成功」</h2>
 *
 * <p>{@code com.ai.daily.service.impl.AiClientServiceImpl} **失败时不抛异常**，
 * 而是返回一句以 {@code AI } 开头的中文文案（这是它既有的约定，对话与内容生成也在用）。
 * 所以这里只能按前缀认。
 *
 * <p><b>为什么不按具体句子认</b>：那要求两处文案永远同步，改一个字就静默失效——
 * 失效的表现是「AI 调用失败：xxx」被当成解读正文渲染出来，看起来像模型说的话。
 *
 * <p><b>为什么还要加一条「不以 # 开头」</b>：单看前缀有假阳性——模型真写
 * 「AI 时代的电源设备…」就会被误判成失败。而 {@link AnalysisPrompt#system()} 第 7 条
 * 强制输出以 {@code ##} 小节开头，所以**真解读一定以 {@code #} 开头**。
 * 两条合起来：以 {@code AI } 开头且不以 {@code #} 开头的才算失败。
 * 剩下唯一的误判是「模型既不听格式又恰好以 AI 开头」，那种输出本来也是废的。
 */
public record AnalysisBrief(boolean available, String title, String text, String note) {

    /** {@code AiClientServiceImpl} 失败文案的统一前缀。 */
    static final String FAILURE_PREFIX = "AI ";

    static final String TITLE_OK = "大模型解读";
    static final String TITLE_UNAVAILABLE = "综合研判（大模型未启用）";

    /**
     * 「<b>没有配 key</b>」时正文顶上那句。只有这一种失败需要用户去改配置。
     *
     * <p>不能只写「未启用」——要给出可操作的下一步。
     */
    static final String NOTE_NOT_CONFIGURED = "大模型解读未生成，上面的规则判定与新闻列表不受影响，"
            + "倾向结论仍以「规则判定」那一栏为准。"
            + "若要启用：在后端配置环境变量 DEEPSEEK_API_KEY 后重启，再点一次本按钮。";

    /**
     * 「<b>配了 key，但这一次调用/返回出了问题</b>」时用那句。
     *
     * <p><b>这两句不能共用一句。</b> 共用会出现自相矛盾的一段话：上面刚让用户
     * 「配置 DEEPSEEK_API_KEY 后重启」，紧接着下面一行就是「AI 暂未返回内容」——
     * 而 key 其实早就配好了（真没配的话 {@code AiClientServiceImpl} 在第一行就返回
     * 「服务暂未配置」，根本走不到「返回空内容」这一步）。用户照着去做一遍，什么都不会变，
     * 还会以为自己配错了。
     *
     * <p>所以这句只讲「这不是配置问题、紧接着的那行才是线索」。至于那行原文说的是
     * 401 还是空正文，由客户端给的那句话自己讲，这里不替它猜。
     *
     * <p><b>不要在这里用 markdown 记号</b>（星号、井号）：这个 note 由前端当纯文本渲染
     * （{@code CodeLookup.tsx} 的 {@code stockpick-note}），记号会照原样带星号显示出来。
     */
    static final String NOTE_CALL_FAILED = "大模型解读未生成，上面的规则判定与新闻列表不受影响，"
            + "倾向结论仍以「规则判定」那一栏为准。"
            + "这一次不是「没配 key」——真没配的话这里会直接这么说——"
            + "而是这一次请求或返回出了问题，紧接着下面那一行是服务端原话，"
            + "过一会儿再点一次本按钮通常就好。";

    /** 「没配 key」那句的开头，用来与「配了但调用失败」区分。 */
    static final String NOT_CONFIGURED_PREFIX = "AI 服务暂未配置";

    /** 一次成功的解读。 */
    static AnalysisBrief ok(String raw) {
        return new AnalysisBrief(true, TITLE_OK, raw.strip(), null);
    }

    /**
     * 一段没生成的解读。
     *
     * <p>{@code reason} 不是「给排查用的」——它就是那句要显示给用户看的原因原文，
     * 而 {@code note} 是要放在它上面、告诉他下一步做什么的那句。两者按失败种类配对，
     * 见 {@link #noteFor(String)}。
     */
    static AnalysisBrief unavailable(String reason) {
        return new AnalysisBrief(false, TITLE_UNAVAILABLE, reason, noteFor(reason));
    }

    /**
     * 按原因挑那句说明。
     *
     * <p>分岔点只有一个：**是不是「没配 key」**。这是唯一一种「用户去改配置就能好」的失败，
     * 其余几种（超时、401、返回空正文、模型名不对）都只能重试或者去看日志。
     * 把配置步骤写给后面这几种，等于给了一条走不通的路。
     */
    static String noteFor(String reason) {
        return reason != null && reason.stripLeading().startsWith(NOT_CONFIGURED_PREFIX)
                ? NOTE_NOT_CONFIGURED
                : NOTE_CALL_FAILED;
    }

    /**
     * 这段文本是不是一句失败文案。
     *
     * @see AnalysisBrief 类注释里对这两条判据的说明
     */
    static boolean isFailureText(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String head = raw.stripLeading();
        return head.startsWith(FAILURE_PREFIX) && !head.startsWith("#");
    }

    /**
     * 把 {@code AiClientServiceImpl} 的返回值变成这一段的结果。
     *
     * <p>失败时 {@code text} 里放的是那句原始文案——**它本身就是要显示给管理员看的信息**
     * （「未配置」和「调用失败」的处置完全不同），只是不能冒充解读正文，
     * 所以由 {@code available} 与 {@code title} 把两者分开。
     */
    static AnalysisBrief fromAi(String raw) {
        if (isFailureText(raw)) {
            return unavailable(raw == null || raw.isBlank() ? "AI 未返回内容。" : raw.strip());
        }
        return ok(raw);
    }
}
