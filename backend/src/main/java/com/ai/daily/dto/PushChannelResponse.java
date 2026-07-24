package com.ai.daily.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class PushChannelResponse {
    Long id;
    String channelType;
    String displayName;
    String targetPreview;
    boolean secretConfigured;
    Boolean enabled;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
