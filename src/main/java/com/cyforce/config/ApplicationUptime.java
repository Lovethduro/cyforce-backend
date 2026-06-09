package com.cyforce.config;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ApplicationUptime {

    private final Instant startedAt = Instant.now();

    public Instant getStartedAt() {
        return startedAt;
    }

    public Duration getUptime() {
        return Duration.between(startedAt, Instant.now());
    }

    public String formatUptime() {
        Duration d = getUptime();
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        long seconds = d.toSecondsPart();
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }
}
