package com.pcbuilder.ai.dto.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiResponse {
    private List<Candidate> candidates;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Candidate {
        private GeminiContent content;
    }

    public String extractText() {
        if (candidates == null || candidates.isEmpty()) return "";
        var parts = candidates.get(0).getContent().getParts();
        if (parts == null || parts.isEmpty()) return "";
        return parts.get(0).getText();
    }
}