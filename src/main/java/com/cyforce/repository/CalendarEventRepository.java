package com.cyforce.repository;

import com.cyforce.model.CalendarEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface CalendarEventRepository extends MongoRepository<CalendarEvent, String> {
    List<CalendarEvent> findByTaggedUserIdsContainingOrderByStartAtAsc(String userId);
    List<CalendarEvent> findByNotifiedUserIdsContainingOrderByStartAtAsc(String userId);
    List<CalendarEvent> findByCreatedByUserIdOrderByStartAtAsc(String userId);
    List<CalendarEvent> findByStartAtBetweenOrderByStartAtAsc(LocalDateTime start, LocalDateTime end);
    List<CalendarEvent> findByReminderSentFalseAndStartAtBetween(LocalDateTime start, LocalDateTime end);
}
