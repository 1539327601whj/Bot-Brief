package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> handleDataAccess(DataAccessException error) {
        String detail = error.getMostSpecificCause() == null
                ? String.valueOf(error.getMessage())
                : String.valueOf(error.getMostSpecificCause().getMessage());
        String message = "数据库访问失败";
        if (detail.contains("topic_schedules")) {
            message = "数据库缺少订阅时间配置字段，请在 MySQL 执行 backend/sql/V4__subscription_topic_schedules.sql";
        } else if (detail.contains("Unknown column") || detail.contains("doesn't exist")) {
            message = "数据库结构与当前版本不一致，请补跑 backend/sql 下未执行的脚本";
        }
        log.error("数据库访问失败: {}", detail);
        return ResponseEntity.status(500).body(Result.error(500, message));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleIllegalState(IllegalStateException error) {
        String detail = error.getMessage() == null ? "" : error.getMessage();
        String message = "服务配置异常";
        if (detail.contains("JWT_SECRET")) {
            message = "登录密钥配置无效，请检查 JWT_SECRET";
        } else if (!detail.isBlank() && detail.length() <= 80) {
            message = detail;
        }
        log.error("服务状态异常: {}", detail);
        return ResponseEntity.status(500).body(Result.error(500, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(Exception error) {
        log.error("未处理异常", error);
        return ResponseEntity.status(500).body(Result.error(500, "服务暂时不可用，请稍后重试"));
    }
}
