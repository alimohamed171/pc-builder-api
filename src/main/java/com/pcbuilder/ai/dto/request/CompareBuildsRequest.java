package com.pcbuilder.ai.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CompareBuildsRequest {

    @NotEmpty
    @Size(min = 2, message = "at least 2 builds are required to compare")
    private List<Long> buildIds;
}