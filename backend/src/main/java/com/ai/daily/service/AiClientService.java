package com.ai.daily.service;

import java.util.List;

public interface AiClientService {

    String chat(String prompt);

    String chat(List<AiMessage> messages, double temperature, int maxTokens);

    record AiMessage(String role, String content) {
    }
}
