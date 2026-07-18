package com.pcbuilder.ai.dto.gemini;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeminiContent {
    private String role;
    private List<GeminiPart> parts;

    public static GeminiContent of(String role, String text) {
        return new GeminiContent(role, List.of(new GeminiPart(text)));
    }
}