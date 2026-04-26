package com.ai.daily.dto;

import lombok.Data;

import java.util.List;

/**
 * 对话响应 DTO
 */
@Data
public class ChatResponseDTO {

    private String answer;

    private List<SourceItem> sources;

    @Data
    public static class SourceItem {
        private Long id;
        private String title;
        private String edition;
        private String createdAt;
    }
}
