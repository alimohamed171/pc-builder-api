package com.pcbuilder.ai.service;

import com.pcbuilder.ai.client.GeminiClient;
import com.pcbuilder.ai.dto.gemini.GeminiContent;
import com.pcbuilder.ai.dto.request.CompareBuildsRequest;
import com.pcbuilder.ai.dto.response.CompareBuildsResponse;
import com.pcbuilder.bundle.entity.Bundle;
import com.pcbuilder.bundle.entity.BundleItem;
import com.pcbuilder.bundle.repository.BundleRepository;
import com.pcbuilder.exception.BadRequestException;
import com.pcbuilder.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompareBuildsAiService {

    private static final String SYSTEM_INSTRUCTION = """
        You are a PC hardware analyst for pcbuilder. Compare the given builds
        on performance, value for money (EGP), and suitability for gaming,
        content creation, and general/office use. Respond with:
        1. A short summary paragraph.
        2. 3-5 key differences as short bullet points (one per line, starting with "- ").
        3. A final recommendation of which build suits which type of user.
        """;

    private final BundleRepository bundleRepository;
    private final GeminiClient geminiClient;

    public CompareBuildsResponse compare(CompareBuildsRequest request, Long userId) {
        List<Bundle> bundles = request.getBuildIds().stream()
                .map(id -> bundleRepository.findByIdAndUserId(id, userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Bundle not found: id=" + id)))
                .collect(Collectors.toList());

        if (bundles.size() < 2) {
            throw new BadRequestException("At least 2 valid builds are required to compare");
        }

        String buildsSummary = buildSummary(bundles);
        List<GeminiContent> contents = List.of(GeminiContent.of("user", buildsSummary));
        String aiReply = geminiClient.generateText(SYSTEM_INSTRUCTION, contents);

        List<String> keyDifferences = aiReply.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("-"))
                .collect(Collectors.toList());

        return new CompareBuildsResponse(
                request.getBuildIds(),
                aiReply,
                keyDifferences,
                null
        );
    }

    private String buildSummary(List<Bundle> bundles) {
        StringBuilder sb = new StringBuilder();
        for (Bundle bundle : bundles) {
            sb.append("Build #").append(bundle.getId())
                    .append(" \"").append(bundle.getName()).append("\"")
                    .append(" | total price: ").append(bundle.getTotalPrice()).append(" EGP")
                    .append(" | compatible: ").append(bundle.isCompatible())
                    .append("\nComponents:\n");
            for (BundleItem item : bundle.getItems()) {
                sb.append("  - ").append(item.getProduct().getCategory()).append(": ")
                        .append(item.getProduct().getMatchedGlobalName() != null
                                ? item.getProduct().getMatchedGlobalName()
                                : item.getProduct().getRawName())
                        .append(" (qty ").append(item.getQuantity()).append(")\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}