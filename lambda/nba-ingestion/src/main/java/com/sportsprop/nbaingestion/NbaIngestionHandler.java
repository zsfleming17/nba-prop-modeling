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
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NbaIngestionHandler implements RequestHandler<ScheduledEvent, String> {

    private static final Logger LOG = Logger.getLogger(NbaIngestionHandler.class.getName());
    private static final long NBA_REQUEST_INTERVAL_MS = 550;

    static final String ENV_NBA_INGEST_SOURCE = "NBA_INGEST_SOURCE";
    static final String MODE_S3_THEN_HTTP = "S3_THEN_HTTP";
    static final String MODE_S3 = "S3";
    static final String MODE_HTTP = "HTTP";

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

        String mode = System.getenv().getOrDefault(ENV_NBA_INGEST_SOURCE, MODE_S3_THEN_HTTP).trim().toUpperCase();
        boolean tryS3 = MODE_S3.equals(mode) || MODE_S3_THEN_HTTP.equals(mode);
        boolean tryHttp = MODE_HTTP.equals(mode) || MODE_S3_THEN_HTTP.equals(mode);

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        NbaStatsClient nba = new NbaStatsClient(NbaStatsClient.newDefaultHttpClient(), mapper, NBA_REQUEST_INTERVAL_MS);

        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        try (DynamoDbClient ddb = DynamoDbClient.builder().region(region).build();
             S3Client s3 = S3Client.builder().region(region).build()) {
            List<PlayerGameStatLine> lines = resolveLines(s3, bucket, nba, season, gameDate, tryS3, tryHttp);
            PlayerGameStatsRepository repo = new PlayerGameStatsRepository(ddb, table);
            repo.putBatch(lines);

            String key = "nba/raw/" + gameDate + ".json";
            String json = mapper.writeValueAsString(lines);
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/json").build(),
                    RequestBody.fromString(json));
            LOG.info("Ingested " + lines.size() + " player game rows for " + gameDate);
            return "OK " + lines.size() + " rows " + gameDate;
        }
    }

    private static List<PlayerGameStatLine> resolveLines(
            S3Client s3,
            String bucket,
            NbaStatsClient nba,
            String season,
            LocalDate gameDate,
            boolean tryS3,
            boolean tryHttp
    ) throws Exception {
        String apiKey = "nba/api/" + gameDate + ".json";
        if (tryS3) {
            try (ResponseInputStream<GetObjectResponse> stream = s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(apiKey).build())) {
                String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                List<PlayerGameStatLine> fromS3 = nba.parseLeagueGameLog(body);
                LOG.info("Loaded NBA game log from S3 key=" + apiKey + " rows=" + fromS3.size());
                return fromS3;
            } catch (NoSuchKeyException e) {
                LOG.info("S3 object not found: " + apiKey + " — will try HTTP if enabled");
                if (!tryHttp) {
                    throw new IllegalStateException(
                            "NBA_INGEST_SOURCE requires S3 but object missing: s3://" + bucket + "/" + apiKey, e);
                }
            }
        } else if (!tryHttp) {
            throw new IllegalStateException("Invalid NBA_INGEST_SOURCE: need S3 and/or HTTP enabled");
        }
        if (tryHttp) {
            List<PlayerGameStatLine> fromHttp = nba.fetchPlayerGameLogForDay(season, gameDate);
            LOG.info("Loaded NBA game log via HTTP rows=" + fromHttp.size());
            return fromHttp;
        }
        throw new IllegalStateException("No NBA data source succeeded for " + gameDate);
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing env " + name);
        }
        return v;
    }
}
