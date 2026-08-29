package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
        return ResponseEntity.status(500).body(Result.error(500, message));
    }
}
