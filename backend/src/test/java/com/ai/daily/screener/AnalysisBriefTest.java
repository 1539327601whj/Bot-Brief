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
    void theThreeFailureStringsAiClientActuallyReturnsAreAllRecognised() {
        // 这三句是 AiClientServiceImpl **实际会返回**的全部失败文案（不是照着文档抄的，
        // 是那个类的所有 `return "AI 开头` 分支）。漏了任何一句，那一句就会漏成正文。
        assertThat(AnalysisBrief.isFailureText("AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。")).isTrue();
        assertThat(AnalysisBrief.isFailureText("AI 调用失败：Connection timed out")).isTrue();
        assertThat(AnalysisBrief.isFailureText("AI 暂未返回内容，请稍后重试。")).isTrue();
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
        AnalysisBrief brief = AnalysisBrief.unavailable("未配置");

        assertThat(brief.note()).contains("规则判定与新闻列表不受影响");
        assertThat(brief.note()).contains("DEEPSEEK_API_KEY");
        assertThat(brief.note()).contains("倾向结论仍以");
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
