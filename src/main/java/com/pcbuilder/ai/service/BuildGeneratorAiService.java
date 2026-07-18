package com.pcbuilder.ai.service;

import com.pcbuilder.ai.client.GeminiClient;
import com.pcbuilder.ai.dto.gemini.GeminiContent;
import com.pcbuilder.ai.dto.request.BuildGeneratorRequest;
import com.pcbuilder.ai.dto.response.BuildGeneratorResponse;
import com.pcbuilder.ai.exception.AiServiceException;
import com.pcbuilder.bundle.dto.CompatibilityResult;
import com.pcbuilder.bundle.service.CompatibilityService;
import com.pcbuilder.common.SpecsUtil;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.entity.ProductCategory;
import com.pcbuilder.product.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        (id, category, name, price in EGP, key specs) and a user's request.
        Pick exactly one product for each of these categories when relevant
        to the request: CPU, MOTHERBOARD, GPU, PSU, CASE, COOLER, MEMORY.
        Only choose from the given product IDs - never invent products.
        Respect socket compatibility (CPU socket must equal motherboard socket)
        and stay within budget when given.
        Respond ONLY with valid JSON, no extra text, in this exact shape:
        {
          "picks": [{"category": "CPU", "productId": 123}],
          "reasoning": "short explanation of the choices"
        }
        """;

    private final ProductRepository productRepository;
    private final CompatibilityService compatibilityService;
    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    public BuildGeneratorResponse generate(BuildGeneratorRequest request) {
        List<Product> candidates = loadCandidatePool();

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

        List<GeminiContent> contents = List.of(GeminiContent.of("user", userPrompt));
        String json = geminiClient.generateJson(SYSTEM_INSTRUCTION, contents);

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
                        p.getMatchedGlobalName() != null ? p.getMatchedGlobalName() : p.getRawName(),
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

    private List<Product> loadCandidatePool() {
        List<ProductCategory> categories = List.of(
                ProductCategory.CPU, ProductCategory.MOTHERBOARD, ProductCategory.GPU,
                ProductCategory.PSU, ProductCategory.CASE, ProductCategory.COOLER, ProductCategory.MEMORY
        );

        List<Product> pool = new ArrayList<>();
        for (ProductCategory category : categories) {
            List<Product> inCategory = productRepository.findByCategory(category).stream()
                    .filter(p -> Boolean.TRUE.equals(p.getInStock()))
                    .sorted(Comparator.comparing(Product::getPriceEgp))
                    .limit(25)
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
                    .append(", name=").append(p.getMatchedGlobalName() != null ? p.getMatchedGlobalName() : p.getRawName())
                    .append(", price=").append(p.getPriceEgp())
                    .append(", specs=").append(SpecsUtil.parse(p.getSpecs()))
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
                    log.warn("Gemini picked productId={} which is not in the candidate pool - skipping", productId);
                }
            }
            return resolved;
        } catch (Exception e) {
            log.error("Failed to parse Gemini build-generator JSON: {}", json, e);
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
}