package com.sportsprop.common.pp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsprop.common.model.PropLineRecord;
import com.sportsprop.common.model.StatType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PrizePicksLinesParserTest {

    @Test
    void parse_rootObjectWithLines() throws IOException {
        String json = """
                {"date": "2025-03-20", "lines": [{"playerId": 2544, "playerName": "LBJ", "statType": "POINTS", "line": 24.5}]}
                """;
        PrizePicksLinesParser p = new PrizePicksLinesParser(new ObjectMapper());
        List<PropLineRecord> rows = p.parse(json, "2025-03-01");
        assertFalse(rows.isEmpty());
        PropLineRecord r = rows.getFirst();
        assertEquals("2025-03-20", r.slateDate());
        assertEquals(2544, r.playerId());
        assertEquals(StatType.POINTS, r.statType());
        assertEquals(24.5, r.lineValue(), 1e-9);
    }

    @Test
    void parse_usesDefaultDateFromFilenameFallback() throws IOException {
        String json = """
                {"lines": [{"playerId": 100, "statType": "REBOUNDS", "line": 8.5}]}
                """;
        PrizePicksLinesParser p = new PrizePicksLinesParser(new ObjectMapper());
        List<PropLineRecord> rows = p.parse(json, "2025-04-01");
        assertEquals("2025-04-01", rows.getFirst().slateDate());
    }
}
