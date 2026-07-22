package com.pcbuilder.product.service;

import com.pcbuilder.exception.BadRequestException;
import com.pcbuilder.exception.ResourceNotFoundException;
import com.pcbuilder.product.dto.ProductDto;
import com.pcbuilder.product.dto.ProductSearchRequest;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.entity.ProductCategory;
import com.pcbuilder.product.mapper.ProductMapper;
import com.pcbuilder.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    /** Home screen: all products, paginated, optionally filtered by category. */
    public Page<ProductDto> getProducts(String categoryParam, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        if (categoryParam == null || categoryParam.isBlank()) {
            return productRepository.findAll(pageable).map(productMapper::toDto);
        }
        ProductCategory category = parseCategory(categoryParam);
        return productRepository.findByCategory(category, pageable).map(productMapper::toDto);
    }

    /** Home screen: random deals, optionally scoped to a category. */
    public List<ProductDto> getRandomDeals(String categoryParam, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<Product> products;
        if (categoryParam == null || categoryParam.isBlank()) {
            products = productRepository.findRandomDeals(safeLimit);
        } else {
            ProductCategory category = parseCategory(categoryParam);
            products = productRepository.findRandomDealsByCategory(category.name(), safeLimit);
        }
        return productMapper.toDtoList(products);
    }

    /** Search screen: filter by category + price range + keyword. */
    public Page<ProductDto> search(ProductSearchRequest request) {
        ProductCategory category = null;
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            category = parseCategory(request.getCategory());
        }
        if (request.getMinPrice() != null && request.getMaxPrice() != null
                && request.getMinPrice().compareTo(request.getMaxPrice()) > 0) {
            throw new BadRequestException("minPrice cannot be greater than maxPrice");
        }
        Pageable pageable = PageRequest.of(Math.max(request.getPage(), 0), clampSize(request.getSize()));
        String keyword = (request.getKeyword() == null || request.getKeyword().isBlank())
                ? null
                : request.getKeyword().trim();
        Page<Product> results = productRepository.search(
                category,
                request.getMinPrice(),
                request.getMaxPrice(),
                keyword,
                request.isInStockOnly(),
                pageable
        );
        return results.map(productMapper::toDto);
    }

    public ProductDto getById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        return productMapper.toDto(product);
    }

    public ProductCategory parseCategory(String value) {
        try {
            return ProductCategory.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown category '" + value + "'. Valid values: CPU, MOTHERBOARD, GPU, PSU, CASE, COOLER, MEMORY");
        }
    }

    private int clampSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, 100);
    }
}