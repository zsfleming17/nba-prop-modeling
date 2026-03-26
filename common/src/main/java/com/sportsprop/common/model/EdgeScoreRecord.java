package com.sportsprop.common.model;

public record EdgeScoreRecord(
        String slateDate,
        int playerId,
        StatType statType,
        double lineValue,
        double projectedMean,
        double modelProbabilityOver,
        double edgeScore
) {
}
