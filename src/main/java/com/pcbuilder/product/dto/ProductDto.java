package com.pcbuilder.product.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ProductDto {

    private Long id;
    private String category;
    private String name;
    private BigDecimal price;
    private boolean inStock;
    private String store;
    private String sourceUrl;
    private String matchedGlobalName;
    private Map<String, String> specs;
    private String imageUrl;
    private List<String> images;
}
