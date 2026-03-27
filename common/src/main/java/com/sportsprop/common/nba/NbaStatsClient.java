package com.sportsprop.common.nba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsprop.common.model.PlayerGameStatLine;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client for stats.nba.com. Uses polite delay between requests.
 * HTTP calls use bounded connect and per-request timeouts.
 */
public class NbaStatsClient {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final String BASE = "https://stats.nba.com/stats/leaguegamelog";

    /**
     * Shared {@link HttpClient} for Lambda and tests (connect timeout only; each {@link HttpRequest} sets read timeout).
     */
    public static HttpClient newDefaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final long minIntervalMs;

    public NbaStatsClient(HttpClient httpClient, ObjectMapper objectMapper, long minIntervalMs) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.minIntervalMs = minIntervalMs;
    }

    private long lastRequestAt;

    public List<PlayerGameStatLine> fetchPlayerGameLogForDay(String season, LocalDate day) throws IOException, InterruptedException {
        String iso = day.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String query = "LeagueID=00&PlayerOrTeam=P&SeasonType=Regular%20Season&Season="
                + URLEncoder.encode(season, StandardCharsets.UTF_8)
                + "&DateFrom=" + iso
                + "&DateTo=" + iso
                + "&Direction=DESC&Counter=0";
        URI uri = URI.create(BASE + "?" + query);
        throttle();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (compatible; SportsPropModel/1.0)")
                .header("Referer", "https://www.nba.com/")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("NBA API status " + response.statusCode() + " for " + uri);
        }
        return parseLeagueGameLog(response.body());
    }

    private synchronized void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long wait = minIntervalMs - (now - lastRequestAt);
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    /**
     * Parses stats.nba.com league game log JSON (or equivalent {@code nba_api} {@code LeagueGameLog} response).
     */
    public List<PlayerGameStatLine> parseLeagueGameLog(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode resultSets = root.get("resultSets");
        if (resultSets == null || !resultSets.isArray() || resultSets.isEmpty()) {
            return List.of();
        }
        JsonNode set = resultSets.get(0);
        JsonNode headersNode = set.get("headers");
        JsonNode rows = set.get("rowSet");
        if (headersNode == null || rows == null || !headersNode.isArray() || !rows.isArray()) {
            return List.of();
        }
        Map<String, Integer> headerIndex = new HashMap<>();
        for (int i = 0; i < headersNode.size(); i++) {
            headerIndex.put(headersNode.get(i).asText(), i);
        }
        List<PlayerGameStatLine> out = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isArray()) {
                continue;
            }
            PlayerGameStatLine line = mapRow(row, headerIndex);
            if (line != null) {
                out.add(line);
            }
        }
        return out;
    }

    private PlayerGameStatLine mapRow(JsonNode row, Map<String, Integer> headerIndex) {
        Integer playerCol = idx(headerIndex, "PLAYER_ID");
        Integer gameCol = idx(headerIndex, "Game_ID");
        if (playerCol == null || gameCol == null) {
            playerCol = idx(headerIndex, "Player_ID");
            gameCol = idx(headerIndex, "GAME_ID");
        }
        if (playerCol == null || gameCol == null) {
            return null;
        }
        String gameDateRaw = textAt(row, headerIndex, "GAME_DATE");
        LocalDate gameDate = parseGameDate(gameDateRaw);
        if (gameDate == null) {
            return null;
        }
        int pts = intAt(row, headerIndex, "PTS", 0);
        int reb = intAt(row, headerIndex, "REB", 0);
        int ast = intAt(row, headerIndex, "AST", 0);
        String team = textAt(row, headerIndex, "TEAM_ABBREVIATION");
        if (team == null) {
            team = "";
        }
        String matchup = textAt(row, headerIndex, "MATCHUP");
        if (matchup == null) {
            matchup = "";
        }
        String min = textAt(row, headerIndex, "MIN");
        if (min == null) {
            min = "0:00";
        }
        return new PlayerGameStatLine(
                row.get(playerCol).asInt(),
                row.get(gameCol).asText(),
                gameDate,
                pts,
                reb,
                ast,
                team,
                matchup,
                min
        );
    }

    private static Integer idx(Map<String, Integer> headerIndex, String key) {
        return headerIndex.get(key);
    }

    private static String textAt(JsonNode row, Map<String, Integer> headerIndex, String col) {
        Integer i = headerIndex.get(col);
        if (i == null || i >= row.size()) {
            return null;
        }
        JsonNode n = row.get(i);
        return n.isNull() ? null : n.asText();
    }

    private static int intAt(JsonNode row, Map<String, Integer> headerIndex, String col, int def) {
        Integer i = headerIndex.get(col);
        if (i == null || i >= row.size()) {
            return def;
        }
        JsonNode n = row.get(i);
        if (n == null || n.isNull()) {
            return def;
        }
        String s = n.asText();
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static final DateTimeFormatter[] GAME_DATE_FORMATTERS = new DateTimeFormatter[]{
            DateTimeFormatter.ISO_LOCAL_DATE,
            new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern("MMM d, yyyy")
                    .toFormatter(Locale.US),
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US)
    };

    static LocalDate parseGameDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        for (DateTimeFormatter f : GAME_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        try {
            return LocalDate.parse(s, new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd")
                    .optionalStart()
                    .appendPattern(" HH:mm:ss")
                    .optionalEnd()
                    .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                    .toFormatter());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
