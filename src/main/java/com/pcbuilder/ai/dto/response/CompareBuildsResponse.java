package com.pcbuilder.ai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CompareBuildsResponse {
    private List<String> buildNames;
    private String comparisonSummary;
    private List<String> keyDifferences;
    private String recommendation;
}