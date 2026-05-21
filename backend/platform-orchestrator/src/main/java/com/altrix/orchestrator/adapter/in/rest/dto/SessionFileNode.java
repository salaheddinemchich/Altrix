package com.altrix.orchestrator.adapter.in.rest.dto;

/**
 * Flat tree-node entry used by {@code GET /api/v1/sessions/{id}/files/tree}
 * to drive the project-wide file tree in the diff viewer.
 *
 * <p>The frontend assembles these into a nested tree by splitting {@code path}
 * on {@code "/"} — keeping the API shape minimal.
 *
 * @param path   repository-relative file path (e.g. {@code src/main/java/X.java})
 * @param status one of {@code MODIFIED}, {@code CREATED}, {@code DELETED},
 *               {@code UNCHANGED}, or {@code UNTOUCHED} (file existed in the
 *               source ZIP but the migrator never looked at it because the
 *               pruner excluded it)
 */
public record SessionFileNode(String path, String status) {}
