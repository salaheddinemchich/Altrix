package com.migrator.project.adapter.in.rest;

import com.migrator.common.domain.enums.ConfigFormatPreference;
import jakarta.validation.constraints.NotNull;

/**
 * HTTP request DTO for the upload endpoint.
 *
 * <p>The multipart file itself comes as a {@code @RequestPart} parameter.
 * This record carries the optional metadata sent alongside it.
 */
public record ProjectRequest(

        /**
         * Optional: user's preferred output config format.
         * Defaults to KEEP_ORIGINAL if not provided.
         */
        ConfigFormatPreference configFormatPreference
) {
    public ProjectRequest {
        if (configFormatPreference == null) {
            configFormatPreference = ConfigFormatPreference.KEEP_ORIGINAL;
        }
    }
}
