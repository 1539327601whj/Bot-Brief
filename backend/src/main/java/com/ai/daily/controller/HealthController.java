package com.ai.daily.controller;

import com.ai.daily.service.HealthCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final HealthCheckService healthCheckService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = healthCheckService.snapshot();
        int status = body.get("httpStatus") instanceof Integer http ? http : 200;
        body.remove("httpStatus");
        if (status != 200) {
            log.warn("健康检查状态异常 httpStatus={}", status);
        }
        return ResponseEntity.status(status).body(body);
    }
}
