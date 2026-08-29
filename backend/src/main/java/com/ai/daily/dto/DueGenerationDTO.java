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
}
