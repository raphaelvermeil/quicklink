package com.quicklink.app.link;

public final class ClickCounters {

    public static final String DIRTY_SET = "stats:dirty";
    public static final String COUNTER_PREFIX = "stats:clicks:";

    public static String counterKey(String shortCode) {
        return COUNTER_PREFIX + shortCode;
    }

    private ClickCounters() {}
}
