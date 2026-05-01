package com.migrator.job.adapter.in.rest;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import jakarta.validation.constraints.NotBlank;

public record CreateJobRequest(
        @NotBlank String projectId,
        ConfigFormatPreference configFormatPreference
) {}
