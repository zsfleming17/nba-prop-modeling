package com.sportsprop.common.model;

public record OutcomeRecord(
        String gameId,
        String gameDate,
        int playerId,
        StatType statType,
        double lineValue,
        int actualValue,
        boolean overHit,
        Double predictedProbabilityOver
) {
}
