package com.pcbuilder.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pcbuilder.ai.dto.request.BuildGeneratorRequest;
import com.pcbuilder.ai.dto.response.BuildGeneratorResponse;
import com.pcbuilder.ai.exception.AiServiceException;
import com.pcbuilder.bundle.dto.CompatibilityResult;
import com.pcbuilder.bundle.service.CompatibilityService;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.entity.ProductCategory;
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
public class BuildGeneratorAiService {

    private static final String SYSTEM_INSTRUCTION = """
        You are a PC build generator for pcbuilder, an Egyptian hardware
        e-commerce platform. You will be given a list of AVAILABLE PRODUCTS
        (id, category, name, price in EGP) and a user's request.
        Pick exactly one product for each of these categories when relevant
        to the request: CPU, MOTHERBOARD, GPU, PSU, CASE, COOLER, MEMORY.
        Only choose from the given product IDs - never invent products.
        Respect socket compatibility (CPU socket must equal motherboard socket,
        inferred from the product names) and stay within budget when given.
        Respond ONLY with raw JSON, no extra text, no markdown code fences,
        no ```json wrapper - just the JSON object itself, in this exact shape:
        {
          "picks": [{"category": "CPU", "productId": 123}],
          "reasoning": "short explanation of the choices"
        }
        When a budget is given, choose components that make good use of that
        budget - do not default to the cheapest option in every category unless
        explicitly asked for the cheapest possible build. A build using less than
        70% of the stated budget is under-using it; prefer better components that
        still fit within budget.
        """;

    private final ProductCatalogCache productCatalogCache;
    private final CompatibilityService compatibilityService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public BuildGeneratorResponse generate(BuildGeneratorRequest request) {
        List<Product> candidates = loadCandidatePool(request.getUsage());
        String catalogText = buildCatalogText(candidates);

        String userPrompt = """
            Budget (EGP): %s
            Usage: %s
            Preferred brand: %s
            User request: %s

            AVAILABLE PRODUCTS:
            %s
            """.formatted(
                request.getBudget(),
                request.getUsage(),
                request.getPreferredBrand(),
                request.getPrompt(),
                catalogText
        );

        String rawJson = chatClient.prompt()
                .system(SYSTEM_INSTRUCTION)
                .user(userPrompt)
                .call()
                .content();

        String json = stripMarkdownCodeFence(rawJson);

        List<Product> pickedProducts = parseAndResolvePicks(json, candidates);

        if (pickedProducts.isEmpty()) {
            throw new AiServiceException("AI could not generate a valid build from the available catalog");
        }

        CompatibilityResult compatibilityResult = compatibilityService.evaluate(pickedProducts);

        BigDecimal totalPrice = pickedProducts.stream()
                .map(Product::getPriceEgp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<BuildGeneratorResponse.ComponentPick> componentPicks = pickedProducts.stream()
                .map(p -> new BuildGeneratorResponse.ComponentPick(
                        p.getCategory().name(),
                        p.getId(),
                        p.getRawName(),
                        p.getPriceEgp()))
                .collect(Collectors.toList());

        String reasoning = extractReasoning(json);

        return new BuildGeneratorResponse(
                componentPicks,
                totalPrice,
                reasoning,
                compatibilityResult.isCompatible()
        );
    }

    private List<Product> loadCandidatePool(String usage) {
        List<ProductCategory> categories = List.of(
                ProductCategory.CPU, ProductCategory.MOTHERBOARD, ProductCategory.GPU,
                ProductCategory.PSU, ProductCategory.CASE, ProductCategory.COOLER, ProductCategory.MEMORY
        );

        List<Product> pool = new ArrayList<>();
        for (ProductCategory category : categories) {
            List<Product> inCategory = productCatalogCache.getByCategory(category).stream()
                    .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                    .filter(p -> looksLikeValidCategoryMatch(p, category))
                    .filter(p -> !(category == ProductCategory.GPU && "GAMING".equalsIgnoreCase(usage)
                            && p.getPriceEgp().doubleValue() < 2500))
                    .sorted(Comparator.comparing(Product::getPriceEgp))
                    .limit(15)
                    .collect(Collectors.toList());
            pool.addAll(inCategory);
        }
        return pool;
    }

    private String buildCatalogText(List<Product> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Product p : candidates) {
            sb.append("id=").append(p.getId())
                    .append(", category=").append(p.getCategory())
                    .append(", name=").append(p.getRawName())
                    .append(", price=").append(p.getPriceEgp())
                    .append("\n");
        }
        return sb.toString();
    }

    private List<Product> parseAndResolvePicks(String json, List<Product> candidates) {
        Map<Long, Product> candidateById = candidates.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode picks = root.get("picks");
            if (picks == null || !picks.isArray()) {
                return List.of();
            }

            List<Product> resolved = new ArrayList<>();
            for (JsonNode pick : picks) {
                Long productId = pick.get("productId").asLong();
                Product product = candidateById.get(productId);
                if (product != null) {
                    resolved.add(product);
                } else {
                    log.warn("AI picked productId={} which is not in the candidate pool - skipping", productId);
                }
            }
            return resolved;
        } catch (Exception e) {
            log.error("Failed to parse AI build-generator JSON: {}", json, e);
            throw new AiServiceException("Failed to parse AI build suggestion", e);
        }
    }

    private String extractReasoning(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode reasoning = root.get("reasoning");
            return reasoning != null ? reasoning.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Some models (notably Llama via Groq) wrap JSON output in markdown code
     * fences (```json ... ```) even when explicitly told to return raw JSON.
     * Strip these before parsing rather than letting them break Jackson.
     */
    private String stripMarkdownCodeFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence != -1) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed.trim();
    }

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
                    || name.matches(".*\\d+mm.*"); // radiator/fan sizes like 120mm, 240m
            default -> true;
        };
    }
}