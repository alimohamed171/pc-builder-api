package com.pcbuilder.ai.service;

import com.pcbuilder.ai.dto.request.BuildGeneratorRequest;
import com.pcbuilder.ai.dto.response.BuildGeneratorResponse;
import com.pcbuilder.ai.exception.AiServiceException;
import com.pcbuilder.ai.service.util.DeterministicPcBuilder;
import com.pcbuilder.bundle.dto.CompatibilityIssueDto;
import com.pcbuilder.bundle.dto.CompatibilityResult;
import com.pcbuilder.bundle.service.CompatibilityService;
import com.pcbuilder.product.dto.ProductDto;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.entity.ProductCategory;
import com.pcbuilder.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@SuppressWarnings("SpellCheckingInspection")
public class BuildGeneratorAiService {

    private static final String SYSTEM_INSTRUCTION = """
        You are an expert hardware assistant for 'pcbuilder', an Egyptian PC building platform.
        I have already used our strict internal system to calculate and select a perfectly balanced,
        budget-optimized list of PC components for the user.

        Your ONLY job is to write a short, friendly paragraph (3-4 sentences max)
        explaining WHY this specific combination of parts is a great choice for their requested usage.

        CRITICAL RULES:
        1. Do NOT list the individual prices or total budget (the UI handles that).
        2. Do NOT output JSON, markdown, or bulleted lists.
        3. Respond ONLY with the plain text paragraph.
        """;

    private static final int MAX_REPAIR_ATTEMPTS = 3;

    private final DeterministicPcBuilder deterministicPcBuilder;
    private final CompatibilityService compatibilityService;
    private final ProductCatalogCache productCatalogCache;
    private final ChatClient chatClient;
    private final ProductMapper productMapper;

    public BuildGeneratorResponse generate(BuildGeneratorRequest request) {

        String effectiveUsage = (request.getUsage() != null && !request.getUsage().isBlank())
                ? request.getUsage()
                : request.getPrompt();

        List<Product> pickedProducts = deterministicPcBuilder.buildPcForBudget(
                request.getBudget() != null ? request.getBudget().doubleValue() : 30000.0,
                request.getPreferredBrand(),
                effectiveUsage
        );

        if (pickedProducts.isEmpty()) {
            throw new AiServiceException("Could not generate a valid build for the given budget.");
        }

        CompatibilityResult compatibilityResult = compatibilityService.evaluate(pickedProducts);

        int attempts = 0;
        while (!compatibilityResult.isCompatible() && attempts < MAX_REPAIR_ATTEMPTS) {
            attempts++;
            log.warn("Attempt {}: generated build has compatibility issues: {}", attempts,
                    compatibilityResult.getIssues().stream()
                            .map(i -> i.getCategory() + ": " + i.getReason())
                            .collect(Collectors.joining(", ")));

            boolean repaired = repairOneIssue(pickedProducts, compatibilityResult, request.getPreferredBrand());
            if (!repaired) {
                log.error("Could not repair remaining compatibility issues after {} attempts", attempts);
                break;
            }

            compatibilityResult = compatibilityService.evaluate(pickedProducts);
        }

        if (!compatibilityResult.isCompatible()) {
            log.error("Returning build with unresolved compatibility issues after {} repair attempts: {}",
                    attempts, compatibilityResult.getIssues().stream()
                            .map(i -> i.getCategory() + ": " + i.getReason())
                            .collect(Collectors.joining(", ")));
        }

        BigDecimal totalPrice = pickedProducts.stream()
                .map(Product::getPriceEgp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double budgetLimit = request.getBudget() != null ? request.getBudget().doubleValue() : 30000.0;
        if (totalPrice.doubleValue() > budgetLimit) {
            throw new AiServiceException(String.format(
                    "Could not generate a valid build within your budget of %,.0f EGP. The minimum required budget for available in-stock parts is %,.0f EGP.",
                    budgetLimit, totalPrice.doubleValue()));
        }

        List<ProductDto> componentPicks = pickedProducts.stream()
                .map(p -> {
                    ProductDto dto = productMapper.toDto(p);
                    dto.setMatchedGlobalName(p.getRawName());
                    dto.setSpecs(new HashMap<>());
                    return dto;
                })
                .collect(Collectors.toList());

        String userPrompt = buildAiPrompt(request, componentPicks);
        String reasoning;
        try {
            if (chatClient != null) {
                var promptSpec = chatClient.prompt();
                String responseContent = promptSpec
                        .system(SYSTEM_INSTRUCTION)
                        .user(userPrompt)
                        .call()
                        .content();
                reasoning = responseContent != null ? responseContent.trim() : "";
            } else {
                reasoning = "This build has been carefully optimized by our system to match your budget and usage requirements, ensuring maximum performance and compatibility.";
            }
        } catch (Exception e) {
            log.error("AI API failed to generate reasoning. Using fallback text.", e);
            reasoning = "This build has been carefully optimized by our system to match your budget and usage requirements, ensuring maximum performance and compatibility.";
        }

        return new BuildGeneratorResponse(
                componentPicks,
                totalPrice,
                reasoning,
                compatibilityResult.isCompatible()
        );
    }

    /**
     * Attempts to fix ONE compatibility issue by swapping the most likely
     * offending component for an alternative from the same category. Returns
     * true if a swap was made (caller should re-evaluate), false if nothing
     * could be done.
     */
    private boolean repairOneIssue(List<Product> pickedProducts, CompatibilityResult result, String preferredBrand) {
        if (result.getIssues() == null || result.getIssues().isEmpty()) {
            return false;
        }

        CompatibilityIssueDto issue = result.getIssues().get(0);
        ProductCategory categoryToSwap = resolveCategoryFromIssue(issue, pickedProducts);
        if (categoryToSwap == null) {
            return false;
        }

        Product current = pickedProducts.stream()
                .filter(p -> p.getCategory() == categoryToSwap)
                .findFirst()
                .orElse(null);
        if (current == null) {
            return false;
        }

        List<Product> fullPool = productCatalogCache.getByCategory(categoryToSwap).stream()
                .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                .filter(p -> !p.getId().equals(current.getId()))
                .sorted(Comparator.comparing(Product::getPriceEgp))
                .collect(Collectors.toList());

        List<Product> alternatives = new ArrayList<>();
        if (preferredBrand != null && !preferredBrand.isBlank()) {
            List<Product> brandFiltered = fullPool.stream()
                    .filter(p -> DeterministicPcBuilder.matchesBrand(p, preferredBrand))
                    .toList();
            if (!brandFiltered.isEmpty()) {
                alternatives.addAll(brandFiltered);
            }
        }
        for (Product p : fullPool) {
            if (!alternatives.contains(p)) {
                alternatives.add(p);
            }
        }

        for (Product candidate : alternatives) {
            List<Product> trialBuild = pickedProducts.stream()
                    .map(p -> p.getCategory() == categoryToSwap ? candidate : p)
                    .collect(Collectors.toList());

            CompatibilityResult trialResult = compatibilityService.evaluate(trialBuild);
            if (trialResult.isCompatible() || trialResult.getIssues().size() < result.getIssues().size()) {
                // Found a strictly better (or fully fixed) combination - apply it.
                pickedProducts.replaceAll(p -> p.getCategory() == categoryToSwap ? candidate : p);
                log.info("Repaired build by swapping category={} to productId={}",
                        categoryToSwap, candidate.getId());
                return true;
            }
        }

        log.warn("No alternative found in category={} that improves compatibility", categoryToSwap);
        return false;
    }

    /**
     * Maps an issue's category string back to a ProductCategory to know what
     * to swap. Assumes CompatibilityIssueDto.category holds a value matching
     * (or containing) a ProductCategory name, e.g. "MOTHERBOARD" or
     * "CPU_MOTHERBOARD". Falls back to null (no repair) if unrecognized.
     */
    private ProductCategory resolveCategoryFromIssue(CompatibilityIssueDto issue, List<Product> pickedProducts) {
        String category = issue.getCategory();
        if (category == null) return null;

        for (ProductCategory pc : ProductCategory.values()) {
            if (category.toUpperCase().contains(pc.name())) {
                // Prefer swapping the "downstream" component in a pair (e.g. for
                // CPU_MOTHERBOARD, swap the motherboard rather than the CPU,
                // since CPU choice already anchors the rest of the build).
                if (pc == ProductCategory.CPU && category.toUpperCase().contains("MOTHERBOARD")) {
                    return ProductCategory.MOTHERBOARD;
                }
                return pc;
            }
        }
        return null;
    }

    private String buildAiPrompt(BuildGeneratorRequest request, List<ProductDto> components) {
        StringBuilder sb = new StringBuilder();
        sb.append("User Usage: ").append(request.getUsage()).append("\n");
        sb.append("User Request: ").append(request.getPrompt()).append("\n");
        sb.append("Selected Components:\n");
        for (ProductDto p : components) {
            sb.append("- ").append(p.getCategory()).append(": ").append(p.getMatchedGlobalName()).append("\n");
        }
        return sb.toString();
    }
}