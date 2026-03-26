package com.sportsprop.common.repo;

import com.sportsprop.common.ddb.DdbKeys;
import com.sportsprop.common.model.PropLineRecord;
import com.sportsprop.common.model.StatType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PropLinesRepository {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public PropLinesRepository(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    public void put(PropLineRecord record) {
        Map<String, AttributeValue> item = Map.of(
                "pk", AttributeValue.builder().s(DdbKeys.datePk(record.slateDate())).build(),
                "sk", AttributeValue.builder().s(DdbKeys.propLineSk(record.playerId(), record.statType())).build(),
                "slateDate", AttributeValue.builder().s(record.slateDate()).build(),
                "playerId", AttributeValue.builder().n(String.valueOf(record.playerId())).build(),
                "playerName", AttributeValue.builder().s(record.playerName() == null ? "" : record.playerName()).build(),
                "statType", AttributeValue.builder().s(record.statType().name()).build(),
                "lineValue", AttributeValue.builder().n(String.valueOf(record.lineValue())).build()
        );
        dynamoDbClient.putItem(b -> b.tableName(tableName).item(item));
    }

    public List<PropLineRecord> queryByDate(String slateDateIso) {
        QueryRequest req = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s(DdbKeys.datePk(slateDateIso)).build()
                ))
                .build();
        List<PropLineRecord> out = new ArrayList<>();
        for (QueryResponse page : dynamoDbClient.queryPaginator(req)) {
            for (Map<String, AttributeValue> item : page.items()) {
                out.add(fromItem(item));
            }
        }
        return out;
    }

    private static PropLineRecord fromItem(Map<String, AttributeValue> item) {
        return new PropLineRecord(
                item.get("slateDate").s(),
                Integer.parseInt(item.get("playerId").n()),
                item.get("playerName").s(),
                StatType.valueOf(item.get("statType").s()),
                Double.parseDouble(item.get("lineValue").n())
        );
    }
}
