package com.sportsprop.nbaingestion;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportsprop.common.model.PlayerGameStatLine;
import com.sportsprop.common.nba.NbaSeasonUtil;
import com.sportsprop.common.nba.NbaStatsClient;
import com.sportsprop.common.repo.PlayerGameStatsRepository;
import com.sportsprop.common.schedule.ScheduleDetailUtil;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NbaIngestionHandler implements RequestHandler<ScheduledEvent, String> {

    private static final Logger LOG = Logger.getLogger(NbaIngestionHandler.class.getName());
    private static final long NBA_REQUEST_INTERVAL_MS = 550;

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        try {
            return run(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.SEVERE, "NbaIngestionHandler interrupted", e);
            throw new IllegalStateException("Interrupted", e);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "NbaIngestionHandler failed", e);
            throw new IllegalStateException("NbaIngestionHandler failed", e);
        }
    }

    private String run(ScheduledEvent event) throws Exception {
        String table = requiredEnv("PLAYER_GAME_STATS_TABLE");
        String bucket = requiredEnv("DATA_BUCKET");

        String dateOverride = ScheduleDetailUtil.readDetailField(event, "gameDate");
        LocalDate gameDate = dateOverride != null && !dateOverride.isBlank()
                ? LocalDate.parse(dateOverride, DateTimeFormatter.ISO_LOCAL_DATE)
                : NbaSeasonUtil.utcToday().minusDays(1);
        String season = NbaSeasonUtil.seasonForDate(gameDate);

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        NbaStatsClient nba = new NbaStatsClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                mapper, NBA_REQUEST_INTERVAL_MS);
        List<PlayerGameStatLine> lines = nba.fetchPlayerGameLogForDay(season, gameDate);

        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        try (DynamoDbClient ddb = DynamoDbClient.builder().region(region).build();
             S3Client s3 = S3Client.builder().region(region).build()) {
            PlayerGameStatsRepository repo = new PlayerGameStatsRepository(ddb, table);
            repo.putBatch(lines);

            String key = "nba/raw/" + gameDate + ".json";
            String json = mapper.writeValueAsString(lines);
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/json").build(),
                    RequestBody.fromString(json));
        }

        LOG.info("Ingested " + lines.size() + " player game rows for " + gameDate);
        return "OK " + lines.size() + " rows " + gameDate;
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing env " + name);
        }
        return v;
    }
}
