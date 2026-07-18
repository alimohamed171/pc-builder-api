package com.pcbuilder.ai.client;

import com.pcbuilder.ai.dto.gemini.*;
import com.pcbuilder.ai.exception.AiServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiClient {

    private final RestClient geminiRestClient;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    public String generateText(String systemInstruction, List<GeminiContent> contents) {
        return call(systemInstruction, contents, null);
    }

    public String generateJson(String systemInstruction, List<GeminiContent> contents) {
        return call(systemInstruction, contents, Map.of("responseMimeType", "application/json"));
    }

    private String call(String systemInstruction, List<GeminiContent> contents, Map<String, Object> generationConfig) {
        GeminiRequest request = new GeminiRequest(
                GeminiRequest.SystemInstruction.of(systemInstruction),
                contents,
                generationConfig
        );

        try {
            GeminiResponse response = geminiRestClient.post()
                    .uri("/models/{model}:generateContent?key={key}", model, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(GeminiResponse.class);

            if (response == null) {
                throw new AiServiceException("Empty response from Gemini");
            }
            return response.extractText();
        } catch (Exception e) {
            log.error("Gemini call failed", e);
            throw new AiServiceException("Failed to get a response from the AI service", e);
        }
    }
}