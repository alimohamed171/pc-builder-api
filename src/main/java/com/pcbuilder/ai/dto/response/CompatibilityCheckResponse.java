package com.pcbuilder.ai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CompatibilityCheckResponse {
    private boolean compatible;
    private List<CompatibilityIssueResponse> issues;
    private List<CompatibilityIssueResponse> warnings;
    private String explanation;
    private boolean resolvedByRuleEngine;
}