package com.sportsprop.common.ddb;

import com.sportsprop.common.model.StatType;

public final class DdbKeys {

    private DdbKeys() {
    }

    public static String playerPk(int playerId) {
        return "PLAYER#" + playerId;
    }

    public static String gameSk(String gameId, String gameDateIso) {
        return "GAME#" + gameId + "#" + gameDateIso;
    }

    public static String datePk(String slateDateIso) {
        return "DATE#" + slateDateIso;
    }

    public static String propLineSk(int playerId, StatType statType) {
        return "PLAYER#" + playerId + "#" + statType.name();
    }

    public static String edgeSk(int playerId, StatType statType) {
        return "EDGE#" + playerId + "#" + statType.name();
    }

    public static String gamePk(String gameId) {
        return "GAME#" + gameId;
    }

    public static String outcomeSk(int playerId, StatType statType) {
        return "PLAYER#" + playerId + "#" + statType.name();
    }

    public static String gsiPlayerPk(int playerId) {
        return "PLAYER#" + playerId;
    }

    public static String gsiEdgeSk(String slateDateIso, StatType statType) {
        return slateDateIso + "#" + statType.name();
    }

    public static String gsiOutcomeSk(String gameDateIso, String gameId, StatType statType) {
        return gameDateIso + "#" + gameId + "#" + statType.name();
    }
}
