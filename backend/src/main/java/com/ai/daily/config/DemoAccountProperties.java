package com.ai.daily.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "demo")
public class DemoAccountProperties {

    private boolean enabled;
    private String email = "demo@example.com";
    private String displayName = "公开演示账号";
    private int tokenExpirationMinutes = 30;
}
