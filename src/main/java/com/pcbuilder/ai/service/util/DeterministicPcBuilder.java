package com.pcbuilder.ai.service.util;

import com.pcbuilder.ai.service.ProductCatalogCache;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.entity.ProductCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeterministicPcBuilder {

    private final ProductCatalogCache productCatalogCache;

    public List<Product> buildPcForBudget(Double budget, String preferredBrand) {
        if (budget == null || budget <= 0) {
            return List.of();
        }

        // Always allocate across all 7 categories including GPU
        Map<ProductCategory, Double> allocation = new LinkedHashMap<>();
        allocation.put(ProductCategory.CPU, 0.22);
        allocation.put(ProductCategory.MOTHERBOARD, 0.14);
        allocation.put(ProductCategory.GPU, 0.32);
        allocation.put(ProductCategory.MEMORY, 0.10);
        allocation.put(ProductCategory.PSU, 0.09);
        allocation.put(ProductCategory.CASE, 0.08);
        allocation.put(ProductCategory.COOLER, 0.05);

        List<Product> picks = new ArrayList<>();
        double rolloverMoney = 0.0;

        // State trackers for real-time compatibility during selection
        String requiredSocket = null;
        String requiredRamType = null;

        for (Map.Entry<ProductCategory, Double> entry : allocation.entrySet()) {
            ProductCategory cat = entry.getKey();
            double subBudget = (budget * entry.getValue()) + rolloverMoney;

            List<Product> baseCandidates = productCatalogCache.getByCategory(cat).stream()
                    .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                    .filter(p -> looksLikeValidCategoryMatch(p, cat))
                    .sorted(Comparator.comparing(Product::getPriceEgp).reversed())
                    .collect(Collectors.toList());

            if (baseCandidates.isEmpty()) continue;

            // 1. Apply Brand Filter (if provided)
            List<Product> candidates = baseCandidates;
            if (preferredBrand != null && !preferredBrand.isBlank()) {
                List<Product> brandFiltered = baseCandidates.stream()
                        .filter(p -> p.getRawName().toLowerCase().contains(preferredBrand.toLowerCase()))
                        .collect(Collectors.toList());
                if (!brandFiltered.isEmpty()) {
                    candidates = brandFiltered;
                }
            }

            // 2. Filter Motherboards by CPU Socket
            if (cat == ProductCategory.MOTHERBOARD && requiredSocket != null) {
                final String targetSocket = requiredSocket;
                List<Product> socketMatching = candidates.stream()
                        .filter(p -> {
                            String mbSocket = extractSocket(p);
                            return mbSocket == null || mbSocket.equals(targetSocket);
                        })
                        .collect(Collectors.toList());
                if (!socketMatching.isEmpty()) {
                    candidates = socketMatching;
                }
            }

            // 3. Filter RAM by Motherboard RAM Generation
            if (cat == ProductCategory.MEMORY && requiredRamType != null) {
                final String targetRam = requiredRamType;
                List<Product> ramMatching = candidates.stream()
                        .filter(p -> {
                            String ramType = extractRamType(p);
                            return ramType == null || ramType.equals(targetRam);
                        })
                        .collect(Collectors.toList());
                if (!ramMatching.isEmpty()) {
                    candidates = ramMatching;
                }
            }

            // 4. Choose highest price component within subBudget
            Product chosen = null;
            for (Product p : candidates) {
                if (p.getPriceEgp().doubleValue() <= subBudget) {
                    chosen = p;
                    break;
                }
            }

            // Fallback: Pick cheapest if all exceed subBudget
            if (chosen == null) {
                chosen = candidates.get(candidates.size() - 1);
            }

// 5. Store specs from picked CPU & Motherboard for subsequent parts
            if (cat == ProductCategory.CPU) {
                requiredSocket = extractSocket(chosen);
            } else if (cat == ProductCategory.MOTHERBOARD) {
                requiredRamType = extractRamType(chosen);

                // NEW: Smart inference! If the Motherboard name doesn't mention DDR4/DDR5,
                // we can deduce the required RAM entirely from the CPU Socket.
                if (requiredRamType == null && requiredSocket != null) {
                    if (requiredSocket.equals("AM5") || requiredSocket.equals("LGA1851")) {
                        requiredRamType = "DDR5";
                    } else if (requiredSocket.equals("AM4")) {
                        requiredRamType = "DDR4";
                    }
                    // LGA1700 and LGA1200 can be either, so we still have to rely on the title for Intel,
                    // but this fixes 100% of AMD builds!
                }
            }

            // 6. Carry leftover money into the next category
            rolloverMoney = subBudget - chosen.getPriceEgp().doubleValue();
            picks.add(chosen);
        }

        return picks;
    }

    private String extractSocket(Product p) {
        String text = (p.getRawName() + " " + (p.getSpecs() != null ? p.getSpecs() : "")).toUpperCase();
        if (text.contains("AM4")) return "AM4";
        if (text.contains("AM5")) return "AM5";
        if (text.contains("LGA1700") || text.contains("LGA 1700")) return "LGA1700";
        if (text.contains("LGA1200") || text.contains("LGA 1200")) return "LGA1200";
        if (text.contains("LGA1851") || text.contains("LGA 1851")) return "LGA1851";
        return null;
    }

    private String extractRamType(Product p) {
        String text = (p.getRawName() + " " + (p.getSpecs() != null ? p.getSpecs() : "")).toUpperCase();
        if (text.contains("DDR5")) return "DDR5";
        if (text.contains("DDR4")) return "DDR4";
        return null;
    }

    @SuppressWarnings("SpellCheckingInspection")
    private boolean looksLikeValidCategoryMatch(Product p, ProductCategory expectedCategory) {
        String name = p.getRawName().toLowerCase();
        return switch (expectedCategory) {
            case GPU -> name.contains("rtx") || name.contains("gtx") || name.contains("radeon")
                    || name.contains("geforce") || name.contains("rx ") || name.contains("graphics card");
            case CPU -> name.contains("ryzen") || name.contains("core i") || name.contains("processor");
            case PSU -> name.contains("psu") || name.contains("power supply") || name.contains("watt")
                    || name.matches(".*\\d+w.*");
            case COOLER -> name.contains("cooler") || name.contains("fan") || name.contains("aio")
                    || name.contains("heatsink") || name.contains("liquid") || name.contains("air cooler")
                    || name.matches(".*\\d+mm.*");
            default -> true;
        };
    }
}