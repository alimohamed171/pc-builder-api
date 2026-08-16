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

/**
 * Runs component-compatibility checks over a set of chosen products
 * (CPU <-> Motherboard socket, Motherboard <-> Memory RAM type, Cooler <-> CPU socket,
 * Motherboard <-> Case form factor, PSU wattage vs estimated draw, Cooler radiator size vs Case size)
 * and, whenever a check fails, looks up compatible alternatives from the catalog.
 */
@Service
@RequiredArgsConstructor
public class CompatibilityService {

    private static final int ALTERNATIVES_LIMIT = 5;

    // form-factor "size rank": bigger case/board sizes can host smaller ones.
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
        Product memory = firstOrNull(byCategory.get(ProductCategory.MEMORY));
        Product gpu = firstOrNull(byCategory.get(ProductCategory.GPU));
        Product psu = firstOrNull(byCategory.get(ProductCategory.PSU));
        Product pcCase = firstOrNull(byCategory.get(ProductCategory.CASE));
        Product cooler = firstOrNull(byCategory.get(ProductCategory.COOLER));

        checkCpuMotherboardSocket(cpu, motherboard, result);
        checkMotherboardMemoryRamType(motherboard, memory, cpu, result);
        checkCoolerCpuSocket(cooler, cpu, result);
        checkMotherboardCaseFormFactor(motherboard, pcCase, result);
        checkPsuWattage(cpu, gpu, psu, result);
        checkCoolerCaseFit(cooler, pcCase, result);

        return result;
    }

    // ---------------------------------------------------------------
    // Rule 1: CPU socket must match Motherboard socket
    // ---------------------------------------------------------------
    private void checkCpuMotherboardSocket(Product cpu, Product motherboard, CompatibilityResult result) {
        if (cpu == null || motherboard == null) {
            return;
        }

        String cpuSocket = SpecsUtil.extractSocket(cpu);
        String moboSocket = SpecsUtil.extractSocket(motherboard);

        if (cpuSocket == null || moboSocket == null) {
            return;
        }

        if (!cpuSocket.equals(moboSocket)) {
            result.addIssue("MOTHERBOARD",
                    "CPU socket (" + cpuSocket + ") does not match motherboard socket (" + moboSocket + ").");

            // suggest motherboards matching the chosen CPU's socket
            List<ProductDto> moboAlternatives = findAlternativesBySocket(ProductCategory.MOTHERBOARD, cpuSocket, motherboard.getId());
            result.addAlternatives("MOTHERBOARD", moboAlternatives);

            // suggest CPUs matching the chosen motherboard's socket
            List<ProductDto> cpuAlternatives = findAlternativesBySocket(ProductCategory.CPU, moboSocket, cpu.getId());
            result.addAlternatives("CPU", cpuAlternatives);
        }
    }

    // ---------------------------------------------------------------
    // Rule 2: RAM Type matching (Motherboard <-> RAM & CPU <-> RAM)
    // ---------------------------------------------------------------
    private void checkMotherboardMemoryRamType(Product motherboard, Product memory, Product cpu, CompatibilityResult result) {
        if (memory == null) {
            return;
        }

        String memRamType = SpecsUtil.extractRamType(memory);
        if (memRamType == null) {
            return;
        }

        if (motherboard != null) {
            String moboRamType = SpecsUtil.extractRamType(motherboard);
            if (moboRamType != null && !moboRamType.equals(memRamType)) {
                result.addIssue("MEMORY",
                        "Motherboard requires " + moboRamType + " memory, but selected RAM is " + memRamType + ".");

                List<ProductDto> memAlternatives = findAlternativesByRamType(ProductCategory.MEMORY, moboRamType, memory.getId());
                result.addAlternatives("MEMORY", memAlternatives);
                return;
            }
        }

        if (cpu != null) {
            String cpuRamType = SpecsUtil.extractRamType(cpu);
            if (cpuRamType != null && !cpuRamType.equals(memRamType)) {
                result.addIssue("MEMORY",
                        "CPU requires " + cpuRamType + " memory, but selected RAM is " + memRamType + ".");

                List<ProductDto> memAlternatives = findAlternativesByRamType(ProductCategory.MEMORY, cpuRamType, memory.getId());
                result.addAlternatives("MEMORY", memAlternatives);
            }
        }
    }

    // ---------------------------------------------------------------
    // Rule 3: Cooler CPU Socket support
    // ---------------------------------------------------------------
    private void checkCoolerCpuSocket(Product cooler, Product cpu, CompatibilityResult result) {
        if (cooler == null || cpu == null) {
            return;
        }

        String cpuSocket = SpecsUtil.extractSocket(cpu);
        if (cpuSocket == null) {
            return;
        }

        if (!SpecsUtil.coolerSupportsSocket(cooler, cpuSocket)) {
            result.addIssue("COOLER",
                    "Cooler does not support CPU socket (" + cpuSocket + ").");

            List<ProductDto> coolerAlternatives = findAlternativesByCoolerSocket(cpuSocket, cooler.getId());
            result.addAlternatives("COOLER", coolerAlternatives);
        }
    }

    // ---------------------------------------------------------------
    // Rule 4: Motherboard form factor must physically fit inside the Case
    // ---------------------------------------------------------------
    private void checkMotherboardCaseFormFactor(Product motherboard, Product pcCase, CompatibilityResult result) {
        if (motherboard == null || pcCase == null) {
            return;
        }

        String moboFormFactor = SpecsUtil.extractFormFactor(motherboard);
        String caseType = SpecsUtil.extractFormFactor(pcCase);

        Integer moboRank = rankOf(moboFormFactor);
        Integer caseRank = rankOf(caseType);

        if (moboRank == null || caseRank == null) {
            return;
        }

        if (caseRank < moboRank) {
            result.addIssue("CASE",
                    "Motherboard form factor (" + moboFormFactor + ") does not fit inside the selected case (" + caseType + ").");

            List<ProductDto> caseAlternatives = findAlternativesByMinFormFactorRank(ProductCategory.CASE, moboRank, pcCase.getId());
            result.addAlternatives("CASE", caseAlternatives);
        }
    }

    // ---------------------------------------------------------------
    // Rule 5: PSU wattage must cover estimated CPU + GPU draw + headroom
    // ---------------------------------------------------------------
    private void checkPsuWattage(Product cpu, Product gpu, Product psu, CompatibilityResult result) {
        if (psu == null || (cpu == null && gpu == null)) {
            return;
        }

        Double psuWattage = SpecsUtil.extractWattage(psu);
        if (psuWattage == null) {
            return;
        }

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
    // Rule 6 (soft check): large AIO/radiator coolers may not fit compact cases
    // ---------------------------------------------------------------
    private void checkCoolerCaseFit(Product cooler, Product pcCase, CompatibilityResult result) {
        if (cooler == null || pcCase == null) {
            return;
        }

        Double coolerSizeMm = SpecsUtil.getDouble(SpecsUtil.parse(cooler.getSpecs()), "size_mm");
        String caseType = SpecsUtil.extractFormFactor(pcCase);

        if (coolerSizeMm == null || caseType == null) {
            return;
        }

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
                .filter(p -> socket.equals(SpecsUtil.extractSocket(p)))
                .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                .sorted(Comparator.comparing(Product::getPriceEgp))
                .limit(ALTERNATIVES_LIMIT)
                .map(productMapper::toDto)
                .collect(Collectors.toList());
    }

    private List<ProductDto> findAlternativesByRamType(ProductCategory category, String ramType, Long excludeId) {
        return productRepository.findByCategory(category).stream()
                .filter(p -> !p.getId().equals(excludeId))
                .filter(p -> ramType.equals(SpecsUtil.extractRamType(p)))
                .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                .sorted(Comparator.comparing(Product::getPriceEgp))
                .limit(ALTERNATIVES_LIMIT)
                .map(productMapper::toDto)
                .collect(Collectors.toList());
    }

    private List<ProductDto> findAlternativesByCoolerSocket(String socket, Long excludeId) {
        return productRepository.findByCategory(ProductCategory.COOLER).stream()
                .filter(p -> !p.getId().equals(excludeId))
                .filter(p -> SpecsUtil.coolerSupportsSocket(p, socket))
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
                    String type = SpecsUtil.extractFormFactor(p);
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
                    Double wattage = SpecsUtil.extractWattage(p);
                    return wattage != null && wattage >= minWattage;
                })
                .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                .sorted(Comparator.comparing(Product::getPriceEgp))
                .limit(ALTERNATIVES_LIMIT)
                .map(productMapper::toDto)
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------
    // Small utilities
    // ---------------------------------------------------------------
    private Product firstOrNull(List<Product> products) {
        return (products == null || products.isEmpty()) ? null : products.get(0);
    }

    private Integer rankOf(String formFactorOrCaseType) {
        if (formFactorOrCaseType == null) {
            return null;
        }
        if (formFactorOrCaseType.contains("EATX") || formFactorOrCaseType.contains("FULL TOWER")) {
            return FORM_FACTOR_RANK.get("EATX");
        }
        if (formFactorOrCaseType.contains("MICRO ATX") || formFactorOrCaseType.contains("MICROATX")
                || formFactorOrCaseType.contains("MATX") || formFactorOrCaseType.contains("M-ATX")) {
            return FORM_FACTOR_RANK.get("MICRO ATX");
        }
        if (formFactorOrCaseType.contains("MINI ITX") || formFactorOrCaseType.contains("ITX")) {
            return FORM_FACTOR_RANK.get("MINI ITX");
        }
        if (formFactorOrCaseType.contains("ATX")) {
            return FORM_FACTOR_RANK.get("ATX");
        }
        return null;
    }

    private BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(0, java.math.RoundingMode.HALF_UP);
    }
}
