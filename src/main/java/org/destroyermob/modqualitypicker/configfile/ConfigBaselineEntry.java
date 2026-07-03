package org.destroyermob.modqualitypicker.configfile;

import java.util.Objects;

public record ConfigBaselineEntry(
        String path,
        String ownerHint,
        String sha256,
        long size,
        String capturedAt
) {
    public ConfigBaselineEntry {
        path = Objects.requireNonNullElse(path, "");
        ownerHint = Objects.requireNonNullElse(ownerHint, "");
        sha256 = Objects.requireNonNullElse(sha256, "");
        size = Math.max(0L, size);
        capturedAt = Objects.requireNonNullElse(capturedAt, "");
    }
}
