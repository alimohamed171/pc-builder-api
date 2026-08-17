package com.pcbuilder.bundle.service;

import com.pcbuilder.bundle.dto.CompatibilityResult;
import com.pcbuilder.common.SpecsUtil;
import com.pcbuilder.product.dto.ProductDto;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.entity.ProductCategory;
import com.pcbuilder.product.mapper.ProductMapper;
import com.pcbuilder.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompatibilityService {

    private static final int ALTERNATIVES_LIMIT = 5;

    private static final Map<String, Integer> FORM_FACTOR_RANK = Map.of(
            "MINI ITX", 1,
            "MICRO ATX", 2,
            "ATX", 3,
            "EATX", 4
    );

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    @Value("${app.bundle.default-min-psu-headroom-watts:150}")
    private int psuHeadroomWatts;

    @Value("${app.bundle.default-gpu-draw-watts:250}")
    private int defaultGpuDrawWatts;

    public CompatibilityResult evaluate(List<Product> products) {
        CompatibilityResult result = new CompatibilityResult();

        Map<ProductCategory, List<Product>> byCategory = products.stream()
                .collect(Collectors.groupingBy(Product::getCategory));

        Product cpu = firstOrNull(byCategory.get(ProductCategory.CPU));
        Product motherboard = firstOrNull(byCategory.get(ProductCategory.MOTHERBOARD));
        Product gpu = firstOrNull(byCategory.get(ProductCategory.GPU));
        Product psu = firstOrNull(byCategory.get(ProductCategory.PSU));
        Product pcCase = firstOrNull(byCategory.get(ProductCategory.CASE));
        Product cooler = firstOrNull(byCategory.get(ProductCategory.COOLER));

        checkCpuMotherboardSocket(cpu, motherboard, result);
        checkMotherboardCaseFormFactor(motherboard, pcCase, result);
        checkPsuWattage(cpu, gpu, psu, result);
        checkCoolerCaseFit(cooler, pcCase, result);

        return result;
    }

    // ---------------------------------------------------------------
    // ADDED: The strict bulletproof socket extractor
    // ---------------------------------------------------------------
    private String extractSocketFromProduct(Product p) {
        if (p == null) return null;

        // 1. Check the Name first (Most Reliable)
        String name = p.getRawName() != null ? p.getRawName().toUpperCase() : "";
        if (name.contains("LGA1700") || name.contains("LGA 1700")) return "LGA1700";
        if (name.contains("LGA1200") || name.contains("LGA 1200")) return "LGA1200";
        if (name.contains("LGA1851") || name.contains("LGA 1851")) return "LGA1851";
        if (name.matches(".*\\bAM5\\b.*")) return "AM5";
        if (name.matches(".*\\bAM4\\b.*")) return "AM4";

        // 2. Fallback to checking Specs if the name doesn't specify
        if (p.getSpecs() != null) {
            String specs = p.getSpecs().toUpperCase().replace("\n", " ").replace("\r", " ");
            if (specs.contains("LGA1700") || specs.contains("LGA 1700")) return "LGA1700";
            if (specs.contains("LGA1200") || specs.contains("LGA 1200")) return "LGA1200";
            if (specs.contains("LGA1851") || specs.contains("LGA 1851")) return "LGA1851";
            if (specs.matches(".*\\bAM5\\b.*")) return "AM5";
            if (specs.matches(".*\\bAM4\\b.*")) return "AM4";
        }

        return null;
    }

    // ---------------------------------------------------------------
    // Rule 1: CPU socket must match Motherboard socket
    // ---------------------------------------------------------------
    private void checkCpuMotherboardSocket(Product cpu, Product motherboard, CompatibilityResult result) {
        if (cpu == null || motherboard == null) return;

        // FIXED: Using our strict extractor instead of relying on scraped specs JSON
        String cpuSocket = extractSocketFromProduct(cpu);
        String moboSocket = extractSocketFromProduct(motherboard);

        if (cpuSocket == null || moboSocket == null) return;

        if (!cpuSocket.equals(moboSocket)) {
            result.addIssue("MOTHERBOARD",
                    "CPU socket (" + cpuSocket + ") does not match motherboard socket (" + moboSocket + ").");

            List<ProductDto> moboAlternatives = findAlternativesBySocket(ProductCategory.MOTHERBOARD, cpuSocket, motherboard.getId());
            result.addAlternatives("MOTHERBOARD", moboAlternatives);

            List<ProductDto> cpuAlternatives = findAlternativesBySocket(ProductCategory.CPU, moboSocket, cpu.getId());
            result.addAlternatives("CPU", cpuAlternatives);
        }
    }

    // ---------------------------------------------------------------
    // Rule 2: Motherboard form factor must physically fit inside the Case
    // ---------------------------------------------------------------
    private void checkMotherboardCaseFormFactor(Product motherboard, Product pcCase, CompatibilityResult result) {
        if (motherboard == null || pcCase == null) return;

        String moboFormFactor = normalize(SpecsUtil.get(SpecsUtil.parse(motherboard.getSpecs()), "form_factor"));
        String caseType = normalize(SpecsUtil.get(SpecsUtil.parse(pcCase.getSpecs()), "type"));

        Integer moboRank = rankOf(moboFormFactor);
        Integer caseRank = rankOf(caseType);

        if (moboRank == null || caseRank == null) return;

        if (caseRank < moboRank) {
            result.addIssue("CASE",
                    "Motherboard form factor (" + moboFormFactor + ") does not fit inside the selected case (" + caseType + ").");

            List<ProductDto> caseAlternatives = findAlternativesByMinFormFactorRank(ProductCategory.CASE, moboRank, pcCase.getId());
            result.addAlternatives("CASE", caseAlternatives);
        }
    }

    // ---------------------------------------------------------------
    // Rule 3: PSU wattage must cover estimated CPU + GPU draw + headroom
    // ---------------------------------------------------------------
    private void checkPsuWattage(Product cpu, Product gpu, Product psu, CompatibilityResult result) {
        if (psu == null || (cpu == null && gpu == null)) return;

        Double psuWattage = SpecsUtil.getDouble(SpecsUtil.parse(psu.getSpecs()), "wattage");
        if (psuWattage == null) return;

        double requiredWattage = psuHeadroomWatts;
        if (cpu != null) {
            Double cpuTdp = SpecsUtil.getDouble(SpecsUtil.parse(cpu.getSpecs()), "tdp");
            requiredWattage += (cpuTdp != null ? cpuTdp : 65);
        }
        if (gpu != null) {
            requiredWattage += defaultGpuDrawWatts;
        }

        if (psuWattage < requiredWattage) {
            result.addIssue("PSU",
                    "PSU wattage (" + round(psuWattage) + "W) is likely insufficient for this build "
                            + "(estimated requirement: ~" + round(requiredWattage) + "W).");

            List<ProductDto> psuAlternatives = findAlternativesByMinWattage(requiredWattage, psu.getId());
            result.addAlternatives("PSU", psuAlternatives);
        }
    }

    // ---------------------------------------------------------------
    // Rule 4 (soft check): large AIO/radiator coolers may not fit compact cases
    // ---------------------------------------------------------------
    private void checkCoolerCaseFit(Product cooler, Product pcCase, CompatibilityResult result) {
        if (cooler == null || pcCase == null) return;

        Double coolerSizeMm = SpecsUtil.getDouble(SpecsUtil.parse(cooler.getSpecs()), "size_mm");
        String caseType = normalize(SpecsUtil.get(SpecsUtil.parse(pcCase.getSpecs()), "type"));

        if (coolerSizeMm == null || caseType == null) return;

        boolean isCompactCase = caseType.contains("MINI ITX") || caseType.contains("MINI TOWER");
        if (isCompactCase && coolerSizeMm > 240) {
            result.addIssue("COOLER",
                    "Cooler radiator size (" + round(coolerSizeMm) + "mm) is unlikely to fit a compact case ("
                            + caseType + "). Consider a 240mm or smaller cooler.");

            List<ProductDto> coolerAlternatives = productRepository.findByCategory(ProductCategory.COOLER).stream()
                    .filter(p -> !p.getId().equals(cooler.getId()))
                    .filter(p -> {
                        Double size = SpecsUtil.getDouble(SpecsUtil.parse(p.getSpecs()), "size_mm");
                        return size != null && size <= 240;
                    })
                    .sorted(Comparator.comparing(Product::getPriceEgp))
                    .limit(ALTERNATIVES_LIMIT)
                    .map(productMapper::toDto)
                    .collect(Collectors.toList());
            result.addAlternatives("COOLER", coolerAlternatives);
        }
    }

    // ---------------------------------------------------------------
    // Alternative-lookup helpers
    // ---------------------------------------------------------------
    private List<ProductDto> findAlternativesBySocket(ProductCategory category, String socket, Long excludeId) {
        return productRepository.findByCategory(category).stream()
                .filter(p -> !p.getId().equals(excludeId))
                // FIXED: Call extractSocketFromProduct so the alternatives map correctly!
                .filter(p -> socket.equals(extractSocketFromProduct(p)))
                .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                .sorted(Comparator.comparing(Product::getPriceEgp))
                .limit(ALTERNATIVES_LIMIT)
                .map(productMapper::toDto)
                .collect(Collectors.toList());
    }

    private List<ProductDto> findAlternativesByMinFormFactorRank(ProductCategory category, int minRank, Long excludeId) {
        return productRepository.findByCategory(category).stream()
                .filter(p -> !p.getId().equals(excludeId))
                .filter(p -> {
                    String type = normalize(SpecsUtil.get(SpecsUtil.parse(p.getSpecs()), "type"));
                    Integer rank = rankOf(type);
                    return rank != null && rank >= minRank;
                })
                .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                .sorted(Comparator.comparing(Product::getPriceEgp))
                .limit(ALTERNATIVES_LIMIT)
                .map(productMapper::toDto)
                .collect(Collectors.toList());
    }

    private List<ProductDto> findAlternativesByMinWattage(double minWattage, Long excludeId) {
        return productRepository.findByCategory(ProductCategory.PSU).stream()
                .filter(p -> !p.getId().equals(excludeId))
                .filter(p -> {
                    Double wattage = SpecsUtil.getDouble(SpecsUtil.parse(p.getSpecs()), "wattage");
                    return wattage != null && wattage >= minWattage;
                })
                .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                .sorted(Comparator.comparing(Product::getPriceEgp))
                .limit(ALTERNATIVES_LIMIT)
                .map(productMapper::toDto)
                .collect(Collectors.toList());
    }

    private Product firstOrNull(List<Product> products) {
        return (products == null || products.isEmpty()) ? null : products.get(0);
    }

    private String normalize(String value) {
        if (value == null) return null;
        return value.trim().toUpperCase();
    }

    private Integer rankOf(String formFactorOrCaseType) {
        if (formFactorOrCaseType == null) return null;
        if (formFactorOrCaseType.contains("EATX") || formFactorOrCaseType.contains("FULL TOWER")) return FORM_FACTOR_RANK.get("EATX");
        if (formFactorOrCaseType.contains("MICRO ATX") || formFactorOrCaseType.contains("MICROATX")
                || formFactorOrCaseType.contains("MATX") || formFactorOrCaseType.contains("M-ATX")) return FORM_FACTOR_RANK.get("MICRO ATX");
        if (formFactorOrCaseType.contains("MINI ITX") || formFactorOrCaseType.contains("ITX")) return FORM_FACTOR_RANK.get("MINI ITX");
        if (formFactorOrCaseType.contains("ATX")) return FORM_FACTOR_RANK.get("ATX");
        return null;
    }

    private BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(0, java.math.RoundingMode.HALF_UP);
    }
}