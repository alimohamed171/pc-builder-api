package com.pcbuilder.ai.service;

import com.pcbuilder.ai.client.GeminiClient;
import com.pcbuilder.ai.dto.gemini.GeminiContent;
import com.pcbuilder.ai.dto.request.CompatibilityCheckRequest;
import com.pcbuilder.ai.dto.response.CompatibilityCheckResponse;
import com.pcbuilder.ai.dto.response.CompatibilityIssueResponse;
import com.pcbuilder.bundle.entity.Bundle;
import com.pcbuilder.bundle.entity.BundleItem;
import com.pcbuilder.bundle.dto.CompatibilityIssueDto;
import com.pcbuilder.bundle.dto.CompatibilityResult;
import com.pcbuilder.bundle.repository.BundleRepository;
import com.pcbuilder.bundle.service.CompatibilityService;
import com.pcbuilder.common.SpecsUtil;
import com.pcbuilder.exception.BadRequestException;
import com.pcbuilder.exception.ResourceNotFoundException;
import com.pcbuilder.product.entity.Product;
import com.pcbuilder.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompatibilityAiService {

    private static final String SYSTEM_INSTRUCTION = """
        You are a PC hardware compatibility expert working alongside a
        deterministic rule engine that already checked socket, form factor,
        PSU wattage, and cooler clearance. Look for soft/edge-case risks the
        fixed rule matrix cannot express (e.g. GPU length vs case clearance
        margins, RAM heatsink height vs cooler, cable routing, BIOS support
        for a specific CPU on an older motherboard revision).

        The rule engine's compatible/incompatible verdict is always the
        source of truth - never contradict it, only add nuance on top.
        For every soft risk you identify, put it on its own line prefixed
        with exactly "CAUTION: <rule_code>: <message>", where rule_code is a short
        UPPER_SNAKE_CASE label (e.g. "CASE_GPU_LENGTH"). Keep the rest of
        your answer concise and practical.
        """;

    private final ProductRepository productRepository;
    private final BundleRepository bundleRepository;
    private final CompatibilityService compatibilityService;
    private final GeminiClient geminiClient;
    private final ChatClient chatClient;

    public CompatibilityCheckResponse check(CompatibilityCheckRequest request, Long userId) {
        if (request.getBuildId() != null && request.getExistingComponentIds() != null
                && !request.getExistingComponentIds().isEmpty()) {
            throw new BadRequestException("buildId and existingComponentIds are mutually exclusive");
        }

        List<Product> existingProducts = resolveExistingProducts(request, userId);
        List<Product> products = new ArrayList<>(existingProducts);

        if (request.getCandidateComponentId() != null) {
            Product candidate = productRepository.findById(request.getCandidateComponentId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Component not found: id=" + request.getCandidateComponentId()));
            products.add(candidate);
        }
        if (products.isEmpty()) {
            throw new BadRequestException("No components provided to check");
        }

        CompatibilityResult ruleResult = compatibilityService.evaluate(products);
        List<CompatibilityIssueResponse> issues = mapIssues(ruleResult);

        // Respect the requested compatibility mode from the API spec
        if ("RULE_BASED".equalsIgnoreCase(request.getMode())) {
            String explanation = ruleResult.isCompatible()
                    ? "All checked components are compatible."
                    : issues.stream().map(CompatibilityIssueResponse::getMessage)
                    .collect(Collectors.joining(" "));
            return new CompatibilityCheckResponse(
                    ruleResult.isCompatible(), issues, List.of(), explanation, true);
        }

        String aiExplanation = "";
        List<CompatibilityIssueResponse> aiWarnings = List.of();

        try {
            String specsSummary = buildSpecsSummary(products);
            String ruleContext = ruleResult.isCompatible()
                    ? "Rule engine result: no compatibility issues found."
                    : "Rule engine found these issues: "
                    + issues.stream().map(CompatibilityIssueResponse::getMessage)
                    .collect(Collectors.joining(" "));

            String prompt = specsSummary + "\n\n" + ruleContext;

            aiExplanation = chatClient.prompt()
                    .system(SYSTEM_INSTRUCTION)
                    .user(prompt)
                    .call()
                    .content();

            if (aiExplanation != null) {
                aiWarnings = extractAiWarnings(aiExplanation);
            }
        } catch (Exception e) {
            // Graceful fallback to rule-based response if AI service fails
            String explanation = ruleResult.isCompatible()
                    ? "All checked components are compatible."
                    : issues.stream().map(CompatibilityIssueResponse::getMessage)
                    .collect(Collectors.joining(" "));
            return new CompatibilityCheckResponse(
                    ruleResult.isCompatible(), issues, List.of(), explanation, true);
        }

        return new CompatibilityCheckResponse(
                ruleResult.isCompatible(), issues, aiWarnings, aiExplanation, false);
    }

    private List<Product> resolveExistingProducts(CompatibilityCheckRequest request, Long userId) {
        if (request.getBuildId() != null) {
            Bundle bundle = bundleRepository.findByIdAndUserId(request.getBuildId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Bundle not found: id=" + request.getBuildId()));
            return bundle.getItems().stream()
                    .map(BundleItem::getProduct)
                    .collect(Collectors.toList());
        }

        if (request.getExistingComponentIds() != null && !request.getExistingComponentIds().isEmpty()) {
            List<Product> found = productRepository.findByIdIn(request.getExistingComponentIds());
            Set<Long> foundIds = found.stream().map(Product::getId).collect(Collectors.toSet());
            List<Long> missing = request.getExistingComponentIds().stream()
                    .filter(id -> !foundIds.contains(id)).toList();
            if (!missing.isEmpty()) {
                throw new BadRequestException("Some products were not found: " + missing);
            }
            return found;
        }

        return List.of();
    }

    private List<CompatibilityIssueResponse> mapIssues(CompatibilityResult ruleResult) {
        return ruleResult.getIssues().stream()
                .map(i -> new CompatibilityIssueResponse(i.getCategory(), i.getReason()))
                .collect(Collectors.toList());
    }

    private List<CompatibilityIssueResponse> extractAiWarnings(String aiExplanation) {
        return aiExplanation.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("CAUTION:"))
                .map(this::parseCautionLine)
                .collect(Collectors.toList());
    }

    private CompatibilityIssueResponse parseCautionLine(String line) {
        String rest = line.replaceFirst("^CAUTION:\\s*", "");
        int colonIdx = rest.indexOf(':');
        if (colonIdx == -1) {
            return new CompatibilityIssueResponse("AI_CAUTION", rest);
        }
        String rule = rest.substring(0, colonIdx).trim();
        String message = rest.substring(colonIdx + 1).trim();
        return new CompatibilityIssueResponse(rule, message);
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