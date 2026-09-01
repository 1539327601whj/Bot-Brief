package com.ai.daily.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DueGenerationDTO {
    private String window;
    private String topic;
    private String generateAt;
    /** 用户想法；空着表示走该主题的系统默认 */
    private String intent;

    public DueGenerationDTO(String window, String topic, String generateAt) {
        this(window, topic, generateAt, "");
    }
}
