package com.altrix.job.adapter.in.rest;

import com.altrix.common.domain.enums.ConfigFormatPreference;
import com.altrix.job.domain.model.JobProviderProfile;
import jakarta.validation.constraints.NotBlank;

public record CreateJobRequest(
        @NotBlank String projectId,
        ConfigFormatPreference configFormatPreference,
        JobProviderProfile providerProfile
) {}
