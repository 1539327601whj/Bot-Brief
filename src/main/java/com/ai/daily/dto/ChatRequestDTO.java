package com.ai.daily.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 对话请求 DTO
 */
@Data
public class ChatRequestDTO {

    @NotBlank(message = "问题不能为空")
    private String question;
}
