package com.ai.daily.screener;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 「代码查询」页里**不在 42 只池子里**的指数，该去哪个源取估值。
 *
 * <p><b>按代码形态定源，不按「谁先回谁赢」。</b>后者看着更「冗余」，实际是章程最反对的
 * 悄悄换源：同一串代码今天命中中证、明天因为中证慢了一点命中蛋卷，而两个口径的数
 * 实测能差 22%–75%。用户看到的是「同一个指数，分位从 30 跳到 60」，页面上没有任何迹象
 * 说明中间换了源。形态是**输入的函数**，天天一样，所以这个决定是稳定的。
 *
 * <p>两种形态：
 * <ul>
 *   <li>{@code 000300}、{@code H30533} —— 6 位字母数字，中证官网的入参形态 → 中证官网；</li>
 *   <li>{@code NDX}、{@code SP500}、{@code SZ399006}、{@code HKHSCEI} —— 其它 → 蛋卷。</li>
 * </ul>
 *
 * <p><b>一个必须说清的限制：</b>裸的 6 位深证/国证代码（如 {@code 399006}）走的是
 * 中证官网，而中证官网**不覆盖深证系**（实测回 0 行）。所以查 {@code 399006} 会得到
 * 「中证口径查不到」，而池子里同样这个指数是用 {@code SZ399006} 走蛋卷查的。
 * 两条路各查各的源、各说各的源——**不会**因为中证查不到就自动去蛋卷拿一个数来填，
 * 那正是上面说的悄悄换源。拿不到就说拿不到。
 *
 * <p>无状态、无网络，可直接单测。
 */
public final class OffPoolIndexResolver {

    /** 估值来源。与 {@link IndexFundPool#SOURCE_CSINDEX}/{@link IndexFundPool#SOURCE_DANJUAN} 对应。 */
    public enum Source {
        CSINDEX, DANJUAN
    }

    /**
     * 一条路由。
     *
     * @param source     去哪个源
     * @param sourceCode 该源要的代码形态（蛋卷要大写）
     * @param label      页面上写出来的口径名。<b>必须显示</b>——不写的话「同一只指数两个 PE」
     *                   就只能靠用户自己发现
     */
    public record Route(Source source, String sourceCode, String label) {}

    /**
     * 中证官网的入参形态：6 位字母数字。实测可用示例 {@code 000300/000905/000852/000688/000016/000015/930740/H30533}。
     *
     * <p>注意 6 位纯数字里既有中证系也有深证系（399xxx），而中证官网只覆盖前者。
     * 形态分不出这两类，所以这里**不做进一步猜测**——查不到就是查不到。
     */
    static final Pattern CSINDEX_SHAPED = Pattern.compile("[0-9A-Za-z]{6}");

    /**
     * 蛋卷的形态：字母开头、2–12 位字母数字。蛋卷的 {@code index_code} 实测有
     * {@code NDX}、{@code SP500}、{@code SZ399006}、{@code CSI716567}、
     * {@code HKHSCEI}、{@code HKHSSCNE}、{@code HSFML25}、{@code GDAXI} 这些写法。
     */
    static final Pattern DANJUAN_SHAPED = Pattern.compile("[A-Za-z][A-Za-z0-9]{1,11}");

    static final String LABEL_CSINDEX = "中证口径";
    static final String LABEL_DANJUAN = "蛋卷口径";

    private OffPoolIndexResolver() {}

    /**
     * @param rawCode 用户输入的代码，前后空白会被去掉
     * @return 路由；形态都不认识时返回 empty，调用方据此回 404 并说明可接受的形态
     */
    public static Optional<Route> resolve(String rawCode) {
        if (rawCode == null) {
            return Optional.empty();
        }
        String code = rawCode.trim();
        if (code.isEmpty()) {
            return Optional.empty();
        }
        if (CSINDEX_SHAPED.matcher(code).matches()) {
            return Optional.of(new Route(Source.CSINDEX, code, LABEL_CSINDEX));
        }
        if (DANJUAN_SHAPED.matcher(code).matches()) {
            // 蛋卷的大小写敏感：池子里存的是 SZ399006，不是 sz399006
            return Optional.of(new Route(Source.DANJUAN, code.toUpperCase(java.util.Locale.ROOT),
                    LABEL_DANJUAN));
        }
        return Optional.empty();
    }
}