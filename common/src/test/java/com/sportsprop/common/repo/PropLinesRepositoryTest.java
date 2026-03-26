package com.sportsprop.common.repo;

import com.sportsprop.common.model.PropLineRecord;
import com.sportsprop.common.model.StatType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PropLinesRepositoryTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Test
    @SuppressWarnings("unchecked")
    void put_sendsExpectedKeys() {
        PropLinesRepository repo = new PropLinesRepository(dynamoDbClient, "PropLines");
        PropLineRecord line = new PropLineRecord("2025-03-20", 100, "Test", StatType.POINTS, 22.5);
        repo.put(line);

        ArgumentCaptor<Consumer<PutItemRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(dynamoDbClient).putItem(captor.capture());
        PutItemRequest.Builder bldr = PutItemRequest.builder();
        captor.getValue().accept(bldr);
        PutItemRequest req = bldr.build();

        assertEquals("PropLines", req.tableName());
        Map<String, AttributeValue> item = req.item();
        assertEquals("DATE#2025-03-20", item.get("pk").s());
        assertTrue(item.get("sk").s().contains("PLAYER#100"));
        assertEquals("POINTS", item.get("statType").s());
    }
}
