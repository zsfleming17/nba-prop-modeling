package com.sportsprop.common.model;

import java.util.Locale;
import java.util.Optional;

public enum StatType {
    POINTS,
    REBOUNDS,
    ASSISTS;

    public static Optional<StatType> fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String n = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (n.equals("PTS") || n.equals("POINTS")) {
            return Optional.of(POINTS);
        }
        if (n.equals("REB") || n.equals("REBOUNDS") || n.equals("TOTAL_REBOUNDS")) {
            return Optional.of(REBOUNDS);
        }
        if (n.equals("AST") || n.equals("ASSISTS")) {
            return Optional.of(ASSISTS);
        }
        try {
            return Optional.of(StatType.valueOf(n));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
