package com.sportsprop.common.repo;

import com.sportsprop.common.ddb.DdbKeys;
import com.sportsprop.common.model.PlayerGameStatLine;
import com.sportsprop.common.model.StatType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PlayerGameStatsRepository {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public PlayerGameStatsRepository(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    public void putBatch(List<PlayerGameStatLine> lines) {
        for (PlayerGameStatLine line : lines) {
            put(line);
        }
    }

    public void put(PlayerGameStatLine line) {
        String dateStr = line.gameDate().format(ISO);
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.builder().s(DdbKeys.playerPk(line.playerId())).build());
        item.put("sk", AttributeValue.builder().s(DdbKeys.gameSk(line.gameId(), dateStr)).build());
        item.put("playerId", AttributeValue.builder().n(String.valueOf(line.playerId())).build());
        item.put("gameId", AttributeValue.builder().s(line.gameId()).build());
        item.put("gameDate", AttributeValue.builder().s(dateStr).build());
        item.put("points", AttributeValue.builder().n(String.valueOf(line.points())).build());
        item.put("rebounds", AttributeValue.builder().n(String.valueOf(line.rebounds())).build());
        item.put("assists", AttributeValue.builder().n(String.valueOf(line.assists())).build());
        item.put("teamAbbreviation", AttributeValue.builder().s(line.teamAbbreviation()).build());
        item.put("matchup", AttributeValue.builder().s(line.matchup()).build());
        item.put("minutesPlayed", AttributeValue.builder().s(line.minutesPlayed()).build());

        dynamoDbClient.putItem(b -> b.tableName(tableName).item(item));
    }

    public List<PlayerGameStatLine> queryByPlayer(int playerId, int limit) {
        QueryRequest req = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s(DdbKeys.playerPk(playerId)).build()
                ))
                .scanIndexForward(false)
                .limit(Math.min(limit, 100))
                .build();
        List<PlayerGameStatLine> out = new ArrayList<>();
        for (QueryResponse page : dynamoDbClient.queryPaginator(req)) {
            for (Map<String, AttributeValue> item : page.items()) {
                if (out.size() >= limit) {
                    return out;
                }
                out.add(fromItem(item));
            }
        }
        return out;
    }

    public Optional<PlayerGameStatLine> findMostRecentOnDate(int playerId, LocalDate date, int scanDepth) {
        for (PlayerGameStatLine g : queryByPlayer(playerId, scanDepth)) {
            if (g.gameDate().equals(date)) {
                return Optional.of(g);
            }
        }
        return Optional.empty();
    }

    public Optional<PlayerGameStatLine> getForGame(int playerId, String gameId, LocalDate gameDate) {
        String dateStr = gameDate.format(ISO);
        var resp = dynamoDbClient.getItem(b -> b
                .tableName(tableName)
                .key(Map.of(
                        "pk", AttributeValue.builder().s(DdbKeys.playerPk(playerId)).build(),
                        "sk", AttributeValue.builder().s(DdbKeys.gameSk(gameId, dateStr)).build()
                )));
        if (!resp.hasItem() || resp.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromItem(resp.item()));
    }

    public double rollingAverage(int playerId, StatType statType, int lastNGames) {
        List<PlayerGameStatLine> recent = queryByPlayer(playerId, lastNGames);
        if (recent.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        int n = 0;
        for (PlayerGameStatLine g : recent) {
            sum += switch (statType) {
                case POINTS -> g.points();
                case REBOUNDS -> g.rebounds();
                case ASSISTS -> g.assists();
            };
            n++;
        }
        return sum / n;
    }

    private static PlayerGameStatLine fromItem(Map<String, AttributeValue> item) {
        return new PlayerGameStatLine(
                Integer.parseInt(item.get("playerId").n()),
                item.get("gameId").s(),
                LocalDate.parse(item.get("gameDate").s()),
                Integer.parseInt(item.get("points").n()),
                Integer.parseInt(item.get("rebounds").n()),
                Integer.parseInt(item.get("assists").n()),
                item.get("teamAbbreviation").s(),
                item.get("matchup").s(),
                item.get("minutesPlayed").s()
        );
    }
}
