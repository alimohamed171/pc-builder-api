package com.pcbuilder.bundle.dto;

import com.pcbuilder.bundle.entity.BundleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Used for both create (POST) and edit (PUT) of a bundle. */
@Getter
@Setter
public class BundleSaveRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotNull(message = "type is required")
    private BundleType type;

    @NotEmpty(message = "items must contain at least one product")
    @Valid
    private List<BundleItemRequest> items;
}