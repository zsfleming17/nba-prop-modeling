package com.sportsprop.common.repo;

import com.sportsprop.common.ddb.DdbKeys;
import com.sportsprop.common.model.OutcomeRecord;
import com.sportsprop.common.model.StatType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OutcomesRepository {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public OutcomesRepository(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    public void put(OutcomeRecord record) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.builder().s(DdbKeys.gamePk(record.gameId())).build());
        item.put("sk", AttributeValue.builder().s(DdbKeys.outcomeSk(record.playerId(), record.statType())).build());
        item.put("gsi1pk", AttributeValue.builder().s(DdbKeys.gsiPlayerPk(record.playerId())).build());
        item.put("gsi1sk", AttributeValue.builder().s(DdbKeys.gsiOutcomeSk(record.gameDate(), record.gameId(), record.statType())).build());
        item.put("gameId", AttributeValue.builder().s(record.gameId()).build());
        item.put("gameDate", AttributeValue.builder().s(record.gameDate()).build());
        item.put("playerId", AttributeValue.builder().n(String.valueOf(record.playerId())).build());
        item.put("statType", AttributeValue.builder().s(record.statType().name()).build());
        item.put("lineValue", AttributeValue.builder().n(String.valueOf(record.lineValue())).build());
        item.put("actualValue", AttributeValue.builder().n(String.valueOf(record.actualValue())).build());
        item.put("overHit", AttributeValue.builder().bool(record.overHit()).build());
        if (record.predictedProbabilityOver() != null) {
            item.put("predictedProbabilityOver", AttributeValue.builder().n(String.valueOf(record.predictedProbabilityOver())).build());
        }

        dynamoDbClient.putItem(b -> b.tableName(tableName).item(item));
    }

    public List<OutcomeRecord> queryByPlayer(int playerId, int limit) {
        QueryRequest req = QueryRequest.builder()
                .tableName(tableName)
                .indexName("PlayerOutcomeIndex")
                .keyConditionExpression("gsi1pk = :gpk")
                .expressionAttributeValues(Map.of(
                        ":gpk", AttributeValue.builder().s(DdbKeys.gsiPlayerPk(playerId)).build()
                ))
                .scanIndexForward(false)
                .limit(limit)
                .build();
        List<OutcomeRecord> out = new ArrayList<>();
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

    private static OutcomeRecord fromItem(Map<String, AttributeValue> item) {
        Double pred = item.containsKey("predictedProbabilityOver") && item.get("predictedProbabilityOver").n() != null
                ? Double.parseDouble(item.get("predictedProbabilityOver").n())
                : null;
        return new OutcomeRecord(
                item.get("gameId").s(),
                item.get("gameDate").s(),
                Integer.parseInt(item.get("playerId").n()),
                StatType.valueOf(item.get("statType").s()),
                Double.parseDouble(item.get("lineValue").n()),
                Integer.parseInt(item.get("actualValue").n()),
                item.get("overHit").bool(),
                pred
        );
    }
}
