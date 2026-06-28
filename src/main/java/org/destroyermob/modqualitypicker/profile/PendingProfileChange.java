package org.destroyermob.modqualitypicker.profile;

import java.time.Instant;
import java.util.Objects;

public record PendingProfileChange(
        int schemaVersion,
        String reason,
        String sourceWorldId,
        String queuedAt,
        QualityProfile profile
) {
    public static final int SCHEMA_VERSION = 1;

    public PendingProfileChange {
        schemaVersion = schemaVersion <= 0 ? SCHEMA_VERSION : schemaVersion;
        reason = Objects.requireNonNullElse(reason, "");
        sourceWorldId = Objects.requireNonNullElse(sourceWorldId, "");
        queuedAt = queuedAt == null || queuedAt.isBlank() ? Instant.now().toString() : queuedAt;
        profile = profile == null ? QualityProfile.empty("balanced", "Balanced") : profile;
    }

    public static PendingProfileChange of(QualityProfile profile, String reason, String sourceWorldId) {
        return new PendingProfileChange(SCHEMA_VERSION, reason, sourceWorldId, Instant.now().toString(), profile);
    }
}

