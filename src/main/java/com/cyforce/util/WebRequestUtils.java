package com.cyforce.util;

import jakarta.servlet.http.HttpServletRequest;

public final class WebRequestUtils {

    private WebRequestUtils() {
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return normalizeIp(forwarded.split(",")[0].trim());
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return normalizeIp(realIp.trim());
        }
        return normalizeIp(request.getRemoteAddr());
    }

    private static String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }
}
