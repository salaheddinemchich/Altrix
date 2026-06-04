package com.altrix.orchestrator.domain.model.apply;

/**
 * One file's worth of change to be committed.  Bytes-level rather than
 * String so the adapter is free to choose its encoding.  Path is
 * repository-relative (no leading slash).
 *
 * @param path     repository-relative path.
 * @param content  the new file content (null for deletions).
 * @param deletion {@code true} if this represents a removal of {@code path}.
 */
public record BranchFileChange(String path, byte[] content, boolean deletion) {
    public BranchFileChange {
        if (path == null || path.isBlank())     throw new IllegalArgumentException("path is required");
        if (path.startsWith("/"))               throw new IllegalArgumentException("path must be repo-relative");
        if (path.contains(".."))                throw new IllegalArgumentException("path must not contain '..'");
        if (!deletion && content == null)       throw new IllegalArgumentException("content required for non-deletions");
    }

    public static BranchFileChange upsert(String path, byte[] content) {
        return new BranchFileChange(path, content, false);
    }
    public static BranchFileChange delete(String path) {
        return new BranchFileChange(path, null, true);
    }
}
