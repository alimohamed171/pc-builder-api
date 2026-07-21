package com.pcbuilder.ai.dto.response;

import com.pcbuilder.product.dto.ProductDto;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class ChatResponse {
    private Long sessionId;
    private String reply;
    private List<ProductDto> mentionedProducts;
    private LocalDateTime timestamp;
}