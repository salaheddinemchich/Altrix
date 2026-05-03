package com.altrix.job.adapter.in.rest;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import jakarta.validation.constraints.NotBlank;

public record CreateJobRequest(
        @NotBlank String projectId,
        ConfigFormatPreference configFormatPreference
) {}
