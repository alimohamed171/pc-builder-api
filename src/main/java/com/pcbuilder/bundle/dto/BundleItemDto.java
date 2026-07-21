package com.pcbuilder.bundle.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class BundleItemDto {

    private Long productId;
    private String name;
    private String category;
    private BigDecimal price;
    private int quantity;
    private BigDecimal subtotal;
    private String imageUrl;
    private List<String> images;
}
