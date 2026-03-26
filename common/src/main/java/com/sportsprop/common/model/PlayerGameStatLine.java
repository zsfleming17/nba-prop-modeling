package com.sportsprop.common.model;

import java.time.LocalDate;

public record PlayerGameStatLine(
        int playerId,
        String gameId,
        LocalDate gameDate,
        int points,
        int rebounds,
        int assists,
        String teamAbbreviation,
        String matchup,
        String minutesPlayed
) {
}
