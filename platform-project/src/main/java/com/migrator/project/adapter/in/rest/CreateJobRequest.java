package com.migrator.project.adapter.in.rest;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateJobRequest {
    @NotBlank(message = "Job name is required")
    private String name;
    @NotBlank(message = "Source topic is required")
    private String sourceTopic;
    @NotBlank(message = "Target topic is required")
    private String targetTopic;
}
