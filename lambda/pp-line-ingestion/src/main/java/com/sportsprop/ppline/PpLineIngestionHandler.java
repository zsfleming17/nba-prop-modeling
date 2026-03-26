package com.sportsprop.ppline;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsprop.common.model.PropLineRecord;
import com.sportsprop.common.pp.PrizePicksLinesParser;
import com.sportsprop.common.repo.PropLinesRepository;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PpLineIngestionHandler implements RequestHandler<S3Event, String> {

    private static final Logger LOG = Logger.getLogger(PpLineIngestionHandler.class.getName());
    private static final Pattern DATE_IN_KEY = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.json$");

    @Override
    public String handleRequest(S3Event event, Context context) {
        try {
            return run(event);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "PpLineIngestionHandler IO error", e);
            throw new IllegalStateException("PpLineIngestionHandler failed", e);
        }
    }

    private String run(S3Event event) throws IOException {
        String table = requiredEnv("PROP_LINES_TABLE");
        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        ObjectMapper mapper = new ObjectMapper();
        PrizePicksLinesParser parser = new PrizePicksLinesParser(mapper);

        int written = 0;
        try (DynamoDbClient ddb = DynamoDbClient.builder().region(region).build();
             S3Client s3 = S3Client.builder().region(region).build()) {
            PropLinesRepository repo = new PropLinesRepository(ddb, table);
            for (S3EventNotification.S3EventNotificationRecord rec : event.getRecords()) {
                String bucket = rec.getS3().getBucket().getName();
                String key = URLDecoder.decode(rec.getS3().getObject().getKey(), StandardCharsets.UTF_8);
                String defaultDate = extractDateFromKey(key).orElse("");
                String json = readObjectUtf8(s3, bucket, key);
                for (PropLineRecord line : parser.parse(json, defaultDate)) {
                    repo.put(line);
                    written++;
                }
            }
        }
        LOG.info("Wrote " + written + " prop lines from S3");
        return "OK " + written;
    }

    private static Optional<String> extractDateFromKey(String key) {
        Matcher m = DATE_IN_KEY.matcher(key);
        if (m.find()) {
            return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    private static String readObjectUtf8(S3Client s3, String bucket, String key) throws IOException {
        GetObjectRequest req = GetObjectRequest.builder().bucket(bucket).key(key).build();
        try (ResponseInputStream<GetObjectResponse> stream = s3.getObject(req)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing env " + name);
        }
        return v;
    }
}
