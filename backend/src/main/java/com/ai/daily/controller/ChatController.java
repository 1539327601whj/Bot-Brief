package com.ai.daily.controller;

import com.ai.daily.dto.ChatRequestDTO;
import com.ai.daily.dto.ChatResponseDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.ChatService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * AI 对话控制器
 */
@Slf4j
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
        Long userId = SecurityUtils.currentUserId();
        int questionLen = request.getQuestion() == null ? 0 : request.getQuestion().length();
        log.info("收到 AI 对话请求 user={} questionLen={}", userId, questionLen);
        ChatResponseDTO response = chatService.chat(
                request.getQuestion(), request.getHistory(), userId);
        return Result.ok(response);
    }
}
