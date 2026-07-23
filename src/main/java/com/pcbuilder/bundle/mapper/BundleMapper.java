package com.pcbuilder.bundle.mapper;

import com.pcbuilder.bundle.dto.*;
import com.pcbuilder.bundle.entity.Bundle;
import com.pcbuilder.bundle.entity.BundleItem;
import com.pcbuilder.common.JsonUtil;
import com.pcbuilder.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dedicated mapper for Bundle <-> BundleResponseDto conversions.
 * Keeps entity<->DTO translation out of the service layer.
 */
@Component
@RequiredArgsConstructor
public class BundleMapper {

    private final ProductMapper productMapper;

    public BundleItemDto toItemDto(BundleItem item) {
        BundleItemDto dto = new BundleItemDto();
        dto.setProductId(item.getProduct().getId());
        dto.setName(item.getProduct().getRawName());
        dto.setCategory(item.getProduct().getCategory().name());
        dto.setPrice(item.getProduct().getPriceEgp());
        dto.setQuantity(item.getQuantity());
        dto.setSubtotal(item.getProduct().getPriceEgp().multiply(BigDecimal.valueOf(item.getQuantity())));
        dto.setImageUrl(item.getProduct().getImageUrl());
        dto.setImages(JsonUtil.parseList(item.getProduct().getImages()));
        return dto;
    }

    public BundleResponseDto toResponseDto(Bundle bundle, CompatibilityResult compatibilityResult) {
        BundleResponseDto dto = new BundleResponseDto();
        dto.setId(bundle.getId());
        dto.setName(bundle.getName());
        dto.setType(bundle.getType());
        dto.setTypeDisplayName(bundle.getType() != null ? bundle.getType().getDisplayName() : null);
        dto.setTotalPrice(bundle.getTotalPrice());
        dto.setCompatible(bundle.isCompatible());
        dto.setCreatedAt(bundle.getCreatedAt());
        dto.setUpdatedAt(bundle.getUpdatedAt());

        List<BundleItemDto> items = bundle.getItems().stream()
                .map(this::toItemDto)
                .collect(Collectors.toList());
        dto.setItems(items);

        if (compatibilityResult != null) {
            dto.setIssues(compatibilityResult.getIssues());
            dto.setAlternatives(compatibilityResult.getAlternatives());
        }

        return dto;
    }

    public List<BundleResponseDto> toResponseDtoList(List<Bundle> bundles) {
        return bundles.stream()
                .map(b -> toResponseDto(b, null))
                .collect(Collectors.toList());
    }
}