package com.pcbuilder.ai.service;

import com.pcbuilder.ai.client.GeminiClient;
import com.pcbuilder.ai.dto.gemini.GeminiContent;
import com.pcbuilder.ai.dto.request.CompatibilityCheckRequest;
import com.pcbuilder.ai.dto.response.CompatibilityCheckResponse;
import com.pcbuilder.bundle.dto.CompatibilityIssueDto;
import com.pcbuilder.bundle.dto.CompatibilityResult;
import com.pcbuilder.bundle.service.CompatibilityService;
import com.pcbuilder.common.SpecsUtil;
import com.pcbuilder.exception.BadRequestException;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompatibilityAiService {

    private static final String SYSTEM_INSTRUCTION = """
        You are a PC hardware compatibility expert working alongside a
        deterministic rule engine that already checked socket, form factor,
        PSU wattage, and cooler clearance. The user has an additional
        free-text question or edge case the rule engine cannot evaluate
        (e.g. subtle GPU length vs case clearance, RAM height vs cooler,
        cable routing, BIOS support for a specific CPU on an older
        motherboard revision). Give a concise, practical answer.
        """;

    private final ProductRepository productRepository;
    private final CompatibilityService compatibilityService;
    private final GeminiClient geminiClient;

    public CompatibilityCheckResponse check(CompatibilityCheckRequest request) {
        List<Product> products = productRepository.findByIdIn(request.getComponentIds());

        Set<Long> foundIds = products.stream().map(Product::getId).collect(Collectors.toSet());
        List<Long> missing = request.getComponentIds().stream()
                .filter(id -> !foundIds.contains(id)).collect(Collectors.toList());
        if (!missing.isEmpty()) {
            throw new BadRequestException("Some products were not found: " + missing);
        }

        CompatibilityResult ruleResult = compatibilityService.evaluate(products);

        List<String> issues = ruleResult.getIssues().stream()
                .map(CompatibilityIssueDto::getReason)
                .collect(Collectors.toList());

        if (request.getNote() == null || request.getNote().isBlank()) {
            String explanation = ruleResult.isCompatible()
                    ? "All checked components are compatible."
                    : String.join(" ", issues);
            return new CompatibilityCheckResponse(ruleResult.isCompatible(), issues, explanation, true);
        }

        String specsSummary = buildSpecsSummary(products);
        String ruleContext = ruleResult.isCompatible()
                ? "Rule engine result: no compatibility issues found."
                : "Rule engine found these issues: " + String.join(" ", issues);

        String prompt = specsSummary + "\n\n" + ruleContext + "\n\nUser question: " + request.getNote();

        List<GeminiContent> contents = List.of(GeminiContent.of("user", prompt));
        String aiExplanation = geminiClient.generateText(SYSTEM_INSTRUCTION, contents);

        return new CompatibilityCheckResponse(ruleResult.isCompatible(), issues, aiExplanation, false);
    }

    private String buildSpecsSummary(List<Product> products) {
        StringBuilder sb = new StringBuilder("Selected components:\n");
        for (Product p : products) {
            sb.append("- ").append(p.getCategory()).append(": ")
                    .append(p.getMatchedGlobalName() != null ? p.getMatchedGlobalName() : p.getRawName())
                    .append(" | specs: ").append(SpecsUtil.parse(p.getSpecs()))
                    .append("\n");
        }
        return sb.toString();
    }
}