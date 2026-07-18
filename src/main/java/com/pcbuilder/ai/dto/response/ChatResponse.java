package com.pcbuilder.ai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ChatResponse {
    private Long sessionId;
    private String reply;
    private LocalDateTime timestamp;
}