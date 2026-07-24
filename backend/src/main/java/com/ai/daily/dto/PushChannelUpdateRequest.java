package com.ai.daily.dto;

import lombok.Data;

@Data
public class PushChannelUpdateRequest {
    private String channelType;
    private String displayName;
    private String target;
    private String secret;
    private Boolean clearSecret;
    private Boolean enabled;
}
