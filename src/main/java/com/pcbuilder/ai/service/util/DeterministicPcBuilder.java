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
        return buildPcForBudget(budget, preferredBrand, null);
    }

    public List<Product> buildPcForBudget(Double budget, String preferredBrand, String usage) {
        if (budget == null || budget <= 0) {
            return List.of();
        }

        Map<ProductCategory, Double> allocation = getAllocationForUsage(usage);

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
                                .sorted(Comparator.comparing(Product::getPriceEgp).reversed())
                                .collect(Collectors.toList());
                        log.warn("No {} matching CPU socket={} within budget slice - " +
                                "searching full catalog and may exceed sub-budget", cat, requiredSocket);
                    } else {
                        log.error("No motherboard found anywhere in catalog matching socket={} - " +
                                "build will be incompatible for this category", requiredSocket);
                    }
                }
            }

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
                                .sorted(Comparator.comparing(Product::getPriceEgp).reversed())
                                .collect(Collectors.toList());
                        log.warn("No {} matching RAM type={} within budget slice - " +
                                "searching full catalog and may exceed sub-budget", cat, requiredRamType);
                    } else {
                        log.error("No memory found anywhere in catalog matching RAM type={}", requiredRamType);
                    }
                }
                candidates.sort((p1, p2) -> {
                    boolean p1Pref = isLargeCapacityOrDualChannel(p1, usage);
                    boolean p2Pref = isLargeCapacityOrDualChannel(p2, usage);
                    if (p1Pref && !p2Pref) return -1;
                    if (!p1Pref && p2Pref) return 1;
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
                                .sorted(Comparator.comparing(Product::getPriceEgp).reversed())
                                .collect(Collectors.toList());
                        log.warn("No {} matching CPU socket={} within budget slice - " +
                                "searching full catalog.", cat, requiredSocket);
                    } else {
                        log.error("No cooler found anywhere in catalog matching socket={}", requiredSocket);
                    }
                }
            }

            Product chosen = null;

            if (preferredBrand != null && !preferredBrand.isBlank()) {
                for (Product p : candidates) {
                    if (matchesBrand(p, preferredBrand) && p.getPriceEgp().doubleValue() <= subBudget) {
                        chosen = p;
                        break;
                    }
                }
            }

            if (chosen == null) {
                for (Product p : candidates) {
                    if (p.getPriceEgp().doubleValue() <= subBudget) {
                        chosen = p;
                        break;
                    }
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

        spendLeftoverBudget(picks, budget);
        optimizeBuildToFitBudget(picks, budget);

        return picks;
    }

    public Map<ProductCategory, List<Product>> getCandidatesPerCategory(
            Double budget, String usage, String preferredBrand, int limitPerCategory) {

        if (budget == null || budget <= 0) return Map.of();

        Map<ProductCategory, Double> allocation = getAllocationForUsage(usage);
        Map<ProductCategory, List<Product>> result = new LinkedHashMap<>();

        String requiredSocket = null;
        String requiredRamType = null;

        for (Map.Entry<ProductCategory, Double> entry : allocation.entrySet()) {
            ProductCategory cat = entry.getKey();
            double subBudget = budget * entry.getValue();

            // Base filtering — in stock, valid category, no laptop parts
            List<Product> baseCandidates = productCatalogCache.getByCategory(cat).stream()
                    .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                    .filter(p -> looksLikeValidCategoryMatch(p, cat))
                    .sorted(Comparator.comparing(Product::getPriceEgp))
                    .collect(Collectors.toList());

            if (baseCandidates.isEmpty()) continue;

            List<Product> candidates = baseCandidates;

            // Socket filtering for MOTHERBOARD
            if (cat == ProductCategory.MOTHERBOARD && requiredSocket != null) {
                List<Product> socketMatching = filterBySocket(candidates, requiredSocket);
                if (!socketMatching.isEmpty()) {
                    candidates = socketMatching;
                } else {
                    // Try full catalog if nothing in base
                    List<Product> fullPoolMatch = filterBySocket(
                            productCatalogCache.getByCategory(cat).stream()
                                    .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                                    .filter(p -> looksLikeValidCategoryMatch(p, cat))
                                    .collect(Collectors.toList()),
                            requiredSocket
                    );
                    if (!fullPoolMatch.isEmpty()) {
                        candidates = fullPoolMatch.stream()
                                .sorted(Comparator.comparing(Product::getPriceEgp))
                                .collect(Collectors.toList());
                        log.warn("[Candidates] No MOTHERBOARD matching socket={} in budget slice, using full catalog", requiredSocket);
                    } else {
                        log.error("[Candidates] No MOTHERBOARD found matching socket={}", requiredSocket);
                    }
                }
            }

            // RAM type filtering for MEMORY
            if (cat == ProductCategory.MEMORY && requiredRamType != null) {
                List<Product> ramMatching = filterByRamType(candidates, requiredRamType);
                if (!ramMatching.isEmpty()) {
                    candidates = ramMatching;
                } else {
                    List<Product> fullPoolMatch = filterByRamType(
                            productCatalogCache.getByCategory(cat).stream()
                                    .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                                    .filter(p -> looksLikeValidCategoryMatch(p, cat))
                                    .collect(Collectors.toList()),
                            requiredRamType
                    );
                    if (!fullPoolMatch.isEmpty()) {
                        candidates = fullPoolMatch.stream()
                                .sorted(Comparator.comparing(Product::getPriceEgp))
                                .collect(Collectors.toList());
                        log.warn("[Candidates] No MEMORY matching ramType={} in budget slice, using full catalog", requiredRamType);
                    } else {
                        log.error("[Candidates] No MEMORY found matching ramType={}", requiredRamType);
                    }
                }
            }

            // Socket filtering for COOLER
            if (cat == ProductCategory.COOLER && requiredSocket != null) {
                final String socket = requiredSocket;
                List<Product> coolerMatching = candidates.stream()
                        .filter(p -> coolerSupportsSocket(p, socket))
                        .collect(Collectors.toList());
                if (!coolerMatching.isEmpty()) {
                    candidates = coolerMatching;
                } else {
                    List<Product> fullPoolMatch = productCatalogCache.getByCategory(cat).stream()
                            .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                            .filter(p -> looksLikeValidCategoryMatch(p, cat))
                            .filter(p -> coolerSupportsSocket(p, socket))
                            .collect(Collectors.toList());
                    if (!fullPoolMatch.isEmpty()) {
                        candidates = fullPoolMatch.stream()
                                .sorted(Comparator.comparing(Product::getPriceEgp))
                                .collect(Collectors.toList());
                        log.warn("[Candidates] No COOLER matching socket={} in budget slice, using full catalog", requiredSocket);
                    } else {
                        log.error("[Candidates] No COOLER found matching socket={}", requiredSocket);
                    }
                }
            }

            // Filter by category budget, fall back to cheapest if nothing fits
            List<Product> withinBudget = candidates.stream()
                    .filter(p -> p.getPriceEgp().doubleValue() <= subBudget)
                    .collect(Collectors.toList());

            if (withinBudget.isEmpty()) {
                log.warn("[Candidates] No {} within subBudget={}, using cheapest available", cat, subBudget);
                withinBudget = candidates.stream()
                        .sorted(Comparator.comparing(Product::getPriceEgp))
                        .limit(limitPerCategory)
                        .collect(Collectors.toList());
            }


            if (cat == ProductCategory.CPU) {
                // Find dominant socket
                requiredSocket = withinBudget.stream()
                        .map(this::extractSocket)
                        .filter(Objects::nonNull)
                        .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                        .entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null);

                // ✅ NEW: Try to filter by brand preference for CPU
                if (preferredBrand != null && !preferredBrand.isBlank()) {
                    List<Product> brandFiltered = withinBudget.stream()
                            .filter(p -> matchesBrand(p, preferredBrand))
                            .collect(Collectors.toList());

                    if (!brandFiltered.isEmpty()) {
                        withinBudget = brandFiltered;
                        // Re-detect socket from brand-filtered list
                        requiredSocket = withinBudget.stream()
                                .map(this::extractSocket)
                                .filter(Objects::nonNull)
                                .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                                .entrySet().stream()
                                .max(Map.Entry.comparingByValue())
                                .map(Map.Entry::getKey)
                                .orElse(requiredSocket);
                        log.info("[Candidates] CPU filtered by brand={} socket={}", preferredBrand, requiredSocket);
                    } else {
                        log.warn("[Candidates] No CPU found for brand={} within budget, ignoring brand preference", preferredBrand);
                    }
                }

                // Re-filter to dominant socket
                if (requiredSocket != null) {
                    final String dominantSocket = requiredSocket;
                    List<Product> sameSocketCpus = withinBudget.stream()
                            .filter(p -> dominantSocket.equals(extractSocket(p)))
                            .collect(Collectors.toList());
                    if (!sameSocketCpus.isEmpty()) {
                        withinBudget = sameSocketCpus;
                    }
                }


                log.info("[Candidates] CPU dominant socket={}", requiredSocket);
            }


            if (cat == ProductCategory.MOTHERBOARD && !withinBudget.isEmpty()) {
                // Try to extract from the cheapest matching motherboard
                for (Product mobo : withinBudget) {
                    requiredRamType = extractRamType(mobo);
                    if (requiredRamType != null) break;
                }
                // Fall back to socket-based guess
                if (requiredRamType == null && requiredSocket != null) {
                    requiredRamType = switch (requiredSocket) {
                        case "AM5", "LGA1851" -> "DDR5";
                        case "AM4", "LGA1700", "LGA1200" -> "DDR4";
                        default -> null;
                    };
                }
                log.info("[Candidates] MOTHERBOARD anchor ramType={}", requiredRamType);
            }

            // Spread sample: cheap, mid, expensive
            List<Product> sample = spreadSample(withinBudget, limitPerCategory);
            result.put(cat, sample);

            log.info("[Candidates] {} → {} candidates (subBudget={} EGP): {}",
                    cat, sample.size(), subBudget,
                    sample.stream()
                            .map(p -> "id=" + p.getId() + "(" + p.getPriceEgp() + ")")
                            .collect(Collectors.joining(", ")));
        }

        return result;
    }

    private void optimizeBuildToFitBudget(List<Product> picks, Double budget) {
        if (picks.isEmpty() || budget == null) return;

        double totalPrice = picks.stream()
                .mapToDouble(p -> p.getPriceEgp().doubleValue())
                .sum();

        if (totalPrice <= budget) return;

        Product cpu = picks.stream().filter(p -> p.getCategory() == ProductCategory.CPU).findFirst().orElse(null);
        Product mobo = picks.stream().filter(p -> p.getCategory() == ProductCategory.MOTHERBOARD).findFirst().orElse(null);
        String socket = cpu != null ? extractSocket(cpu) : null;
        String ramType = mobo != null ? extractRamType(mobo) : null;

        List<ProductCategory> downgradeOrder = List.of(
                ProductCategory.COOLER,
                ProductCategory.CASE,
                ProductCategory.MEMORY,
                ProductCategory.PSU,
                ProductCategory.GPU,
                ProductCategory.MOTHERBOARD,
                ProductCategory.CPU
        );

        boolean improved = true;
        while (totalPrice > budget && improved) {
            improved = false;
            for (ProductCategory cat : downgradeOrder) {
                Product current = picks.stream().filter(p -> p.getCategory() == cat).findFirst().orElse(null);
                if (current == null) continue;

                List<Product> pool = productCatalogCache.getByCategory(cat).stream()
                        .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                        .filter(p -> looksLikeValidCategoryMatch(p, cat))
                        .filter(p -> p.getPriceEgp().doubleValue() < current.getPriceEgp().doubleValue())
                        .sorted(Comparator.comparing(Product::getPriceEgp))
                        .collect(Collectors.toList());

                if (cat == ProductCategory.MOTHERBOARD && socket != null) {
                    pool = filterBySocket(pool, socket);
                } else if (cat == ProductCategory.MEMORY && ramType != null) {
                    pool = filterByRamType(pool, ramType);
                } else if (cat == ProductCategory.COOLER && socket != null) {
                    pool = pool.stream().filter(p -> coolerSupportsSocket(p, socket)).collect(Collectors.toList());
                }

                if (!pool.isEmpty()) {
                    Product cheaper = pool.get(0);
                    picks.remove(current);
                    picks.add(cheaper);
                    totalPrice = picks.stream().mapToDouble(p -> p.getPriceEgp().doubleValue()).sum();
                    improved = true;
                    if (totalPrice <= budget) break;
                }
            }
        }
    }

    public static boolean matchesBrand(Product p, String preferredBrand) {
        if (preferredBrand == null || preferredBrand.isBlank()) return true;
        String brand = preferredBrand.trim().toLowerCase();
        String name = p.getRawName() != null ? p.getRawName().toLowerCase() : "";
        String specs = p.getSpecs() != null ? p.getSpecs().toLowerCase() : "";
        String combined = name + " " + specs;

        return switch (brand) {
            case "nvidia" -> combined.contains("nvidia") || combined.contains("geforce")
                    || combined.contains("rtx") || combined.contains("gtx");
            case "amd" -> combined.contains("amd") || combined.contains("ryzen")
                    || combined.contains("radeon") || combined.contains("rx ");
            case "intel" -> combined.contains("intel") || combined.contains("core i")
                    || combined.contains("arc ");
            default -> combined.contains(brand);
        };
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

    public String extractSocket(Product p) {
        String text = (p.getRawName() + " " + (p.getSpecs() != null ? p.getSpecs() : "")).toUpperCase();
        if (text.contains("AM4")) return "AM4";
        if (text.contains("AM5")) return "AM5";
        if (text.contains("LGA1700") || text.contains("LGA 1700")) return "LGA1700";
        if (text.contains("LGA1200") || text.contains("LGA 1200")) return "LGA1200";
        if (text.contains("LGA1851") || text.contains("LGA 1851")) return "LGA1851";
        return null;
    }

    public String extractRamType(Product p) {
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

    private boolean isLargeCapacityOrDualChannel(Product p, String usage) {
        String text = (p.getRawName() + " " + (p.getSpecs() != null ? p.getSpecs() : "")).toUpperCase();
        if (usage != null) {
            String u = usage.toLowerCase();
            if (u.contains("edit") || u.contains("render") || u.contains("workstation")
                    || u.contains("3d") || u.contains("ai") || u.contains("stream")) {
                if (text.contains("32GB") || text.contains("2X16") || text.contains("2*16")
                        || text.contains("64GB")) {
                    return true;
                }
            }
        }
        return isDualChannelKit(p);
    }

    public Map<ProductCategory, Double> getAllocationForUsage(String usage) {
        Map<ProductCategory, Double> allocation = new LinkedHashMap<>();

        if (usage == null || usage.isBlank()) {
            usage = "gaming";
        }

        String u = usage.trim().toLowerCase();

        if (u.contains("ai") || u.contains("workstation")) {
            allocation.put(ProductCategory.CPU,         0.15);
            allocation.put(ProductCategory.MOTHERBOARD, 0.10);
            allocation.put(ProductCategory.MEMORY,      0.12);
            allocation.put(ProductCategory.GPU,         0.50);
            allocation.put(ProductCategory.PSU,         0.08);
            allocation.put(ProductCategory.CASE,        0.02);
            allocation.put(ProductCategory.COOLER,      0.03);
        } else if (u.contains("program")) {
            allocation.put(ProductCategory.CPU,         0.30);
            allocation.put(ProductCategory.MOTHERBOARD, 0.13);
            allocation.put(ProductCategory.MEMORY,      0.27);
            allocation.put(ProductCategory.GPU,         0.05);
            allocation.put(ProductCategory.PSU,         0.08);
            allocation.put(ProductCategory.CASE,        0.07);
            allocation.put(ProductCategory.COOLER,      0.10);
        } else if (u.contains("office") || u.contains("general") || u.contains("study")) {
            allocation.put(ProductCategory.CPU,         0.25);
            allocation.put(ProductCategory.MOTHERBOARD, 0.20);
            allocation.put(ProductCategory.MEMORY,      0.20);
            allocation.put(ProductCategory.GPU,         0.05);
            allocation.put(ProductCategory.PSU,         0.10);
            allocation.put(ProductCategory.CASE,        0.10);
            allocation.put(ProductCategory.COOLER,      0.10);
        } else {
            allocation.put(ProductCategory.CPU,         0.18);
            allocation.put(ProductCategory.MOTHERBOARD, 0.10);
            allocation.put(ProductCategory.MEMORY,      0.10);
            allocation.put(ProductCategory.GPU,         0.48);
            allocation.put(ProductCategory.PSU,         0.07);
            allocation.put(ProductCategory.CASE,        0.04);
            allocation.put(ProductCategory.COOLER,      0.03);
        }

        return allocation;
    }

    private boolean isDualChannelKit(Product p) {
        String text = (p.getRawName() + " " + (p.getSpecs() != null ? p.getSpecs() : "")).toUpperCase();
        return text.matches(".*\\b2\\s*[X*]\\s*\\d+\\b.*") || text.contains("KIT") || text.contains("DUAL");
    }

    private void spendLeftoverBudget(List<Product> picks, Double budget) {
        if (picks.isEmpty() || budget == null) return;

        double totalPrice = picks.stream().mapToDouble(p -> p.getPriceEgp().doubleValue()).sum();
        double leftover = budget - totalPrice;

        if (leftover > 1000) {
            List<ProductCategory> upgradeOrder = List.of(ProductCategory.CPU, ProductCategory.MEMORY);

            for (ProductCategory cat : upgradeOrder) {
                Product current = picks.stream().filter(p -> p.getCategory() == cat).findFirst().orElse(null);
                if (current == null) continue;

                double maxAllowedPrice = current.getPriceEgp().doubleValue() + leftover;

                List<Product> candidates = productCatalogCache.getByCategory(cat).stream()
                        .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                        .filter(p -> p.getPriceEgp().doubleValue() > current.getPriceEgp().doubleValue())
                        .filter(p -> p.getPriceEgp().doubleValue() <= maxAllowedPrice)
                        .sorted(Comparator.comparing(Product::getPriceEgp).reversed())
                        .collect(Collectors.toList());

                if (cat == ProductCategory.CPU) {
                    Product mobo = picks.stream()
                            .filter(p -> p.getCategory() == ProductCategory.MOTHERBOARD)
                            .findFirst().orElse(null);
                    if (mobo != null && extractSocket(mobo) != null) {
                        candidates = filterBySocket(candidates, extractSocket(mobo));
                    }
                } else if (cat == ProductCategory.MEMORY) {
                    Product mobo = picks.stream()
                            .filter(p -> p.getCategory() == ProductCategory.MOTHERBOARD)
                            .findFirst().orElse(null);
                    if (mobo != null && extractRamType(mobo) != null) {
                        candidates = filterByRamType(candidates, extractRamType(mobo));
                    }
                }

                if (!candidates.isEmpty()) {
                    Product upgrade = candidates.get(0);
                    picks.remove(current);
                    picks.add(upgrade);
                    leftover -= (upgrade.getPriceEgp().doubleValue() - current.getPriceEgp().doubleValue());
                    log.info("Upgraded {} to spend unspent budget.", cat);
                }
            }
        }
    }

    @SuppressWarnings("SpellCheckingInspection")
    private boolean looksLikeValidCategoryMatch(Product p, ProductCategory expectedCategory) {
        String name = p.getRawName().toLowerCase();
        boolean isLaptopPart = name.contains("laptop") || name.contains("notebook")
                || name.contains("sodimm") || name.contains("so-dimm");
        if (isLaptopPart) return false;

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

    private List<Product> spreadSample(List<Product> sorted, int limit) {
        if (sorted.size() <= limit) return sorted;
        List<Product> result = new ArrayList<>();
        double step = (double) sorted.size() / limit;
        for (int i = 0; i < limit; i++) {
            int index = (int) Math.round(i * step);
            if (index >= sorted.size()) index = sorted.size() - 1;
            result.add(sorted.get(index));
        }
        return result;
    }
}