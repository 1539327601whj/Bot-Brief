package com.ai.daily.screener;

/**
 * 行情数据不可得。**必须让调用方看到明确错误，不能返回空列表假装「今天没有候选」。**
 */
public class MarketDataException extends RuntimeException {

    public MarketDataException(String message) {
        super(message);
    }

    public MarketDataException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 所有域名都在传输层失败（连不上/超时），没有任何一个返回过响应。
     *
     * <p>与普通失败区分开，是因为它决定了**还要不要再试**：网络断了的时候，
     * 换一种请求方式重试必然同样失败，只会让用户多等几十秒才看到同一条错误。
     * 而「域名通了但请求被拒/返回结构异常」才值得换个方式重试。
     */
    public static class MarketDataUnreachableException extends MarketDataException {
        public MarketDataUnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 行情源在限流/封禁我们：连接建起来了，但服务端一个字节都没回就把连接掐了。
     *
     * <p>与「网络不通」分开，是因为两者的处置**完全相反**：网络不通时重试无意义但无害；
     * 被限流时每一次重试都在把这个 IP 往更深的封禁里推。
     * 所以这条异常必须让调用方**立刻停手**——不再跨域名重试、进入冷却、
     * 并且如实告诉用户「等一会儿」。
     */
    public static class MarketDataRateLimitedException extends MarketDataException {
        public MarketDataRateLimitedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}