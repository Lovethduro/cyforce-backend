package com.cyforce.controller;

import com.cyforce.service.CalendarService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/calendar")
@CrossOrigin(origins = "http://localhost:3000")
public class CalendarController {

    private final CalendarService calendarService;

    public CalendarController(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/events")
    public ResponseEntity<?> events(@RequestHeader("X-User-Id") String userId,
                                    @RequestParam(required = false) String month) {
        try {
            return ResponseEntity.ok(calendarService.listEvents(userId, month));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/events")
    public ResponseEntity<?> create(@RequestHeader("X-User-Id") String userId, @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(calendarService.createEvent(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/events/{id}")
    public ResponseEntity<?> update(@RequestHeader("X-User-Id") String userId,
                                    @PathVariable String id,
                                    @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(calendarService.updateEvent(userId, id, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/events/{id}")
    public ResponseEntity<?> delete(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            calendarService.deleteEvent(userId, id);
            return ResponseEntity.ok(Map.of("message", "Deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/staff")
    public ResponseEntity<?> staff(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(calendarService.listStaffForTagging(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
