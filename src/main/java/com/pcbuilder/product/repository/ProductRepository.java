package com.pcbuilder.product.repository;

import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.entity.ProductCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByCategory(ProductCategory category, Pageable pageable);

    List<Product> findByCategory(ProductCategory category);

    List<Product> findByIdIn(List<Long> ids);

    List<Product> findByCategoryIn(List<ProductCategory> categories);

    @Query("""
            SELECT p FROM Product p
            WHERE (:category IS NULL OR p.category = :category)
              AND (:minPrice IS NULL OR p.priceEgp >= :minPrice)
              AND (:maxPrice IS NULL OR p.priceEgp <= :maxPrice)
              AND (:keyword IS NULL OR LOWER(p.rawName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(p.matchedGlobalName) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:inStockOnly = false OR p.inStock = true)
            """)
    Page<Product> search(@Param("category") ProductCategory category,
                          @Param("minPrice") BigDecimal minPrice,
                          @Param("maxPrice") BigDecimal maxPrice,
                          @Param("keyword") String keyword,
                          @Param("inStockOnly") boolean inStockOnly,
                          Pageable pageable);

    @Query(value = "SELECT * FROM products WHERE in_stock = 1 ORDER BY RAND() LIMIT :limit", nativeQuery = true)
    List<Product> findRandomDeals(@Param("limit") int limit);

    @Query(value = "SELECT * FROM products WHERE category = :category AND in_stock = 1 ORDER BY RAND() LIMIT :limit",
            nativeQuery = true)
    List<Product> findRandomDealsByCategory(@Param("category") String category, @Param("limit") int limit);
}
