package com.pcbuilder.ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    private Long sessionId;

    @NotBlank(message = "message must not be empty")
    private String message;
}