package com.sportsprop.common.schedule;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

import java.util.Map;

public final class ScheduleDetailUtil {

    private ScheduleDetailUtil() {
    }

    /**
     * Reads a string field from the EventBridge scheduled event {@code detail} map (if present).
     */
    public static String readDetailField(ScheduledEvent event, String key) {
        if (event == null) {
            return null;
        }
        Map<String, Object> detail = event.getDetail();
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        Object value = detail.get(key);
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isBlank() ? null : s;
    }
}
