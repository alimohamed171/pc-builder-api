package com.pcbuilder.ai.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CompatibilityCheckRequest {

    @NotEmpty
    private List<Long> componentIds;

    private String note;
}