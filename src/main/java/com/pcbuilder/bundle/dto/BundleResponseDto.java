package com.pcbuilder.bundle.dto;

import com.pcbuilder.bundle.entity.BundleType;
import com.pcbuilder.product.dto.ProductDto;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class BundleResponseDto {

    private Long id;
    private String name;
    private BundleType type;
    private String typeDisplayName;
    private BigDecimal totalPrice;
    private boolean compatible;
    private List<BundleItemDto> items;
    private List<CompatibilityIssueDto> issues;
    private Map<String, List<ProductDto>> alternatives;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}