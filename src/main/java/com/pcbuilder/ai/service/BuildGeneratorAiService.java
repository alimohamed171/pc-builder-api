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
import com.pcbuilder.product.repository.ProductRepository;
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

    private static final int CANDIDATES_PER_CATEGORY = 8;
    private static final int MAX_REPAIR_ATTEMPTS = 5;
    private static final double DEFAULT_BUDGET = 30_000.0;

    private static final String PICK_COMPONENTS_SYSTEM = """
        You are a PC hardware expert for 'pcbuilder', an Egyptian PC building platform.
        
        You will receive:
        - User's total budget in EGP
        - Budget allocated per category (spend as close to each category budget as possible)
        - User's intended usage
        - User's preferred brand (if any)
        - Real available products per category with ID, name, and price
        - (Optional) Already locked-in components
        
        Your job is to select ONE product ID from each category to form a complete,
        balanced PC build that:
        1. Fits within the total budget
        2. Spends as close to each category's allocated budget as possible
        3. Is optimized for the stated usage
        4. Respects brand preference when possible
        
        CRITICAL RULES:
        - Return ONLY a JSON object, no explanation, no markdown, no backticks
        - Every ID you pick MUST come exactly from the candidate lists provided
        - Never invent or guess an ID
        - If a category has no candidates, omit it
        - Try to pick the most expensive option within each category budget slice
        - The JSON format must be exactly:
        {
          "CPU": <id>,
          "MOTHERBOARD": <id>,
          "GPU": <id>,
          "MEMORY": <id>,
          "PSU": <id>,
          "CASE": <id>,
          "COOLER": <id>
        }
        """;

    private static final String REPAIR_SYSTEM = """
        You are a PC hardware compatibility expert for 'pcbuilder'.
        
        A PC build has a compatibility issue. You will receive:
        - The current build components
        - The compatibility issue description
        - A list of alternative products for the problematic category
        
        Pick ONE product ID from the alternatives list that will fix the compatibility issue.
        
        CRITICAL RULES:
        - Return ONLY a JSON object: {"id": <number>}
        - The ID must come exactly from the alternatives list provided
        - Never invent or guess an ID
        """;

    private static final String REASONING_SYSTEM = """
        You are an expert hardware assistant for 'pcbuilder', an Egyptian PC building platform.
        
        A complete, compatible PC build has been selected. Write a short friendly paragraph
        (3-4 sentences max) explaining why this combination is great for the user's usage.
        
        RULES:
        - Do NOT list individual prices or total budget
        - Do NOT output JSON, markdown, or bullet points
        - Plain text paragraph only
        """;

    private final CompatibilityService compatibilityService;
    private final ProductRepository productRepository;
    private final ChatClient chatClient;
    private final ProductMapper productMapper;
    private final DeterministicPcBuilder deterministicPcBuilder;


    public BuildGeneratorResponse generate(BuildGeneratorRequest request) {

        double budget = (request.getBudget() != null && request.getBudget().doubleValue() > 0)
                ? request.getBudget().doubleValue()
                : DEFAULT_BUDGET;

        String usage = firstNonBlank(request.getUsage(), request.getPrompt(), "gaming");
        String brand = request.getPreferredBrand();

        log.info("[BuildGen] Starting AI build: budget={} usage={} brand={}", budget, usage, brand);


        List<Product> mustIncludeProducts = resolveMustInclude(request.getMustInclude());
        Set<ProductCategory> lockedCategories = mustIncludeProducts.stream()
                .map(Product::getCategory)
                .collect(Collectors.toSet());

        double mustIncludeCost = mustIncludeProducts.stream()
                .mapToDouble(p -> p.getPriceEgp().doubleValue())
                .sum();

        if (mustIncludeCost >= budget) {
            throw new AiServiceException(
                    "The mustInclude products already exceed the total budget.");
        }


        Map<ProductCategory, List<Product>> candidates =
                deterministicPcBuilder.getCandidatesPerCategory(
                        budget, usage, brand, CANDIDATES_PER_CATEGORY);

        candidates.forEach((cat, products) -> {
            log.info("[Candidates] {} → {} products: {}",
                    cat,
                    products.size(),
                    products.stream()
                            .map(p -> "id=" + p.getId() + "(" + p.getPriceEgp() + ")")
                            .collect(Collectors.joining(", ")));
        });

        lockedCategories.forEach(candidates::remove);

        if (candidates.isEmpty()) {
            throw new AiServiceException(
                    "No products available for the given budget.");
        }

        Map<ProductCategory, Long> aiPicks = askAiToPick(
                budget, usage, brand, candidates, mustIncludeProducts, request.getPrompt());

        List<Product> pickedProducts = buildFinalList(aiPicks, mustIncludeProducts, candidates);

        if (pickedProducts.isEmpty()) {
            throw new AiServiceException(
                    "AI could not select valid components for this build.");
        }


        CompatibilityResult compatibilityResult = compatibilityService.evaluate(pickedProducts);
        int attempts = 0;

        while (!compatibilityResult.isCompatible() && attempts < MAX_REPAIR_ATTEMPTS) {
            attempts++;
            log.warn("[BuildGen] Attempt {}: issues: {}", attempts,
                    issuesSummary(compatibilityResult));

            boolean repaired = repairWithAi(
                    pickedProducts, compatibilityResult, lockedCategories, candidates);
            if (!repaired) {
                log.error("[BuildGen] Could not repair after {} attempts", attempts);
                break;
            }
            compatibilityResult = compatibilityService.evaluate(pickedProducts);
        }

        if (!compatibilityResult.isCompatible()) {
            log.error("[BuildGen] Returning build with unresolved issues after {} attempts",
                    attempts);
        }


        BigDecimal totalPrice = pickedProducts.stream()
                .map(Product::getPriceEgp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalPrice.doubleValue() > budget) {
            pickedProducts = trimToFitBudget(pickedProducts, budget, candidates);
            totalPrice = pickedProducts.stream()
                    .map(Product::getPriceEgp)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            compatibilityResult = compatibilityService.evaluate(pickedProducts);
        }

        if (totalPrice.doubleValue() > budget) {
            throw new AiServiceException(String.format(
                    "Could not generate a valid build within your budget of %,.0f EGP. " +
                            "The minimum required for available parts is %,.0f EGP.",
                    budget, totalPrice.doubleValue()));
        }


        List<ProductDto> componentPicks = pickedProducts.stream()
                .map(p -> {
                    ProductDto dto = productMapper.toDto(p);
                    dto.setMatchedGlobalName(p.getRawName());
                    dto.setSpecs(new HashMap<>());
                    return dto;
                })
                .collect(Collectors.toList());


        String reasoning = generateReasoning(usage, request.getPrompt(), componentPicks);

        return new BuildGeneratorResponse(
                componentPicks,
                totalPrice,
                reasoning,
                compatibilityResult.isCompatible()
        );
    }

    private Map<ProductCategory, Long> askAiToPick(
            double budget,
            String usage,
            String brand,
            Map<ProductCategory, List<Product>> candidates,
            List<Product> mustInclude,
            String userPrompt) {

        String prompt = buildPickPrompt(budget, usage, brand, candidates, mustInclude, userPrompt);
        log.info("[BuildGen] Sending pick prompt to AI...");

        try {
            String response = chatClient.prompt()
                    .system(PICK_COMPONENTS_SYSTEM)
                    .user(prompt)
                    .call()
                    .content();

            log.info("[BuildGen] AI pick response: {}", response);
            return parsePickResponse(response, candidates);

        } catch (Exception e) {
            log.error("[BuildGen] AI pick failed: {}", e.getMessage());
            throw new AiServiceException(
                    "AI failed to select components. Please try again.");
        }
    }

    private String buildPickPrompt(
            double budget,
            String usage,
            String brand,
            Map<ProductCategory, List<Product>> candidates,
            List<Product> mustInclude,
            String userPrompt) {

        // Get allocation percentages to tell AI how much to spend per category
        Map<ProductCategory, Double> allocation =
                deterministicPcBuilder.getAllocationForUsage(usage);

        StringBuilder sb = new StringBuilder();
        sb.append("Total Budget: ").append(String.format("%,.0f", budget)).append(" EGP\n");
        sb.append("Usage: ").append(usage).append("\n");

        if (brand != null && !brand.isBlank()) {
            sb.append("Preferred Brand: ").append(brand).append("\n");
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("User Request: ").append(userPrompt).append("\n");
        }

        // Tell AI how much to spend per category
        sb.append("\nTarget budget per category:\n");
        for (Map.Entry<ProductCategory, List<Product>> entry : candidates.entrySet()) {
            ProductCategory cat = entry.getKey();
            double catBudget = budget * allocation.getOrDefault(cat, 0.10);
            sb.append("  ").append(cat)
                    .append(": ").append(String.format("%,.0f", catBudget)).append(" EGP\n");
        }

        if (!mustInclude.isEmpty()) {
            sb.append("\nLocked Components (already chosen, do NOT pick for these categories):\n");
            for (Product p : mustInclude) {
                sb.append("- ").append(p.getCategory())
                        .append(": ").append(p.getRawName())
                        .append(" (").append(p.getPriceEgp()).append(" EGP)\n");
            }
        }

        sb.append("\nAvailable Products (pick ONE id per category):\n");
        for (Map.Entry<ProductCategory, List<Product>> entry : candidates.entrySet()) {
            ProductCategory cat = entry.getKey();
            double catBudget = budget * allocation.getOrDefault(cat, 0.10);
            sb.append("\n").append(cat)
                    .append(" (target: ").append(String.format("%,.0f", catBudget)).append(" EGP):\n");
            for (Product p : entry.getValue()) {
                sb.append("  id=").append(p.getId())
                        .append(" | ").append(p.getRawName())
                        .append(" | ").append(p.getPriceEgp()).append(" EGP\n");
            }
        }

        return sb.toString();
    }

    private Map<ProductCategory, Long> parsePickResponse(
            String response, Map<ProductCategory, List<Product>> candidates) {

        Map<ProductCategory, Long> result = new LinkedHashMap<>();
        if (response == null) return result;

        String json = response.replaceAll("```json", "").replaceAll("```", "").trim();

        // Build valid ID sets per category for validation
        Map<ProductCategory, Set<Long>> validIds = new HashMap<>();
        for (Map.Entry<ProductCategory, List<Product>> entry : candidates.entrySet()) {
            validIds.put(entry.getKey(),
                    entry.getValue().stream()
                            .map(Product::getId)
                            .collect(Collectors.toSet()));
        }

        for (ProductCategory cat : ProductCategory.values()) {
            String key = "\"" + cat.name() + "\"";
            int idx = json.indexOf(key);
            if (idx == -1) continue;

            int colon = json.indexOf(':', idx);
            if (colon == -1) continue;

            String rest = json.substring(colon + 1).trim();
            StringBuilder digits = new StringBuilder();
            for (char c : rest.toCharArray()) {
                if (Character.isDigit(c)) digits.append(c);
                else if (!digits.isEmpty()) break;
            }
            if (digits.isEmpty()) continue;

            try {
                long id = Long.parseLong(digits.toString());
                Set<Long> allowed = validIds.get(cat);
                if (allowed != null && allowed.contains(id)) {
                    result.put(cat, id);
                } else {
                    log.warn("[BuildGen] AI returned invalid id={} for category={} - ignoring",
                            id, cat);
                }
            } catch (NumberFormatException e) {
                log.warn("[BuildGen] Could not parse id for category={}", cat);
            }
        }

        return result;
    }


    private List<Product> buildFinalList(
            Map<ProductCategory, Long> aiPicks,
            List<Product> mustInclude,
            Map<ProductCategory, List<Product>> candidates) {

        List<Product> result = new ArrayList<>(mustInclude);

        Map<Long, Product> idToProduct = candidates.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));

        for (Map.Entry<ProductCategory, Long> entry : aiPicks.entrySet()) {
            Product p = idToProduct.get(entry.getValue());
            if (p != null) {
                result.add(p);
            }
        }

        return result;
    }


    private boolean repairWithAi(
            List<Product> picks,
            CompatibilityResult result,
            Set<ProductCategory> lockedCategories,
            Map<ProductCategory, List<Product>> candidates) {

        if (result.getIssues() == null || result.getIssues().isEmpty()) return false;

        ProductCategory categoryToSwap = null;
        CompatibilityIssueDto targetIssue = null;

        for (CompatibilityIssueDto issue : result.getIssues()) {
            ProductCategory cat = resolveCategoryFromIssue(issue);
            if (cat != null && !lockedCategories.contains(cat)) {
                categoryToSwap = cat;
                targetIssue = issue;
                break;
            }
        }

        if (categoryToSwap == null) {
            log.warn("[Repair] All problematic categories are locked");
            return false;
        }

        final ProductCategory finalCategoryToSwap = categoryToSwap;
        final CompatibilityIssueDto finalTargetIssue = targetIssue;

        Product current = picks.stream()
                .filter(p -> p.getCategory() == finalCategoryToSwap)
                .findFirst().orElse(null);

        List<Product> alternatives = candidates.getOrDefault(finalCategoryToSwap, List.of())
                .stream()
                .filter(p -> current == null || !p.getId().equals(current.getId()))
                .collect(Collectors.toList());

        if (alternatives.isEmpty()) {
            log.warn("[Repair] No alternatives for category={}", finalCategoryToSwap);
            return false;
        }

        String currentBuildSummary = picks.stream()
                .map(p -> p.getCategory() + ": " + p.getRawName()
                        + " (" + p.getPriceEgp() + " EGP)")
                .collect(Collectors.joining("\n"));

        StringBuilder repairPrompt = new StringBuilder();
        repairPrompt.append("Current build:\n").append(currentBuildSummary).append("\n\n");
        repairPrompt.append("Compatibility issue: ")
                .append(finalTargetIssue.getCategory()).append(" - ")
                .append(finalTargetIssue.getReason()).append("\n\n");
        repairPrompt.append("Alternative ").append(finalCategoryToSwap).append(" options:\n");
        for (Product p : alternatives) {
            repairPrompt.append("  id=").append(p.getId())
                    .append(" | ").append(p.getRawName())
                    .append(" | ").append(p.getPriceEgp()).append(" EGP\n");
        }
        repairPrompt.append("\nReturn JSON: {\"id\": <number>}");

        try {
            String response = chatClient.prompt()
                    .system(REPAIR_SYSTEM)
                    .user(repairPrompt.toString())
                    .call()
                    .content();

            log.info("[Repair] AI repair response: {}", response);

            Long newId = parseRepairResponse(response, alternatives);
            if (newId == null) {
                log.warn("[Repair] AI returned invalid id for repair");
                return false;
            }

            Product replacement = alternatives.stream()
                    .filter(p -> p.getId().equals(newId))
                    .findFirst().orElse(null);

            if (replacement == null) return false;

            picks.replaceAll(p -> p.getCategory() == finalCategoryToSwap ? replacement : p);

            if (picks.stream().noneMatch(p -> p.getCategory() == finalCategoryToSwap)) {
                picks.add(replacement);
            }

            log.info("[Repair] Swapped {} to id={}", finalCategoryToSwap, newId);
            return true;

        } catch (Exception e) {
            log.error("[Repair] AI repair call failed: {}", e.getMessage());
            return false;
        }
    }

    private Long parseRepairResponse(String response, List<Product> alternatives) {
        if (response == null) return null;

        String json = response.replaceAll("```json", "").replaceAll("```", "").trim();
        int idx = json.indexOf("\"id\"");
        if (idx == -1) idx = json.indexOf("id");
        if (idx == -1) return null;

        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;

        String rest = json.substring(colon + 1).trim();
        StringBuilder digits = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else if (!digits.isEmpty()) break;
        }
        if (digits.isEmpty()) return null;

        try {
            long id = Long.parseLong(digits.toString());
            Set<Long> validIds = alternatives.stream()
                    .map(Product::getId).collect(Collectors.toSet());
            return validIds.contains(id) ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }


    private String generateReasoning(String usage, String prompt, List<ProductDto> components) {
        try {
            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("Usage: ").append(usage).append("\n");
            if (prompt != null) {
                userPrompt.append("User request: ").append(prompt).append("\n");
            }
            userPrompt.append("Selected components:\n");
            for (ProductDto p : components) {
                userPrompt.append("- ").append(p.getCategory())
                        .append(": ").append(p.getMatchedGlobalName()).append("\n");
            }

            String content = chatClient.prompt()
                    .system(REASONING_SYSTEM)
                    .user(userPrompt.toString())
                    .call()
                    .content();

            return content != null ? content.trim() : fallbackReasoning();
        } catch (Exception e) {
            log.error("[BuildGen] Reasoning failed, using fallback.", e);
            return fallbackReasoning();
        }
    }

    private String fallbackReasoning() {
        return "This build has been carefully selected to match your budget and usage " +
                "requirements, ensuring maximum performance and compatibility.";
    }


    private List<Product> resolveMustInclude(List<String> mustInclude) {
        if (mustInclude == null || mustInclude.isEmpty()) return List.of();

        List<Long> ids = mustInclude.stream()
                .map(s -> {
                    try { return Long.parseLong(s.trim()); }
                    catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return ids.isEmpty() ? List.of() : productRepository.findByIdIn(ids);
    }

    private ProductCategory resolveCategoryFromIssue(CompatibilityIssueDto issue) {
        String category = issue.getCategory();
        if (category == null) return null;
        for (ProductCategory pc : ProductCategory.values()) {
            if (category.toUpperCase().contains(pc.name())) {
                if (pc == ProductCategory.CPU
                        && category.toUpperCase().contains("MOTHERBOARD")) {
                    return ProductCategory.MOTHERBOARD;
                }
                return pc;
            }
        }
        return null;
    }

    private String issuesSummary(CompatibilityResult result) {
        return result.getIssues().stream()
                .map(i -> i.getCategory() + ": " + i.getReason())
                .collect(Collectors.joining(", "));
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private List<Product> trimToFitBudget(
            List<Product> picks,
            double budget,
            Map<ProductCategory, List<Product>> candidates) {

        List<Product> result = new ArrayList<>(picks);

        // Downgrade order — least important first
        List<ProductCategory> downgradeOrder = List.of(
                ProductCategory.COOLER,
                ProductCategory.CASE,
                ProductCategory.PSU,
                ProductCategory.MEMORY,
                ProductCategory.GPU,
                ProductCategory.MOTHERBOARD,
                ProductCategory.CPU
        );

        boolean improved = true;
        while (totalOf(result) > budget && improved) {
            improved = false;

            for (ProductCategory cat : downgradeOrder) {
                Product current = result.stream()
                        .filter(p -> p.getCategory() == cat)
                        .findFirst().orElse(null);
                if (current == null) continue;

                // Find cheaper alternative from candidates list
                Product cheaper = candidates.getOrDefault(cat, List.of()).stream()
                        .filter(p -> p.getPriceEgp().doubleValue()
                                < current.getPriceEgp().doubleValue())
                        .min(Comparator.comparing(Product::getPriceEgp))
                        .orElse(null);

                if (cheaper != null) {
                    final ProductCategory finalCat = cat;
                    result.replaceAll(p -> p.getCategory() == finalCat ? cheaper : p);
                    log.info("[TrimBudget] Downgraded {} from {} to {} EGP",
                            cat, current.getPriceEgp(), cheaper.getPriceEgp());
                    improved = true;
                    if (totalOf(result) <= budget) break;
                }
            }
        }

        return result;
    }

    private double totalOf(List<Product> picks) {
        return picks.stream()
                .mapToDouble(p -> p.getPriceEgp().doubleValue())
                .sum();
    }
}