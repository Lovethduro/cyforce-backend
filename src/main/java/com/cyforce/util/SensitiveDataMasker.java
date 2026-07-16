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
    private static final Pattern PORTAL_URL = Pattern.compile(
            "(?i)\\bhttps?://[^\\s]*/(?:quote|support)/portal/[A-Za-z0-9_-]+");
    /** Guest portal tokens are 32-char hex (UUID without dashes). */
    private static final Pattern GUEST_TOKEN = Pattern.compile("\\b[a-fA-F0-9]{32}\\b");
    /**
     * Labeled payment / bank fields, e.g. "account number: 0123456789", "BVN 22123456789",
     * "Acc No 0123456789", "card: 4111 1111 1111 1111".
     */
    private static final Pattern LABELED_PAYMENT = Pattern.compile(
            "(?i)\\b(?:account\\s*(?:number|no\\.?|#)?|acc(?:ount)?\\s*(?:no\\.?|#)?|a/?c\\s*(?:no\\.?|#)?"
                    + "|acct\\s*(?:no\\.?|#)?|nuban|bvn|iban|sort\\s*code|routing\\s*(?:number|no\\.?)?"
                    + "|card\\s*(?:number|no\\.?|#)?|(?:debit|credit)\\s*card"
                    + "|bank\\s*account(?:\\s*(?:number|no\\.?|#))?)"
                    + "\\s*(?:is|:|-|#)?\\s*(?:[A-Z]{2}\\d{2}[A-Z0-9]{10,}|\\d(?:[ \\-]*\\d){5,})");
    /** International bank account numbers. */
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b");
    /** Card-like digit groups (13–19 digits with optional spaces/dashes). */
    private static final Pattern CARD_NUMBER = Pattern.compile(
            "(?<!\\d)\\d(?:[ -]*\\d){12,18}(?!\\d)");
    /**
     * Bare long digit runs: Nigerian NUBAN (10), BVN (11), and similar account-style numbers.
     * Applied after labeled/card patterns so remaining credentials are still hidden.
     */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile(
            "(?<!\\d)\\d(?:[\\s.-]*\\d){7,18}(?!\\d)");
    /** Nigerian and common international phone shapes (shorter / prefixed forms). */
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\w)(?:\\+?234|0)[\\s.-]?\\d{3}[\\s.-]?\\d{3}[\\s.-]?\\d{4}\\b");

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
     * Redacts emails, phones, bank/payment details, portal URLs, and guest tokens in free text.
     */
    public static String redactText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = PORTAL_URL.matcher(text).replaceAll("[portal link redacted]");
        result = LABELED_PAYMENT.matcher(result).replaceAll("[payment details redacted]");
        result = IBAN.matcher(result).replaceAll("[iban redacted]");
        result = CARD_NUMBER.matcher(result).replaceAll("[card redacted]");
        result = replaceEmails(result);
        result = PHONE.matcher(result).replaceAll("[phone redacted]");
        result = LONG_DIGIT_RUN.matcher(result).replaceAll("[number redacted]");
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
