package com.sportsprop.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportsprop.common.model.EdgeScoreRecord;
import com.sportsprop.common.model.OutcomeRecord;
import com.sportsprop.common.repo.EdgeScoresRepository;
import com.sportsprop.common.repo.OutcomesRepository;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ApiHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger LOG = Logger.getLogger(ApiHandler.class.getName());
    private static final String JSON = "application/json";

    private final ObjectMapper mapper = new ObjectMapper();

    public ApiHandler() {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            return route(event);
        } catch (JsonProcessingException e) {
            LOG.log(Level.SEVERE, "ApiHandler JSON error", e);
            return response(500, "{\"error\":\"serialization\"}");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "ApiHandler failed", e);
            return response(500, "{\"error\":\"internal\"}");
        }
    }

    private APIGatewayV2HTTPResponse route(APIGatewayV2HTTPEvent event) throws JsonProcessingException {
        String path = event.getRawPath() == null ? "" : event.getRawPath();
        String method = event.getRequestContext() != null && event.getRequestContext().getHttp() != null
                ? event.getRequestContext().getHttp().getMethod()
                : "GET";

        if (!"GET".equalsIgnoreCase(method)) {
            return response(405, "{\"error\":\"method not allowed\"}");
        }

        if (path.endsWith("/edges") || "/edges".equals(path)) {
            return edges(event);
        }
        if (path.contains("/player/") && path.endsWith("/history")) {
            return playerHistory(event, path);
        }

        return response(404, "{\"error\":\"not found\"}");
    }

    private APIGatewayV2HTTPResponse edges(APIGatewayV2HTTPEvent event) throws JsonProcessingException {
        Map<String, String> q = event.getQueryStringParameters();
        if (q == null || !q.containsKey("date") || q.get("date") == null || q.get("date").isBlank()) {
            return response(400, "{\"error\":\"required query param: date\"}");
        }
        String date = q.get("date").trim();
        String edgesTable = requiredEnv("EDGE_SCORES_TABLE");
        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        try (DynamoDbClient ddb = DynamoDbClient.builder().region(region).build()) {
            EdgeScoresRepository repo = new EdgeScoresRepository(ddb, edgesTable);
            List<EdgeScoreRecord> rows = repo.queryByDateSorted(date);
            return json(200, Map.of("date", date, "edges", rows));
        }
    }

    private APIGatewayV2HTTPResponse playerHistory(APIGatewayV2HTTPEvent event, String path) throws JsonProcessingException {
        Map<String, String> pathParams = event.getPathParameters();
        String idStr = pathParams != null ? pathParams.get("id") : null;
        if (idStr == null || idStr.isBlank()) {
            idStr = extractPlayerIdFromPath(path);
        }
        if (idStr == null || idStr.isBlank()) {
            return response(400, "{\"error\":\"missing player id\"}");
        }
        int playerId;
        try {
            playerId = Integer.parseInt(idStr.trim());
        } catch (NumberFormatException e) {
            return response(400, "{\"error\":\"invalid player id\"}");
        }

        int limit = 50;
        Map<String, String> q = event.getQueryStringParameters();
        if (q != null && q.containsKey("limit")) {
            try {
                limit = Math.min(200, Math.max(1, Integer.parseInt(q.get("limit").trim())));
            } catch (NumberFormatException ignored) {
                limit = 50;
            }
        }

        String edgesTable = requiredEnv("EDGE_SCORES_TABLE");
        String outcomesTable = requiredEnv("OUTCOMES_TABLE");
        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        try (DynamoDbClient ddb = DynamoDbClient.builder().region(region).build()) {
            EdgeScoresRepository edges = new EdgeScoresRepository(ddb, edgesTable);
            OutcomesRepository outcomes = new OutcomesRepository(ddb, outcomesTable);
            List<EdgeScoreRecord> edgeRows = edges.queryByPlayer(playerId, limit);
            List<OutcomeRecord> outcomeRows = outcomes.queryByPlayer(playerId, limit);
            return json(200, Map.of(
                    "playerId", playerId,
                    "edges", edgeRows,
                    "outcomes", outcomeRows
            ));
        }
    }

    private static String extractPlayerIdFromPath(String path) {
        int i = path.indexOf("/player/");
        if (i < 0) {
            return null;
        }
        String rest = path.substring(i + "/player/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        return rest.substring(0, slash);
    }

    private APIGatewayV2HTTPResponse json(int status, Object body) throws JsonProcessingException {
        return response(status, mapper.writeValueAsString(body));
    }

    private static APIGatewayV2HTTPResponse response(int status, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", JSON);
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(status)
                .withHeaders(headers)
                .withBody(body)
                .build();
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing env " + name);
        }
        return v;
    }
}
