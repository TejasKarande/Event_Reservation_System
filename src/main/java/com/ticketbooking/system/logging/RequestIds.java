package com.ticketbooking.system.logging;

public final class RequestIds {
    private static final ThreadLocal<String> ID = new ThreadLocal<>();

    private RequestIds() {
    }

    public static String get() {
        return ID.get();
    }

    public static void set(String v) {
        ID.set(v);
    }

    public static void clear() {
        ID.remove();
    }
}
