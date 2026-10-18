package com.ai.daily.screener.v2;

/**
 * v2 的数据底座没就绪（缺表 / 缺列 / 功能开关关闭）。
 *
 * <p>与「源不可达」分开：这是**部署问题**，重试多少次都不会好，必须让运维先执行迁移。
 * 所以页面文案要直接给出迁移文件名，而不是「请稍后重试」。
 */
public class WarehouseUnavailableException extends RuntimeException {

    public WarehouseUnavailableException(String message) {
        super(message);
    }
}
