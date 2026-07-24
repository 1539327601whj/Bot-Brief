package com.ai.daily.dto;

import lombok.Data;

@Data
public class PushChannelCreateRequest {
    private String channelType;
    private String displayName;
    private String target;
    private String secret;
    private Boolean enabled;
}
