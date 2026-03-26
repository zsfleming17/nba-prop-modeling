package com.sportsprop.common.nba;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsprop.common.model.PlayerGameStatLine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NbaStatsClientParseTest {

    @Test
    void parseLeagueGameLog_mapsRow() throws IOException {
        String json = """
                {
                  "resultSets": [
                    {
                      "name": "LeagueGameLog",
                      "headers": ["PLAYER_ID", "Game_ID", "GAME_DATE", "MATCHUP", "WL", "MIN", "PTS", "REB", "AST", "TEAM_ABBREVIATION"],
                      "rowSet": [
                        [2544, "0022400123", "2025-03-20", "LAL @ BOS", "W", "34:12", 28, 7, 5, "LAL"]
                      ]
                    }
                  ]
                }
                """;
        NbaStatsClient client = new NbaStatsClient(null, new ObjectMapper(), 0);
        List<PlayerGameStatLine> lines = client.parseLeagueGameLog(json);
        assertFalse(lines.isEmpty());
        PlayerGameStatLine g = lines.getFirst();
        assertEquals(2544, g.playerId());
        assertEquals("0022400123", g.gameId());
        assertEquals(LocalDate.of(2025, 3, 20), g.gameDate());
        assertEquals(28, g.points());
        assertEquals(7, g.rebounds());
        assertEquals(5, g.assists());
        assertEquals("LAL", g.teamAbbreviation());
    }

    @Test
    void parseGameDate_acceptsIsoAndUsFormats() {
        assertEquals(LocalDate.of(2025, 1, 5), NbaStatsClient.parseGameDate("2025-01-05"));
        assertEquals(LocalDate.of(2025, 1, 5), NbaStatsClient.parseGameDate("1/5/2025"));
    }
}
