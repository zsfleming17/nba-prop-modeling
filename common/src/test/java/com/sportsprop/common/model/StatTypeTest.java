package com.sportsprop.common.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatTypeTest {

    @Test
    void fromString_acceptsAliases() {
        assertEquals(Optional.of(StatType.POINTS), StatType.fromString("PTS"));
        assertEquals(Optional.of(StatType.REBOUNDS), StatType.fromString("REB"));
        assertEquals(Optional.of(StatType.ASSISTS), StatType.fromString("AST"));
    }
}
