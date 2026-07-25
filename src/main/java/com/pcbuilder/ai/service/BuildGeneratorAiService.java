package com.pcbuilder.ai.service;

import com.pcbuilder.ai.dto.request.BuildGeneratorRequest;
import com.pcbuilder.ai.dto.response.BuildGeneratorResponse;
import com.pcbuilder.ai.exception.AiServiceException;
import com.pcbuilder.ai.service.util.DeterministicPcBuilder;
import com.pcbuilder.bundle.dto.CompatibilityResult;
import com.pcbuilder.bundle.service.CompatibilityService;
import com.pcbuilder.product.dto.ProductDto;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
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

    private final DeterministicPcBuilder deterministicPcBuilder;
    private final CompatibilityService compatibilityService;
    private final ChatClient chatClient;
    private final ProductMapper productMapper;

    public BuildGeneratorResponse generate(BuildGeneratorRequest request) {

        // 1. JAVA DOES THE MATH (0% Hallucination)
        List<Product> pickedProducts = deterministicPcBuilder.buildPcForBudget(
                request.getBudget() != null ? request.getBudget().doubleValue() : 30000.0,
                request.getPreferredBrand()
        );

        if (pickedProducts.isEmpty()) {
            throw new AiServiceException("Could not generate a valid build for the given budget.");
        }

        // 2. CHECK COMPATIBILITY
        CompatibilityResult compatibilityResult = compatibilityService.evaluate(pickedProducts);

        if (!compatibilityResult.isCompatible()) {
            log.warn("Generated an incompatible build! Issues: {}",
                    compatibilityResult.getIssues().stream()
                            .map(i -> i.getCategory() + ": " + i.getReason())
                            .collect(Collectors.joining(", ")));
        }

        BigDecimal totalPrice = pickedProducts.stream()
                .map(Product::getPriceEgp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. MAP DTOs AND HIDE BAD GLOBAL DATA
        List<ProductDto> componentPicks = pickedProducts.stream()
                .map(p -> {
                    ProductDto dto = productMapper.toDto(p);
                    dto.setMatchedGlobalName(p.getRawName()); // Hide bad global name
                    dto.setSpecs(new HashMap<>()); // Hide bad specs from frontend
                    return dto;
                })
                .collect(Collectors.toList());

        // 4. ASK AI TO WRITE THE EXPLANATION (Only text generation, no math)
        String userPrompt = buildAiPrompt(request, componentPicks);
        String reasoning;
        try {
            String responseContent = chatClient.prompt()
                    .system(SYSTEM_INSTRUCTION)
                    .user(userPrompt)
                    .call()
                    .content();

            // Safe null check before trimming
            reasoning = responseContent != null ? responseContent.trim() : "";

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