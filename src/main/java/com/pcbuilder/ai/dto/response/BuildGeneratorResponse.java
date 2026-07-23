package com.pcbuilder.ai.dto.response;

import com.pcbuilder.product.dto.ProductDto;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
public class BuildGeneratorResponse {
    private List<ProductDto> components;
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