package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnalysisBrief} 的降级判据。
 *
 * <p>这一段是整个功能里**唯一一处按字符串内容做判断**的地方，所以它值得单独测：
 * 认错了不会报错，只会让一句「AI 调用失败：xxx」被当成模型的解读正文排版出来——
 * 看起来像是模型说的话，而且**没有任何迹象表明它是失败的**。
 */
class AnalysisBriefTest {

    // ------------------------------------------------------------------
    // 认得出失败
    // ------------------------------------------------------------------

    @Test
    void everyFailureStringAiClientActuallyReturnsIsRecognised() {
        // 这些是 AiClientServiceImpl **实际会返回**的全部失败文案（不是照着文档抄的，
        // 是那个类的所有 `return "AI 开头` 分支）。漏了任何一句，那一句就会漏成正文。
        assertThat(AnalysisBrief.isFailureText("AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。")).isTrue();
        assertThat(AnalysisBrief.isFailureText("AI 调用失败：Connection timed out")).isTrue();
        assertThat(AnalysisBrief.isFailureText("AI 暂未返回内容，请稍后重试。")).isTrue();
        // 正文空白的另外两种成因（截断 / 只回了思考）现在是分开报的，同样是「AI 」开头。
        assertThat(AnalysisBrief.isFailureText("AI 输出被 max_tokens 截断了，正文没留下（本次上限 4000）")).isTrue();
        assertThat(AnalysisBrief.isFailureText("AI 只返回了思考过程、没有正文")).isTrue();
        // 「返回里没有 choices」那一句会把响应原文接在末尾，原文里可能有 `#` 或换行。
        // 判据先 stripLeading 再看开头，所以拼接**不会**把这一句带偏——仍然以「AI 」起头。
        // 这一条钉的就是这个：往失败文案后面接任意原文，都不该让它变成「真解读」。
        assertThat(AnalysisBrief.isFailureText(
                "AI 返回里没有 choices（模型名 deepseek-v4-next 可能不对）：{\"choices\":[]}")).isTrue();
    }

    @Test
    void leadingWhitespaceDoesNotHideAFailure() {
        // 前缀判据如果写成 startsWith 而不先 stripLeading，一句前面多打了空格的失败文案
        // 就会漏过去。AI 返回的文本里带前导换行是常见的。
        assertThat(AnalysisBrief.isFailureText("\n\n  AI 调用失败：boom")).isTrue();
    }

    @Test
    void nothingAtAllCountsAsAFailure() {
        assertThat(AnalysisBrief.isFailureText(null)).isTrue();
        assertThat(AnalysisBrief.isFailureText("")).isTrue();
        assertThat(AnalysisBrief.isFailureText("   ")).isTrue();
    }

    // ------------------------------------------------------------------
    // 不误伤真解读
    // ------------------------------------------------------------------

    @Test
    void aRealBriefIsNeverMistakenForAFailure() {
        // 系统提示词强制输出以 `##` 小节开头，所以真解读一定以 `#` 起头。
        // 这一条是「只看前缀」那版判据的补丁：`AI ` 前缀单独用会误伤
        // 一句恰好以「AI 」开头的真解读（比如「AI 时代的电源设备…」）。
        assertThat(AnalysisBrief.isFailureText(
                "## 一句话结论\n当前数据呈现为偏正面。\n## 主要依据\n- PE 18")).isFalse();
    }

    @Test
    void aBriefThatStartsWithTheWordAiButNotTheFailureShapeIsStillAmbiguous() {
        // 这条记录**已知的残余误判**：模型既不听格式（不以 # 开头）又恰好以「AI 」开头时，
        // 会被判成失败。那种输出本来也是废的，所以没有为它再加一层判据——
        // 但把它写成一条测试，是为了下次有人看到这个行为时知道**这是有意的**。
        assertThat(AnalysisBrief.isFailureText("AI 时代的电源设备需要重新估值。")).isTrue();
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    @Test
    void fromAiKeepsTheFailureTextVisibleBecauseItIsTheOnlyDiagnostic() {
        // 「未配置」与「调用失败」的处置完全不同（一个去配 key，一个去看日志）。
        // 把它吞掉换成一句笼统的「生成失败」，排查时就没有任何线索了。
        AnalysisBrief brief = AnalysisBrief.fromAi("AI 调用失败：Connection timed out");

        assertThat(brief.available()).isFalse();
        assertThat(brief.text()).contains("Connection timed out");
        assertThat(brief.title()).isEqualTo(AnalysisBrief.TITLE_UNAVAILABLE);
    }

    @Test
    void theUnavailableNoteSaysWhatStillWorksAndHowToEnableIt() {
        // 未启用时用户最需要知道两件事：**其余几块还在**（否则以为整页坏了），
        // 以及**怎么开**。只写「未启用」是一句没有出路的话。
        AnalysisBrief brief = AnalysisBrief.unavailable("AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。");

        assertThat(brief.note()).contains("规则判定与新闻列表不受影响");
        assertThat(brief.note()).contains("DEEPSEEK_API_KEY");
        assertThat(brief.note()).contains("倾向结论仍以");
        assertThat(brief.note()).isEqualTo(AnalysisBrief.NOTE_NOT_CONFIGURED);
    }

    @Test
    void aCallThatFailedDoesNotTellTheUserToGoConfigureTheKey() {
        // **这一条是照着线上截图写的。** 页面上原来把「配置 DEEPSEEK_API_KEY 后重启」
        // 无条件放在原因原文上面，于是出现过这样一段自相矛盾的话：
        //   上面：「若要启用：在后端配置环境变量 DEEPSEEK_API_KEY 后重启」
        //   下面：「AI 暂未返回内容，请稍后重试。」
        // key 明明已经配好了——真没配的话根本到不了「返回空内容」这一步。
        // 用户照着改一遍配置，什么都不会变，还会以为自己配错了。
        AnalysisBrief brief = AnalysisBrief.fromAi("AI 暂未返回内容，请稍后重试。");

        assertThat(brief.available()).isFalse();
        assertThat(brief.note()).isEqualTo(AnalysisBrief.NOTE_CALL_FAILED);
        assertThat(brief.note()).doesNotContain("DEEPSEEK_API_KEY");
        // 「其余几块还在」这句两种情况下都得有：它是用户判断「要不要刷新整页」的依据
        assertThat(brief.note()).contains("规则判定与新闻列表不受影响");
    }

    @Test
    void theNotConfiguredBranchIsMatchedByPrefixNotByTheWholeSentence() {
        // 按前缀认，不按整句认：`AiClientServiceImpl` 那句结尾的标点改一下，
        // 这里就会静默退化成「调用失败」的说明——而那句会告诉用户「key 已经配好了」，
        // 正好把该做的事说反。
        assertThat(AnalysisBrief.noteFor("AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。"))
                .isEqualTo(AnalysisBrief.NOTE_NOT_CONFIGURED);
        assertThat(AnalysisBrief.noteFor("  AI 服务暂未配置。"))
                .isEqualTo(AnalysisBrief.NOTE_NOT_CONFIGURED);
        assertThat(AnalysisBrief.noteFor("AI 调用失败：Connection timed out"))
                .isEqualTo(AnalysisBrief.NOTE_CALL_FAILED);
        assertThat(AnalysisBrief.noteFor(null)).isEqualTo(AnalysisBrief.NOTE_CALL_FAILED);
    }

    @Test
    void aSuccessfulBriefCarriesNoNote() {
        AnalysisBrief brief = AnalysisBrief.fromAi("## 一句话结论\n偏正面。");

        assertThat(brief.available()).isTrue();
        assertThat(brief.title()).isEqualTo(AnalysisBrief.TITLE_OK);
        assertThat(brief.note()).isNull();
    }

    @Test
    void aSuccessfulBriefIsStrippedOfSurroundingBlankLines() {
        assertThat(AnalysisBrief.fromAi("\n\n  ## 一句话结论\n偏正面。\n\n").text())
                .isEqualTo("## 一句话结论\n偏正面。");
    }
}
