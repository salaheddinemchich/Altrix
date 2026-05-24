package com.altrix.orchestrator.adapter.in.rest.dto;

import java.time.Instant;

/**
 * Response returned by the share-link creation endpoint (#133).
 *
 * @param token     the opaque JWT to put on the wire; the public endpoint
 *                  validates it.
 * @param url       fully-qualified public URL the recipient can open.
 *                  Built from {@code app.public-base-url} + token.
 * @param expiresAt absolute timestamp when the link stops working.
 */
public record ShareTokenResponse(
        String token,
        String url,
        Instant expiresAt
) {}
