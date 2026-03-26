package com.sportsprop.common.model;

public record PropLineRecord(
        String slateDate,
        int playerId,
        String playerName,
        StatType statType,
        double lineValue
) {
}
