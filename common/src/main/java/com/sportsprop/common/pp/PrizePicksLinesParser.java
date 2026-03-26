package com.sportsprop.common.pp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsprop.common.model.PropLineRecord;
import com.sportsprop.common.model.StatType;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Expected JSON shapes:
 * <pre>
 * { "date": "2025-03-20", "lines": [ { "playerId": 2544, "playerName": "...", "statType": "POINTS", "line": 24.5 } ] }
 * </pre>
 * or top-level array of line objects with optional root {@code date}.
 */
public class PrizePicksLinesParser {

    private final ObjectMapper objectMapper;

    public PrizePicksLinesParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<PropLineRecord> parse(String json, String defaultDateIso) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        String slateDate = defaultDateIso;
        JsonNode linesNode;
        if (root.isObject()) {
            if (root.hasNonNull("date")) {
                slateDate = root.get("date").asText(defaultDateIso);
            }
            linesNode = root.get("lines");
            if (linesNode == null || !linesNode.isArray()) {
                linesNode = root.get("props");
            }
        } else {
            linesNode = root;
        }
        if (linesNode == null || !linesNode.isArray()) {
            return List.of();
        }
        List<PropLineRecord> out = new ArrayList<>();
        for (JsonNode n : linesNode) {
            Optional<PropLineRecord> rec = mapLine(n, slateDate);
            rec.ifPresent(out::add);
        }
        return out;
    }

    public List<PropLineRecord> parse(InputStream in, String defaultDateIso) throws IOException {
        return parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), defaultDateIso);
    }

    private Optional<PropLineRecord> mapLine(JsonNode n, String slateDate) {
        if (n == null || !n.isObject()) {
            return Optional.empty();
        }
        int playerId = intField(n, "playerId", "player_id", "nbaPlayerId");
        if (playerId <= 0) {
            return Optional.empty();
        }
        String date = textField(n, "date", "slateDate");
        String effectiveDate = date != null && !date.isBlank() ? date : slateDate;
        if (effectiveDate == null || effectiveDate.isBlank()) {
            return Optional.empty();
        }
        String statRaw = textField(n, "statType", "stat", "market");
        Optional<StatType> st = StatType.fromString(statRaw);
        if (st.isEmpty()) {
            return Optional.empty();
        }
        double line = doubleField(n, "line", "lineValue", "projection");
        String name = textField(n, "playerName", "name");
        return Optional.of(new PropLineRecord(effectiveDate, playerId, name == null ? "" : name, st.get(), line));
    }

    private static String textField(JsonNode n, String... names) {
        for (String key : names) {
            if (n.hasNonNull(key)) {
                return n.get(key).asText();
            }
        }
        return null;
    }

    private static int intField(JsonNode n, String... names) {
        for (String key : names) {
            if (n.has(key) && !n.get(key).isNull()) {
                JsonNode v = n.get(key);
                if (v.isInt() || v.isLong()) {
                    return v.asInt();
                }
                try {
                    return Integer.parseInt(v.asText().trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static double doubleField(JsonNode n, String... names) {
        for (String key : names) {
            if (n.has(key) && !n.get(key).isNull()) {
                JsonNode v = n.get(key);
                if (v.isNumber()) {
                    return v.asDouble();
                }
                try {
                    return Double.parseDouble(v.asText().trim());
                } catch (NumberFormatException ignored) {
                    return 0.0;
                }
            }
        }
        return 0.0;
    }
}
