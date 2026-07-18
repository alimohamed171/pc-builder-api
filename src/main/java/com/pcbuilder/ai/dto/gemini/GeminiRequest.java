package com.pcbuilder.ai.dto.gemini;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeminiRequest {
    private SystemInstruction systemInstruction;
    private List<GeminiContent> contents;
    private Map<String, Object> generationConfig;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemInstruction {
        private List<GeminiPart> parts;

        public static SystemInstruction of(String text) {
            return new SystemInstruction(List.of(new GeminiPart(text)));
        }
    }
}