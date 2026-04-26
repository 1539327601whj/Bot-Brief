package com.ai.daily.service;

import com.ai.daily.dto.ChatResponseDTO;

/**
 * AI 对话 Service
 */
public interface ChatService {

    /**
     * 根据问题搜索相关简报并生成回答
     */
    ChatResponseDTO chat(String question);
}
