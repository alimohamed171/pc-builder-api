package com.pcbuilder.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GeminiConfig {

    @Value("${gemini.base-url}")
    private String baseUrl;

    @Bean
    public RestClient geminiRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}