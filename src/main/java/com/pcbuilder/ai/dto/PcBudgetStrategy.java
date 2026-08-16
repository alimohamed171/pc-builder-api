package com.pcbuilder.ai.dto;

import com.pcbuilder.product.entity.ProductCategory;

import java.util.Map;

public record PcBudgetStrategy(
        Map<ProductCategory, Double> allocation,
        int minimumMemoryGb,
        int preferredMemoryGb,
        String reasoning
) {
}