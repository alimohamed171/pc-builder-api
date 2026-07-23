package com.pcbuilder.ai.service;

import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.entity.ProductCategory;
import com.pcbuilder.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductCatalogCache {

    private final ProductRepository productRepository;

    private final AtomicReference<Map<ProductCategory, List<Product>>> catalog =
            new AtomicReference<>(Map.of());

    @Scheduled(initialDelay = 0, fixedRate = 10 * 60 * 1000)
    public void refresh() {
        log.info("Refreshing in-memory product catalog...");
        List<Product> all = productRepository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                .collect(Collectors.toList());

        Map<ProductCategory, List<Product>> grouped = all.stream()
                .collect(Collectors.groupingBy(Product::getCategory));

        catalog.set(grouped);
        log.info("In-memory catalog refreshed: {} products across {} categories", all.size(), grouped.size());
    }

    public List<Product> getByCategory(ProductCategory category) {
        return catalog.get().getOrDefault(category, List.of());
    }

    public List<Product> getByCategories(List<ProductCategory> categories) {
        return categories.stream()
                .flatMap(c -> getByCategory(c).stream())
                .collect(Collectors.toList());
    }
}