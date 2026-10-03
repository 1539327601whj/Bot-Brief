package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OffPoolIndexResolver} 的测试：**形态决定源，源不互相顶替**。
 *
 * <p>这一层看着琐碎，但它守的是章程里最贵的一条：跨口径的数字能差 22%–75%，
 * 而页面上看不出来。路由只要有一处按「谁先回谁赢」写，就会在某个慢请求上
 * 悄悄换源，从此没人能解释分位为什么跳。
 */
class OffPoolIndexResolverTest {

    @Test
    void sixAlphanumericCharactersGoToCsindex() {
        // 中证官网的入参形态，实测对这些都可用
        for (String code : new String[]{"000300", "000905", "000852", "000688",
                "000016", "000015", "930740", "H30533"}) {
            assertThat(OffPoolIndexResolver.resolve(code))
                    .as("code=%s", code)
                    .hasValueSatisfying(r -> {
                        assertThat(r.source()).isEqualTo(OffPoolIndexResolver.Source.CSINDEX);
                        assertThat(r.sourceCode()).isEqualTo(code);
                        assertThat(r.label()).isEqualTo(OffPoolIndexResolver.LABEL_CSINDEX);
                    });
        }
    }

    @Test
    void overseasAndShenzhenShapesGoToDanjuan() {
        for (String code : new String[]{"NDX", "SP500", "GDAXI", "HKHSCEI",
                "HKHSSCNE", "HSFML25", "CSI716567", "SZ399006", "SZ399550"}) {
            assertThat(OffPoolIndexResolver.resolve(code))
                    .as("code=%s", code)
                    .hasValueSatisfying(r -> assertThat(r.source())
                            .isEqualTo(OffPoolIndexResolver.Source.DANJUAN));
        }
    }

    @Test
    void danjuanCodesAreUppercasedBecauseThatSourceIsCaseSensitive() {
        // 池子里存的是 SZ399006 不是 sz399006；用小写去查会「查不到」，
        // 而那不是「没有这个指数」，是代码形态没对上——两种说法在页面上完全不同。
        assertThat(OffPoolIndexResolver.resolve("ndx").orElseThrow().sourceCode()).isEqualTo("NDX");
        assertThat(OffPoolIndexResolver.resolve("sp500").orElseThrow().sourceCode()).isEqualTo("SP500");
        assertThat(OffPoolIndexResolver.resolve("sz399006").orElseThrow().sourceCode())
                .isEqualTo("SZ399006");
    }

    @Test
    void surfaceWhitespaceIsTrimmed() {
        assertThat(OffPoolIndexResolver.resolve("  000300 ").orElseThrow().sourceCode())
                .isEqualTo("000300");
    }

    @Test
    void aBareShenzhenCodeIsRoutedToCsindexEvenThoughThatSourceHasNothingForIt() {
        // 这条是**故意**钉住的限制，不是漏掉的 bug。399006 是 6 位，形态上归中证官网，
        // 而中证官网不覆盖深证系（实测 0 行）。所以它会得到「中证口径查不到」。
        //
        // 页面上同一个指数若写成 SZ399006 就走蛋卷、查得到。两条路各说各的源。
        // 想「聪明一点」在这时切到蛋卷，就是章程最反对的悄悄换源——查不到就说查不到。
        OffPoolIndexResolver.Route route = OffPoolIndexResolver.resolve("399006").orElseThrow();
        assertThat(route.source()).isEqualTo(OffPoolIndexResolver.Source.CSINDEX);
        assertThat(route.sourceCode()).isEqualTo("399006");
    }

    @Test
    void unrecognisedShapesResolveToNothingRatherThanGuessingASource() {
        // 猜源 = 猜口径。返回 empty 让上层回 404 并说明可接受的形态。
        for (String bad : new String[]{"", "   ", "12", "30027", "3002745",
                "000-300", "0003 00", "一二三四五六", "A", "AB@CD"}) {
            assertThat(OffPoolIndexResolver.resolve(bad))
                    .as("code=%r", bad).isEmpty();
        }
        assertThat(OffPoolIndexResolver.resolve(null)).isEmpty();
    }

    @Test
    void theLabelIsAlwaysPresentSoThePageCanSayWhichCaliberWasUsed() {
        // 章程 §5.3 / §8：必须能看出这个数来自哪个口径。label 为空等于没写。
        assertThat(OffPoolIndexResolver.resolve("000300").orElseThrow().label()).isNotBlank();
        assertThat(OffPoolIndexResolver.resolve("NDX").orElseThrow().label()).isNotBlank();
        assertThat(OffPoolIndexResolver.LABEL_CSINDEX)
                .isNotEqualTo(OffPoolIndexResolver.LABEL_DANJUAN);
    }
}