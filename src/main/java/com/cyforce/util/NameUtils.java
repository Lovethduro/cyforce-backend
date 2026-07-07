package com.cyforce.util;

public final class NameUtils {

    private NameUtils() {
    }

    public static String capitalizeWords(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String[] parts = value.trim().split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(capitalizeWord(parts[i]));
        }
        return builder.toString();
    }

    private static String capitalizeWord(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        if (word.length() == 1) {
            return word.toUpperCase();
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase();
    }
}
