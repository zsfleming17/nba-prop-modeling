package com.sportsprop.common.repo;

import com.sportsprop.common.ddb.DdbKeys;
import com.sportsprop.common.model.EdgeScoreRecord;
import com.sportsprop.common.model.StatType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class EdgeScoresRepository {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public EdgeScoresRepository(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    public Optional<EdgeScoreRecord> get(String slateDateIso, int playerId, StatType statType) {
        var resp = dynamoDbClient.getItem(b -> b
                .tableName(tableName)
                .key(Map.of(
                        "pk", AttributeValue.builder().s(DdbKeys.datePk(slateDateIso)).build(),
                        "sk", AttributeValue.builder().s(DdbKeys.edgeSk(playerId, statType)).build()
                )));
        if (!resp.hasItem() || resp.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromItem(resp.item()));
    }

    public void put(EdgeScoreRecord record) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.builder().s(DdbKeys.datePk(record.slateDate())).build());
        item.put("sk", AttributeValue.builder().s(DdbKeys.edgeSk(record.playerId(), record.statType())).build());
        item.put("gsi1pk", AttributeValue.builder().s(DdbKeys.gsiPlayerPk(record.playerId())).build());
        item.put("gsi1sk", AttributeValue.builder().s(DdbKeys.gsiEdgeSk(record.slateDate(), record.statType())).build());
        item.put("slateDate", AttributeValue.builder().s(record.slateDate()).build());
        item.put("playerId", AttributeValue.builder().n(String.valueOf(record.playerId())).build());
        item.put("statType", AttributeValue.builder().s(record.statType().name()).build());
        item.put("lineValue", AttributeValue.builder().n(String.valueOf(record.lineValue())).build());
        item.put("projectedMean", AttributeValue.builder().n(String.valueOf(record.projectedMean())).build());
        item.put("modelProbabilityOver", AttributeValue.builder().n(String.valueOf(record.modelProbabilityOver())).build());
        item.put("edgeScore", AttributeValue.builder().n(String.valueOf(record.edgeScore())).build());

        dynamoDbClient.putItem(b -> b.tableName(tableName).item(item));
    }

    public List<EdgeScoreRecord> queryByDateSorted(String slateDateIso) {
        QueryRequest req = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s(DdbKeys.datePk(slateDateIso)).build()
                ))
                .build();
        List<EdgeScoreRecord> out = new ArrayList<>();
        for (QueryResponse page : dynamoDbClient.queryPaginator(req)) {
            for (Map<String, AttributeValue> item : page.items()) {
                out.add(fromItem(item));
            }
        }
        out.sort(Comparator.comparingDouble(EdgeScoreRecord::edgeScore).reversed());
        return out;
    }

    public List<EdgeScoreRecord> queryByPlayer(int playerId, int limit) {
        QueryRequest req = QueryRequest.builder()
                .tableName(tableName)
                .indexName("PlayerEdgeIndex")
                .keyConditionExpression("gsi1pk = :gpk")
                .expressionAttributeValues(Map.of(
                        ":gpk", AttributeValue.builder().s(DdbKeys.gsiPlayerPk(playerId)).build()
                ))
                .scanIndexForward(false)
                .limit(limit)
                .build();
        List<EdgeScoreRecord> out = new ArrayList<>();
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

    private static EdgeScoreRecord fromItem(Map<String, AttributeValue> item) {
        return new EdgeScoreRecord(
                item.get("slateDate").s(),
                Integer.parseInt(item.get("playerId").n()),
                StatType.valueOf(item.get("statType").s()),
                Double.parseDouble(item.get("lineValue").n()),
                Double.parseDouble(item.get("projectedMean").n()),
                Double.parseDouble(item.get("modelProbabilityOver").n()),
                Double.parseDouble(item.get("edgeScore").n())
        );
    }
}
