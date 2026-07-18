package com.pcbuilder.ai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
public class BuildGeneratorResponse {
    private List<ComponentPick> components;
    private BigDecimal totalPrice;
    private String reasoning;
    private boolean compatibilityOk;

    @Data
    @AllArgsConstructor
    public static class ComponentPick {
        private String category;
        private Long productId;
        private String name;
        private BigDecimal price;
    }
}