package com.pcbuilder.ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class BuildGeneratorRequest {

    @NotBlank
    private String prompt;

    private BigDecimal budget;
    private String usage;
    private String preferredBrand;
    private List<String> mustInclude;
}