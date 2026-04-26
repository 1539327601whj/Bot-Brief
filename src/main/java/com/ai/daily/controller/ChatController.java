package com.ai.daily.controller;

import com.ai.daily.dto.ChatRequestDTO;
import com.ai.daily.dto.ChatResponseDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * AI 对话控制器
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    @Autowired
    private ChatService chatService;

    /**
     * 提问
     */
    @PostMapping
    public Result<ChatResponseDTO> chat(@Valid @RequestBody ChatRequestDTO request) {
        ChatResponseDTO response = chatService.chat(request.getQuestion());
        return Result.ok(response);
    }
}
