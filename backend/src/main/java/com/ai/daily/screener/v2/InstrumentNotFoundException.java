package com.ai.daily.screener.v2;

/**
 * v2 的「查不到这个代码」。
 *
 * <p>与取数失败严格分开：404 让用户换代码，429/503 让用户等一会儿。
 * 把两者混成一个错误页，用户会一直在那儿重试一个根本不存在的代码。
 */
public class InstrumentNotFoundException extends RuntimeException {

    public InstrumentNotFoundException(String message) {
        super(message);
    }
}
