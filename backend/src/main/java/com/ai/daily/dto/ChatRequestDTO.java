package com.ai.daily.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 对话请求 DTO
 */
@Data
public class ChatRequestDTO {

    @NotBlank(message = "问题不能为空")
    private String question;

    /** 最近几轮对话，用于追问；不要包含当前这一句 */
    private List<ChatMessageDTO> history;
}
