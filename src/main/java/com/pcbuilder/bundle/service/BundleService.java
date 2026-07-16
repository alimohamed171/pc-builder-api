package com.pcbuilder.bundle.service;

import com.pcbuilder.auth.entity.User;
import com.pcbuilder.auth.repository.UserRepository;
import com.pcbuilder.bundle.dto.*;
import com.pcbuilder.bundle.entity.Bundle;
import com.pcbuilder.bundle.entity.BundleItem;
import com.pcbuilder.bundle.mapper.BundleMapper;
import com.pcbuilder.bundle.repository.BundleRepository;
import com.pcbuilder.exception.BadRequestException;
import com.pcbuilder.exception.ResourceNotFoundException;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.entity.ProductCategory;
import com.pcbuilder.product.repository.ProductRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BundleService {

    // categories where having more than one item selected doesn't make sense
    private static final Set<ProductCategory> SINGLE_INSTANCE_CATEGORIES = Set.of(
            ProductCategory.CPU, ProductCategory.MOTHERBOARD, ProductCategory.PSU, ProductCategory.CASE
    );

    private final BundleRepository bundleRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final BundleMapper bundleMapper;
    private final CompatibilityService compatibilityService;

    @Transactional
    public BundleResult create(Long userId, BundleSaveRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        List<Product> products = resolveAndValidateProducts(request.getItems());
        CompatibilityResult compatibilityResult = compatibilityService.evaluate(products);

        Bundle bundle = new Bundle();
        bundle.setUser(user);
        bundle.setName(request.getName());
        applyItems(bundle, request, products);
        bundle.setCompatible(compatibilityResult.isCompatible());

        Bundle saved = bundleRepository.save(bundle);

        return buildResult(saved, compatibilityResult);
    }

    @Transactional
    public BundleResult update(Long userId, Long bundleId, BundleSaveRequest request) {
        Bundle bundle = bundleRepository.findByIdAndUserId(bundleId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Bundle not found"));

        List<Product> products = resolveAndValidateProducts(request.getItems());
        CompatibilityResult compatibilityResult = compatibilityService.evaluate(products);

        bundle.clearItems();
        bundle.setName(request.getName());
        applyItems(bundle, request, products);
        bundle.setCompatible(compatibilityResult.isCompatible());
        bundle.setUpdatedAt(LocalDateTime.now());

        Bundle saved = bundleRepository.save(bundle);

        return buildResult(saved, compatibilityResult);
    }

    @Transactional(readOnly = true)
    public BundleResponseDto getById(Long userId, Long bundleId) {
        Bundle bundle = bundleRepository.findByIdAndUserId(bundleId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Bundle not found"));
        return bundleMapper.toResponseDto(bundle, null);
    }

    @Transactional(readOnly = true)
    public Page<BundleResponseDto> getUserBundles(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size <= 0 ? 20 : Math.min(size, 100));
        return bundleRepository.findByUserId(userId, pageable)
                .map(b -> bundleMapper.toResponseDto(b, null));
    }

    // ---------------------------------------------------------------
    private List<Product> resolveAndValidateProducts(List<BundleItemRequest> itemRequests) {
        List<Long> ids = itemRequests.stream().map(BundleItemRequest::getProductId).collect(Collectors.toList());

        List<Product> products = productRepository.findByIdIn(ids);
        if (products.size() != new HashSet<>(ids).size()) {
            Set<Long> foundIds = products.stream().map(Product::getId).collect(Collectors.toSet());
            List<Long> missing = ids.stream().distinct().filter(id -> !foundIds.contains(id)).collect(Collectors.toList());
            throw new BadRequestException("Some products were not found: " + missing);
        }

        Map<ProductCategory, Long> countByCategory = products.stream()
                .collect(Collectors.groupingBy(Product::getCategory, Collectors.counting()));

        for (ProductCategory category : SINGLE_INSTANCE_CATEGORIES) {
            Long count = countByCategory.get(category);
            if (count != null && count > 1) {
                throw new BadRequestException("Only one " + category + " can be selected per bundle");
            }
        }

        return products;
    }

    private void applyItems(Bundle bundle, BundleSaveRequest request, List<Product> products) {
        Map<Long, Product> productById = products.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        BigDecimal total = BigDecimal.ZERO;
        for (BundleItemRequest itemRequest : request.getItems()) {
            Product product = productById.get(itemRequest.getProductId());
            BundleItem item = new BundleItem();
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            bundle.addItem(item);
            total = total.add(product.getPriceEgp().multiply(BigDecimal.valueOf(itemRequest.getQuantity())));
        }
        bundle.setTotalPrice(total);
    }

    private BundleResult buildResult(Bundle bundle, CompatibilityResult compatibilityResult) {
        BundleResponseDto dto = bundleMapper.toResponseDto(bundle, compatibilityResult);

        String message;
        if (compatibilityResult.isCompatible()) {
            message = "Bundle saved successfully. All selected components are compatible.";
        } else {
            String issuesSummary = compatibilityResult.getIssues().stream()
                    .map(CompatibilityIssueDto::getReason)
                    .collect(Collectors.joining(" "));
            message = "Bundle saved, but it is NOT fully compatible: " + issuesSummary
                    + " See 'alternatives' for compatible replacement options.";
        }

        return new BundleResult(dto, message);
    }

    /** Small holder so the controller gets both the DTO and a ready-made human message. */
    @Getter
    @AllArgsConstructor
    public static class BundleResult {
        private final BundleResponseDto data;
        private final String message;
    }
}
