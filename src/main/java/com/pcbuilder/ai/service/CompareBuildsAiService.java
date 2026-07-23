package com.pcbuilder.ai.service;


import com.pcbuilder.ai.dto.request.CompareBuildsRequest;
import com.pcbuilder.ai.dto.response.CompareBuildsResponse;
import com.pcbuilder.bundle.entity.Bundle;
import com.pcbuilder.bundle.entity.BundleItem;
import com.pcbuilder.bundle.repository.BundleRepository;
import com.pcbuilder.exception.BadRequestException;
import com.pcbuilder.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompareBuildsAiService {

    private static final String SYSTEM_INSTRUCTION = """
            You are a PC hardware analyst for PCBuilder, an Egyptian PC-building platform.
            
            You will receive two or more PC builds. Each build includes:
            - Build name
            - Total price (EGP)
            - Compatibility status
            - List of components
            
            Your task is to compare the builds ONLY using the information provided.
            
            General Rules:
            - Do NOT invent specifications, benchmarks, performance numbers, prices, or compatibility details.
            - If information is missing, state that it cannot be determined from the provided data.
            - If a build is marked as incompatible, clearly mention this and explain that it should be fixed before purchase.
            - Never assume one component is better unless the provided information explicitly supports that conclusion.
            - Be concise, objective, and factual.
            - Write in clear, simple English suitable for a general audience.
            - Keep prices in EGP using thousands separators (e.g. 45,000 EGP).
            
            Return ONLY the following sections in EXACTLY this order:
            
            SUMMARY:
            Write one short paragraph (2-4 sentences, maximum 80 words) summarizing the overall comparison.
            
            DIFFERENCES:
            - Write 3 to 5 bullet points.
            - Every bullet MUST begin with "- ".
            - Focus only on concrete differences such as components, compatibility, or price.
            - Do not include opinions or marketing language.
            - Do not use bullet points anywhere else in the response.
            
            RECOMMENDATION:
            Recommend the most suitable build(s) by their exact build names.
            
            When making recommendations:
            - Prefer compatible builds over incompatible builds.
            - Explain your recommendation using ONLY the provided information.
            - If two or more builds have identical components and compatibility, state that they are equivalent in hardware and recommend choosing the lower-priced build.
            - If both the components and the price are identical, state that the builds are effectively the same and either build can be chosen.           
            - Mention which build is better suited for gaming, content creation, or general/office use only if the provided data supports that conclusion.
            - If two builds are equally suitable, say so.
            - If all builds are incompatible, do NOT recommend any build. Instead, state that compatibility issues should be resolved before making a recommendation.
            
            Do not add any introduction, conclusion, headings, or text outside the required sections.
            """;
    private static final String SUMMARY_MARKER = "SUMMARY:";
    private static final String DIFFERENCES_MARKER = "DIFFERENCES:";
    private static final String RECOMMENDATION_MARKER = "RECOMMENDATION:";

    private final BundleRepository bundleRepository;
    private final ChatClient chatClient;

    public CompareBuildsResponse compare(CompareBuildsRequest request, Long userId) {
        List<Bundle> bundles = request.getBuildIds().stream().map(id -> bundleRepository.findByIdAndUserId(id, userId).orElseThrow(() -> new ResourceNotFoundException("Bundle not found: id=" + id))).collect(Collectors.toList());

        if (bundles.size() < 2) {
            throw new BadRequestException("At least 2 valid builds are required to compare");
        }

        String buildsSummary = buildSummary(bundles);
        String aiReply =
                chatClient.prompt()
                        .system(SYSTEM_INSTRUCTION)
                        .user(buildsSummary)
                        .call()
                        .content();
        if (aiReply == null) {
            throw new RuntimeException("AI service returned null response");
        }

        String differencesSection = extractSection(aiReply, DIFFERENCES_MARKER, RECOMMENDATION_MARKER);
        String recommendation = extractSection(aiReply, RECOMMENDATION_MARKER, null);

        List<String> keyDifferences = differencesSection.lines().map(String::trim).filter(line -> line.startsWith("-")).map(line -> line.replaceFirst("^-\\s*", "")).filter(line -> !line.isBlank()).collect(Collectors.toList());

        return new CompareBuildsResponse(request.getBuildIds(), aiReply, keyDifferences, recommendation.isBlank() ? null : recommendation);
    }

    private String extractSection(String text, String startMarker, String endMarker) {
        int startIdx = text.indexOf(startMarker);
        if (startIdx == -1) {
            return "";
        }
        startIdx += startMarker.length();

        int endIdx = (endMarker != null) ? text.indexOf(endMarker, startIdx) : -1;
        String section = (endIdx != -1) ? text.substring(startIdx, endIdx) : text.substring(startIdx);

        return section.trim();
    }

    private String buildSummary(List<Bundle> bundles) {
        StringBuilder sb = new StringBuilder();
        for (Bundle bundle : bundles) {
            sb.append("Build #").append(bundle.getId()).append(" \"").append(bundle.getName()).append("\"").append(" | total price: ").append(bundle.getTotalPrice()).append(" EGP").append(" | compatible: ").append(bundle.isCompatible()).append("\nComponents:\n");
            for (BundleItem item : bundle.getItems()) {
                sb.append("  - ").append(item.getProduct().getCategory()).append(": ").append(item.getProduct().getMatchedGlobalName() != null ? item.getProduct().getMatchedGlobalName() : item.getProduct().getRawName()).append(" (qty ").append(item.getQuantity()).append(")\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}