package com.pcbuilder.bundle.dto;

import com.pcbuilder.product.dto.ProductDto;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal (service-layer) result of running the compatibility engine
 * over a set of chosen products. Not returned directly to the client;
 * BundleMapper folds it into BundleResponseDto.
 */
@Getter
public class CompatibilityResult {

    private boolean compatible = true;
    private final List<CompatibilityIssueDto> issues = new ArrayList<>();
    private final Map<String, List<ProductDto>> alternatives = new LinkedHashMap<>();

    public void addIssue(String category, String reason) {
        this.compatible = false;
        this.issues.add(new CompatibilityIssueDto(category, reason));
    }

    public void addAlternatives(String category, List<ProductDto> products) {
        if (products == null || products.isEmpty()) {
            return;
        }
        this.alternatives
                .computeIfAbsent(category, k -> new ArrayList<>())
                .addAll(products);
    }
}
