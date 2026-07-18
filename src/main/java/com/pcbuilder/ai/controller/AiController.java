package com.pcbuilder.ai.controller;

import com.pcbuilder.ai.dto.request.*;
import com.pcbuilder.ai.dto.response.*;
import com.pcbuilder.ai.service.*;
import com.pcbuilder.common.ApiResponse;
import com.pcbuilder.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiChatService aiChatService;
    private final BuildGeneratorAiService buildGeneratorAiService;
    private final CompareBuildsAiService compareBuildsAiService;
    private final CompatibilityAiService compatibilityAiService;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(
            @Valid @RequestBody ChatRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        ChatResponse response = aiChatService.chat(request, principal.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/build-generator")
    public ResponseEntity<ApiResponse<BuildGeneratorResponse>> generateBuild(
            @Valid @RequestBody BuildGeneratorRequest request) {
        BuildGeneratorResponse response = buildGeneratorAiService.generate(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/compare-builds")
    public ResponseEntity<ApiResponse<CompareBuildsResponse>> compareBuilds(
            @Valid @RequestBody CompareBuildsRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        CompareBuildsResponse response = compareBuildsAiService.compare(request, principal.getId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/compatibility-check")
    public ResponseEntity<ApiResponse<CompatibilityCheckResponse>> checkCompatibility(
            @Valid @RequestBody CompatibilityCheckRequest request) {
        CompatibilityCheckResponse response = compatibilityAiService.check(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}