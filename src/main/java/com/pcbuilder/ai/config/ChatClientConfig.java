package com.pcbuilder.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class ChatClientConfig {

    @Value("${ai.provider:gemini}")
    private String provider;

    @Bean
    public ChatClient chatClient(@Lazy GoogleGenAiChatModel geminiModel,
                                 @Lazy OpenAiChatModel groqModel,
                                 @Lazy OllamaChatModel ollamaModel) {
        return switch (provider.toLowerCase()) {
            case "groq" -> ChatClient.builder(groqModel).build();
            case "ollama" -> ChatClient.builder(ollamaModel).build();
            default -> ChatClient.builder(geminiModel).build();
        };
    }
}