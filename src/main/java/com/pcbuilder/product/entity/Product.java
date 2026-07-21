package com.pcbuilder.product.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to the existing "products" table (imported from pc_bundle_export.sql).
 * This entity is intentionally read-mostly: the table is populated by an
 * external scraper/import job, not by this API.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private ProductCategory category;

    @Column(name = "raw_name", nullable = false, length = 500)
    private String rawName;

    @Column(name = "price_egp", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceEgp;

    @Column(name = "in_stock", nullable = false)
    private Boolean inStock;

    @Column(name = "store", nullable = false, length = 50)
    private String store;

    @Column(name = "source_url", nullable = false, length = 1000)
    private String sourceUrl;

    @Column(name = "matched_global_name", length = 255)
    private String matchedGlobalName;

    @Column(name = "match_confidence", precision = 5, scale = 4)
    private BigDecimal matchConfidence;

    /**
     * Raw JSON blob, e.g. {"socket":"AM5","tdp":"65", ...}. Parsed on demand via SpecsUtil.
     */
    @Column(name = "specs", columnDefinition = "json")
    private String specs;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "images", columnDefinition = "json")
    private String images;

    @Column(name = "first_seen_at")
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "active")
    private Boolean active;
}
