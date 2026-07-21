package com.pcbuilder.ai.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class CompatibilityCheckRequest {
    private Long buildId;
    private List<Long> existingComponentIds;
    private Long candidateComponentId;
}