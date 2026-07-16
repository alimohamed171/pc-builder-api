package com.pcbuilder.product.mapper;

import com.pcbuilder.common.SpecsUtil;
import com.pcbuilder.product.dto.ProductDto;
import com.pcbuilder.product.entity.Product;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Dedicated mapper for Product <-> ProductDto conversions.
 */
@Component
public class ProductMapper {

    public ProductDto toDto(Product product) {
        ProductDto dto = new ProductDto();
        dto.setId(product.getId());
        dto.setCategory(product.getCategory().name());
        dto.setName(product.getRawName());
        dto.setPrice(product.getPriceEgp());
        dto.setInStock(Boolean.TRUE.equals(product.getInStock()));
        dto.setStore(product.getStore());
        dto.setSourceUrl(product.getSourceUrl());
        dto.setMatchedGlobalName(product.getMatchedGlobalName());
        dto.setSpecs(SpecsUtil.parse(product.getSpecs()));
        return dto;
    }

    public List<ProductDto> toDtoList(List<Product> products) {
        return products.stream().map(this::toDto).collect(Collectors.toList());
    }
}
