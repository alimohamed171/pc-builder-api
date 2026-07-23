package com.pcbuilder.bundle.controller;

import com.pcbuilder.bundle.dto.BundleResponseDto;
import com.pcbuilder.bundle.dto.BundleSaveRequest;
import com.pcbuilder.bundle.entity.BundleType;
import com.pcbuilder.bundle.service.BundleService;
import com.pcbuilder.common.ApiResponse;
import com.pcbuilder.common.PageResponse;
import com.pcbuilder.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bundles")
@RequiredArgsConstructor
public class BundleController {

    private final BundleService bundleService;

    /** Create a bundle. Always saved; response tells the user whether it's compatible. */
    @PostMapping
    public ResponseEntity<ApiResponse<BundleResponseDto>> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody BundleSaveRequest request) {

        BundleService.BundleResult result = bundleService.create(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, result.getMessage(), result.getData()));
    }

    /** Edit an existing bundle (replaces its items and re-checks compatibility). */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<BundleResponseDto>> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody BundleSaveRequest request) {

        BundleService.BundleResult result = bundleService.update(principal.getId(), id, request);
        return ResponseEntity.ok(new ApiResponse<>(true, result.getMessage(), result.getData()));
    }

    /** Retrieve a single bundle owned by the current user. */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BundleResponseDto>> getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {

        BundleResponseDto bundle = bundleService.getById(principal.getId(), id);
        return ResponseEntity.ok(ApiResponse.success("Bundle fetched successfully", bundle));
    }

    /**
     * Retrieve all bundles owned by the current user, paginated.
     * Optionally filter by type (e.g. /api/bundles?type=GAMING); if omitted, all types are returned.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<BundleResponseDto>>> getMyBundles(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) BundleType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<BundleResponseDto> bundles = bundleService.getUserBundles(principal.getId(), type, page, size);
        return ResponseEntity.ok(ApiResponse.success("Bundles fetched successfully", PageResponse.from(bundles)));
    }
}