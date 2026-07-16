package com.pcbuilder.product.controller;

import com.pcbuilder.common.ApiResponse;
import com.pcbuilder.common.PageResponse;
import com.pcbuilder.product.dto.ProductDto;
import com.pcbuilder.product.dto.ProductSearchRequest;
import com.pcbuilder.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * Home screen — all products, paginated, optionally filtered by category.
     * GET /api/products?category=CPU&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ProductDto>>> getProducts(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<ProductDto> result = productService.getProducts(category, page, size);
        return ResponseEntity.ok(ApiResponse.success("Products fetched successfully", PageResponse.from(result)));
    }

    /**
     * Home screen — random deals, optionally scoped to a category.
     * GET /api/products/deals/random?category=GPU&limit=10
     */
    @GetMapping("/deals/random")
    public ResponseEntity<ApiResponse<List<ProductDto>>> getRandomDeals(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "10") int limit) {

        List<ProductDto> deals = productService.getRandomDeals(category, limit);
        return ResponseEntity.ok(ApiResponse.success("Random deals fetched successfully", deals));
    }

    /**
     * Search screen — filter by category and/or price range and/or keyword.
     * GET /api/products/search?category=GPU&minPrice=10000&maxPrice=50000&keyword=rtx&page=0&size=20
     */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PageResponse<ProductDto>>> search(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean inStockOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        ProductSearchRequest request = new ProductSearchRequest();
        request.setCategory(category);
        request.setMinPrice(minPrice);
        request.setMaxPrice(maxPrice);
        request.setKeyword(keyword);
        request.setInStockOnly(inStockOnly);
        request.setPage(page);
        request.setSize(size);

        Page<ProductDto> result = productService.search(request);
        return ResponseEntity.ok(ApiResponse.success("Search results fetched successfully", PageResponse.from(result)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDto>> getById(@PathVariable Long id) {
        ProductDto product = productService.getById(id);
        return ResponseEntity.ok(ApiResponse.success("Product fetched successfully", product));
    }
}
