package com.pcbuilder.ai.service;

import com.pcbuilder.ai.dto.PcBudgetStrategy;
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
import org.springframework.ai.converter.BeanOutputConverter;
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
    private PcBudgetStrategy generateBudgetStrategy(
            BuildGeneratorRequest request
    ) {

        double budget =
                request.getBudget() != null
                        ? request.getBudget().doubleValue()
                        : 30000.0;

        String usage =
                request.getUsage() != null
                        && !request.getUsage().isBlank()
                        ? request.getUsage()
                        : request.getPrompt();

        BeanOutputConverter<PcBudgetStrategy> converter =
                new BeanOutputConverter<>(PcBudgetStrategy.class);

        String systemPrompt = """
        You are an expert PC hardware architect specializing in the Egyptian hardware market.

        Your job is ONLY to create a strategic budget allocation (percentages).
        Do NOT select specific products.

        The available categories are:
        CPU, MOTHERBOARD, MEMORY, GPU, PSU, CASE, COOLER

        CRITICAL MARKET REALITIES (EGYPTIAN POUND - EGP):
        - A modern entry-level dedicated GPU (like an RTX 3050) costs AT LEAST 13,000 EGP.
        WORKLOAD PROFILES:
        1. Office / General / Study:
           - Focus heavily on CPU (30-40%), RAM, and a fast NVMe (handled by Motherboard).
        2. Programming / Software Development:
           - CPU and RAM are top priorities.
        3. Gaming:
           - GPU is the highest priority (35% - 45%).
           - Do not overspend on the CPU; prioritize the graphics card.
        4. AI Workstation / 3D Rendering:
           - GPU is mandatory (40% - 50%) for VRAM/CUDA cores.
           - PSU MUST be at least 0.08 - 0.10 to safely power high-end GPUs.
           - COOLER MUST be at least 0.04 - 0.06 to prevent thermal throttling.

        STRICT RULES:
        1. Allocation values must be decimals between 0.0 and 1.0.
        2. Allocation values must add up to exactly 1.0 (100%).
        3. Do NOT evenly distribute the budget. PCs are heavily skewed toward the CPU and GPU.
        4. The final selection will be performed by deterministic Java code. You are only the financial planner.
        
        Return ONLY the requested structured JSON output. No markdown, no conversational text.

        """ + converter.getFormat();

        String userPrompt = """
            User usage: %s

            User budget: %.2f EGP

            Preferred brand: %s

            User request:
            %s
            """.formatted(
                usage,
                budget,
                request.getPreferredBrand(),
                request.getPrompt()
        );

        try {

            String response =
                    chatClient.prompt()
                            .system(systemPrompt)
                            .user(userPrompt)
                            .call()
                            .content();

            if (response == null || response.isBlank()) {
                throw new IllegalStateException(
                        "AI returned empty allocation strategy"
                );
            }

            PcBudgetStrategy strategy =
                    converter.convert(response);

            validateStrategy(strategy);

            return strategy;

        } catch (Exception e) {

            log.error(
                    "Failed to generate AI budget strategy",
                    e
            );

            return fallbackStrategy(usage);
        }
    }
    private void validateStrategy(
            PcBudgetStrategy strategy
    ) {

        if (strategy == null
                || strategy.allocation() == null
                || strategy.allocation().isEmpty()) {

            throw new IllegalArgumentException(
                    "AI returned an empty allocation"
            );
        }

        double total =
                strategy.allocation()
                        .values()
                        .stream()
                        .mapToDouble(Double::doubleValue)
                        .sum();

        if (Math.abs(total - 1.0) > 0.02) {

            throw new IllegalArgumentException(
                    "AI allocation must total approximately 100%, got "
                            + total
            );
        }

        for (Map.Entry<ProductCategory, Double> entry
                : strategy.allocation().entrySet()) {

            if (entry.getValue() == null
                    || entry.getValue() < 0
                    || entry.getValue() > 1) {

                throw new IllegalArgumentException(
                        "Invalid allocation for "
                                + entry.getKey()
                );
            }
        }
    }
    private PcBudgetStrategy fallbackStrategy(
            String usage
    ) {

        Map<ProductCategory, Double> allocation =
                new LinkedHashMap<>();

        allocation.put(ProductCategory.CPU, 0.20);
        allocation.put(ProductCategory.MOTHERBOARD, 0.12);
        allocation.put(ProductCategory.MEMORY, 0.20);
        allocation.put(ProductCategory.GPU, 0.20);
        allocation.put(ProductCategory.PSU, 0.10);
        allocation.put(ProductCategory.CASE, 0.08);
        allocation.put(ProductCategory.COOLER, 0.10);

        boolean gpuRequired =
                usage != null
                        && (
                        usage.toLowerCase().contains("gaming")
                                || usage.toLowerCase().contains("ai")
                                || usage.toLowerCase().contains("workstation")
                );

        return new PcBudgetStrategy(
                allocation,
                16,
                32,
                "Fallback allocation used because AI strategy generation failed."
        );
    }
    public BuildGeneratorResponse generate(BuildGeneratorRequest request) {

        String effectiveUsage = (request.getUsage() != null && !request.getUsage().isBlank())
                ? request.getUsage()
                : request.getPrompt();

//        List<Product> pickedProducts = deterministicPcBuilder.buildPcForBudget(
//                request.getBudget() != null ? request.getBudget().doubleValue() : 30000.0,
//                request.getPreferredBrand(),
//                effectiveUsage
//        );
        PcBudgetStrategy strategy =
                generateBudgetStrategy(request);

        log.info(
                "AI budget strategy for usage={} budget={}: {}",
                effectiveUsage,
                request.getBudget(),
                strategy
        );

        List<Product> pickedProducts =
                deterministicPcBuilder.buildPcForBudget(
                        request.getBudget() != null
                                ? request.getBudget().doubleValue()
                                : 30000.0,
                        request.getPreferredBrand(),
                        effectiveUsage,
                        strategy
                );
        if (pickedProducts.isEmpty()) {
            throw new AiServiceException("Could not generate a valid build for the given budget.");
        }

        CompatibilityResult compatibilityResult = compatibilityService.evaluate(pickedProducts);

        double budgetLimit = request.getBudget() != null ? request.getBudget().doubleValue() : 30000.0;

        int attempts = 0;
        while (!compatibilityResult.isCompatible() && attempts < MAX_REPAIR_ATTEMPTS) {
            attempts++;
            log.warn("Attempt {}: generated build has compatibility issues: {}", attempts,
                    compatibilityResult.getIssues().stream()
                            .map(i -> i.getCategory() + ": " + i.getReason())
                            .collect(Collectors.joining(", ")));

            boolean repaired = repairOneIssue(pickedProducts, compatibilityResult, request.getPreferredBrand(), budgetLimit);
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
    private boolean repairOneIssue(List<Product> pickedProducts, CompatibilityResult result, String preferredBrand, double budgetLimit) {
        if (result.getIssues() == null || result.getIssues().isEmpty()) {
            return false;
        }

        CompatibilityIssueDto issue = result.getIssues().get(0);
        ProductCategory primaryCategory = resolveCategoryFromIssue(issue, pickedProducts);

        if (primaryCategory != null && trySwapCategory(primaryCategory, pickedProducts, result, preferredBrand, budgetLimit)) {
            return true;
        }

        ProductCategory secondaryCategory = resolveSecondaryCategoryFromIssue(issue, pickedProducts);
        if (secondaryCategory != null && secondaryCategory != primaryCategory) {
            return trySwapCategory(secondaryCategory, pickedProducts, result, preferredBrand, budgetLimit);
        }

        return false;
    }

    private boolean trySwapCategory(ProductCategory categoryToSwap, List<Product> pickedProducts, CompatibilityResult result, String preferredBrand, double budgetLimit) {
        Product current = pickedProducts.stream()
                .filter(p -> p.getCategory() == categoryToSwap)
                .findFirst()
                .orElse(null);
        if (current == null) {
            return false;
        }

        BigDecimal otherProductsTotal = pickedProducts.stream()
                .filter(p -> p.getCategory() != categoryToSwap)
                .map(Product::getPriceEgp)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Product> fullPool = productCatalogCache.getByCategory(categoryToSwap).stream()
                .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                .filter(p -> p.getPriceEgp() != null)
                .filter(p -> !p.getId().equals(current.getId()))
                .sorted(Comparator.comparing(Product::getPriceEgp).reversed())
                .toList();

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

        Product bestFullyCompatibleCandidate = null;
        Product bestPartialCandidate = null;
        int minIssues = result.getIssues().size();

        for (Product candidate : alternatives) {
            BigDecimal candidatePrice = candidate.getPriceEgp();
            if (otherProductsTotal.add(candidatePrice).doubleValue() > budgetLimit) {
                continue;
            }

            List<Product> trialBuild = pickedProducts.stream()
                    .map(p -> p.getCategory() == categoryToSwap ? candidate : p)
                    .collect(Collectors.toList());
            CompatibilityResult trialResult = compatibilityService.evaluate(trialBuild);

            if (trialResult.isCompatible()) {
                bestFullyCompatibleCandidate = candidate;
                break;
            } else if (trialResult.getIssues().size() < minIssues && bestPartialCandidate == null) {
                bestPartialCandidate = candidate;
                minIssues = trialResult.getIssues().size();
            }
        }

        Product chosenCandidate = bestFullyCompatibleCandidate != null ? bestFullyCompatibleCandidate : bestPartialCandidate;

        if (chosenCandidate != null) {
            final Product toSwap = chosenCandidate;
            pickedProducts.replaceAll(p -> p.getCategory() == categoryToSwap ? toSwap : p);
            log.info("Repaired build by swapping category={} to productId={} (price={})",
                    categoryToSwap, toSwap.getId(), toSwap.getPriceEgp());
            return true;
        }

        log.warn("No alternative found in category={} that improves compatibility within budget limit {}",
                categoryToSwap, budgetLimit);
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

    private ProductCategory resolveSecondaryCategoryFromIssue(CompatibilityIssueDto issue, List<Product> pickedProducts) {
        String category = issue.getCategory();
        if (category == null) return null;
        String catUpper = category.toUpperCase();

        if (catUpper.contains("MOTHERBOARD")) {
            return ProductCategory.CPU;
        } else if (catUpper.contains("MEMORY")) {
            return ProductCategory.MOTHERBOARD;
        } else if (catUpper.contains("COOLER")) {
            return ProductCategory.CASE;
        } else if (catUpper.contains("CASE")) {
            return ProductCategory.MOTHERBOARD;
        } else if (catUpper.contains("PSU")) {
            return ProductCategory.GPU;
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