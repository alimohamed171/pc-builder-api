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

            List<Product> candidates = baseCandidates;
            if (preferredBrand != null && !preferredBrand.isBlank()) {
                List<Product> brandFiltered = baseCandidates.stream()
                        .filter(p -> p.getRawName().toLowerCase().contains(preferredBrand.toLowerCase()))
                        .collect(Collectors.toList());
                if (!brandFiltered.isEmpty()) {
                    candidates = brandFiltered;
                }
            }

            // --- Socket-matching for MOTHERBOARD: hard requirement, no silent fallback ---
            if (cat == ProductCategory.MOTHERBOARD && requiredSocket != null) {
                List<Product> socketMatching = filterBySocket(candidates, requiredSocket);

                if (!socketMatching.isEmpty()) {
                    candidates = socketMatching;
                } else {
                    List<Product> fullPoolMatch = filterBySocket(
                            productCatalogCache.getByCategory(cat).stream()
                                    .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                                    .collect(Collectors.toList()),
                            requiredSocket
                    );
                    if (!fullPoolMatch.isEmpty()) {
                        candidates = fullPoolMatch.stream()
                                .sorted(Comparator.comparing(Product::getPriceEgp))
                                .collect(Collectors.toList());
                        log.warn("No {} matching CPU socket={} within budget slice - " +
                                "searching full catalog and may exceed sub-budget", cat, requiredSocket);
                    } else {
                        log.error("No motherboard found anywhere in catalog matching socket={} - " +
                                "build will be incompatible for this category", requiredSocket);
                    }
                }
            }

            // --- RAM-type matching for MEMORY: same hard-requirement treatment ---
            if (cat == ProductCategory.MEMORY && requiredRamType != null) {
                List<Product> ramMatching = filterByRamType(candidates, requiredRamType);

                if (!ramMatching.isEmpty()) {
                    candidates = ramMatching;
                } else {
                    List<Product> fullPoolMatch = filterByRamType(
                            productCatalogCache.getByCategory(cat).stream()
                                    .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                                    .collect(Collectors.toList()),
                            requiredRamType
                    );
                    if (!fullPoolMatch.isEmpty()) {
                        candidates = fullPoolMatch.stream()
                                .sorted(Comparator.comparing(Product::getPriceEgp))
                                .collect(Collectors.toList());
                        log.warn("No {} matching RAM type={} within budget slice - " +
                                "searching full catalog and may exceed sub-budget", cat, requiredRamType);
                    } else {
                        log.error("No memory found anywhere in catalog matching RAM type={}", requiredRamType);
                    }
                }

                // Prioritize dual-channel kits over single sticks
                candidates.sort((p1, p2) -> {
                    boolean p1Dual = isDualChannelKit(p1);
                    boolean p2Dual = isDualChannelKit(p2);
                    if (p1Dual && !p2Dual) return -1;
                    if (!p1Dual && p2Dual) return 1;
                    return p2.getPriceEgp().compareTo(p1.getPriceEgp());
                });
            }

            if (cat == ProductCategory.COOLER && requiredSocket != null) {
                final String finalRequiredSocket = requiredSocket;
                List<Product> coolerMatching = candidates.stream()
                        .filter(p -> coolerSupportsSocket(p, finalRequiredSocket))
                        .collect(Collectors.toList());

                if (!coolerMatching.isEmpty()) {
                    candidates = coolerMatching;
                } else {
                    List<Product> fullPoolMatch = productCatalogCache.getByCategory(cat).stream()
                            .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                            .filter(p -> coolerSupportsSocket(p, finalRequiredSocket))
                            .collect(Collectors.toList());
                    if (!fullPoolMatch.isEmpty()) {
                        candidates = fullPoolMatch.stream()
                                .sorted(Comparator.comparing(Product::getPriceEgp))
                                .collect(Collectors.toList());
                        log.warn("No {} matching CPU socket={} within budget slice - " +
                                "searching full catalog.", cat, requiredSocket);
                    } else {
                        log.error("No cooler found anywhere in catalog matching socket={}", requiredSocket);
                    }
                }
            }

            Product chosen = null;
            for (Product p : candidates) {
                if (p.getPriceEgp().doubleValue() <= subBudget) {
                    chosen = p;
                    break;
                }
            }

            if (chosen == null) {
                chosen = candidates.stream()
                        .min(Comparator.comparing(Product::getPriceEgp))
                        .orElse(null);
            }

            if (chosen == null) {
                log.error("Could not select any product for category={} - skipping", cat);
                continue;
            }

            if (cat == ProductCategory.CPU) {
                requiredSocket = extractSocket(chosen);
            } else if (cat == ProductCategory.MOTHERBOARD) {
                requiredRamType = extractRamType(chosen);

                if (requiredRamType == null && requiredSocket != null) {
                    if (requiredSocket.equals("AM5") || requiredSocket.equals("LGA1851")) {
                        requiredRamType = "DDR5";
                    } else if (requiredSocket.equals("AM4")) {
                        requiredRamType = "DDR4";
                    }
                }
            }

            rolloverMoney = subBudget - chosen.getPriceEgp().doubleValue();
            picks.add(chosen);
        }

        return picks;
    }

    private List<Product> filterBySocket(List<Product> products, String targetSocket) {
        return products.stream()
                .filter(p -> {
                    String mbSocket = extractSocket(p);
                    return mbSocket != null && mbSocket.equals(targetSocket);
                })
                .collect(Collectors.toList());
    }

    private List<Product> filterByRamType(List<Product> products, String targetRam) {
        return products.stream()
                .filter(p -> {
                    String ramType = extractRamType(p);
                    return ramType != null && ramType.equals(targetRam);
                })
                .collect(Collectors.toList());
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

    private boolean coolerSupportsSocket(Product p, String targetSocket) {
        if (targetSocket == null) return true;
        String text = (p.getRawName() + " " + (p.getSpecs() != null ? p.getSpecs() : "")).toUpperCase();

        String normalizedText = text.replace(" ", "");

        if (targetSocket.equals("AM5")) {
            return normalizedText.contains("AM5") || normalizedText.contains("AM4");
        }
        return normalizedText.contains(targetSocket);
    }

    private boolean isDualChannelKit(Product p) {
        String text = (p.getRawName() + " " + (p.getSpecs() != null ? p.getSpecs() : "")).toUpperCase();
        return text.matches(".*\\b2\\s*[X*]\\s*\\d+\\b.*") || text.contains("KIT") || text.contains("DUAL");
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