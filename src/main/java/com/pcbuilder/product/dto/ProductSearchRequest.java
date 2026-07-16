package com.pcbuilder.product.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Query parameters accepted by GET /api/products/search
 */
@Getter
@Setter
public class ProductSearchRequest {

    private String category;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private String keyword;
    private boolean inStockOnly = false;
    private int page = 0;
    private int size = 20;
}
