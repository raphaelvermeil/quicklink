package com.quicklink.app.link;

final class ClickCounters {

    static final String DIRTY_SET = "stats:dirty";
    private static final String COUNTER_PREFIX = "stats:clicks:";

    static String counterKey(String shortCode) {
        return COUNTER_PREFIX + shortCode;
    }

    private ClickCounters() {}
}
