package com.ai.daily.service;

import com.ai.daily.dto.ChatMessageDTO;
import com.ai.daily.dto.ChatResponseDTO;

import java.util.List;

/**
 * AI 对话 Service
 */
public interface ChatService {

    ChatResponseDTO chat(String question, List<ChatMessageDTO> history, Long userId);
}
