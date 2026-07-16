package com.cyforce.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks high-sensitivity values in API responses for oversight roles (e.g. ADMIN).
 * Does not alter stored data — only outbound views.
 */
public final class SensitiveDataMasker {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    /** Nigerian and common international phone shapes. */
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\w)(?:\\+?234|0)?[\\s.-]?\\d{3}[\\s.-]?\\d{3}[\\s.-]?\\d{4}\\b");
    private static final Pattern PORTAL_URL = Pattern.compile(
            "(?i)\\bhttps?://[^\\s]*/(?:quote|support)/portal/[A-Za-z0-9_-]+");
    /** Guest portal tokens are 32-char hex (UUID without dashes). */
    private static final Pattern GUEST_TOKEN = Pattern.compile("\\b[a-fA-F0-9]{32}\\b");

    private SensitiveDataMasker() {
    }

    public static boolean shouldMaskForRole(String role) {
        return role != null && "ADMIN".equalsIgnoreCase(role.trim());
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            return "***";
        }
        String local = trimmed.substring(0, at);
        String domain = trimmed.substring(at + 1);
        String visible = local.length() == 1 ? "*" : local.charAt(0) + "***";
        return visible + "@" + domain;
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return "****";
        }
        return "****" + digits.substring(digits.length() - 4);
    }

    /**
     * Redacts emails, phones, guest portal URLs, and bare guest tokens in free text.
     */
    public static String redactText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = PORTAL_URL.matcher(text).replaceAll("[portal link redacted]");
        result = replaceEmails(result);
        result = PHONE.matcher(result).replaceAll(match -> maskPhone(match.group()));
        result = GUEST_TOKEN.matcher(result).replaceAll("[token redacted]");
        return result;
    }

    private static String replaceEmails(String text) {
        Matcher matcher = EMAIL.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(maskEmail(matcher.group())));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
