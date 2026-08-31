package com.ai.daily.security;

public final class IngestTokens {

    private IngestTokens() {
    }

    public static boolean invalid(String expected, String provided) {
        return expected == null || expected.isBlank() || !expected.equals(provided);
    }
}
