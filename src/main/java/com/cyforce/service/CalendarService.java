package com.cyforce.service;

import com.cyforce.model.CalendarEvent;
import com.cyforce.model.User;
import com.cyforce.repository.CalendarEventRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CalendarService {

    private static final Set<String> STAFF_ROLES = Set.of(
            "ADMIN", "SUPERVISOR", "SALES_AGENT", "SUPPORT_AGENT"
    );

    private final CalendarEventRepository eventRepository;
    private final UserRepository userRepository;
    private final RequestUserService requestUserService;
    private final NotificationService notificationService;

    public CalendarService(CalendarEventRepository eventRepository,
                           UserRepository userRepository,
                           RequestUserService requestUserService,
                           NotificationService notificationService) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.requestUserService = requestUserService;
        this.notificationService = notificationService;
    }

    public List<Map<String, Object>> listEvents(String userId, String month) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR", "SALES_AGENT", "SUPPORT_AGENT");

        LocalDate startDate = month != null && month.matches("\\d{4}-\\d{2}")
                ? LocalDate.parse(month + "-01")
                : LocalDate.now().withDayOfMonth(1);
        LocalDateTime rangeStart = startDate.atStartOfDay();
        LocalDateTime rangeEnd = startDate.plusMonths(1).atStartOfDay();

        Set<String> seen = new HashSet<>();
        List<CalendarEvent> events = new ArrayList<>();
        collectEvents(events, seen, eventRepository.findByStartAtBetweenOrderByStartAtAsc(rangeStart, rangeEnd), user, rangeStart, rangeEnd);
        collectEvents(events, seen, eventRepository.findByCreatedByUserIdOrderByStartAtAsc(user.getId()), user, rangeStart, rangeEnd);
        collectEvents(events, seen, eventRepository.findByTaggedUserIdsContainingOrderByStartAtAsc(user.getId()), user, rangeStart, rangeEnd);
        collectEvents(events, seen, eventRepository.findByNotifiedUserIdsContainingOrderByStartAtAsc(user.getId()), user, rangeStart, rangeEnd);

        return events.stream()
                .sorted(Comparator.comparing(CalendarEvent::getStartAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toRow)
                .collect(Collectors.toList());
    }

    private void collectEvents(List<CalendarEvent> target,
                               Set<String> seen,
                               List<CalendarEvent> source,
                               User user,
                               LocalDateTime rangeStart,
                               LocalDateTime rangeEnd) {
        for (CalendarEvent event : source) {
            if (!inMonth(event, rangeStart, rangeEnd)) {
                continue;
            }
            if (!isVisibleToUser(event, user)) {
                continue;
            }
            if (seen.add(event.getId())) {
                target.add(event);
            }
        }
    }

    public Map<String, Object> createEvent(String userId, Map<String, Object> body) {
        User user = requestUserService.requireUser(userId);
        String eventType = stringVal(body.get("eventType"), "personal");
        if ("company".equalsIgnoreCase(eventType)) {
            requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");
        } else {
            requestUserService.requireRole(user, "ADMIN", "SUPERVISOR", "SALES_AGENT", "SUPPORT_AGENT");
        }

        LocalDateTime startAt = parseDateTime(body.get("startAt"));
        if (startAt == null) {
            throw new RuntimeException("Start date and time are required");
        }

        CalendarEvent event = new CalendarEvent();
        event.setTitle(stringVal(body.get("title"), "Event"));
        event.setDescription(stringVal(body.get("description"), ""));
        event.setStartAt(startAt);
        event.setEndAt(parseDateTime(body.get("endAt")));
        event.setEventType(eventType.toLowerCase());
        event.setCreatedByUserId(user.getId());
        event.setCreatedByName(user.getFullName());
        event.setTaggedUserIds(parseStringList(body.get("taggedUserIds")));
        event.setTargetRoles(parseStringList(body.get("targetRoles")));

        if ("personal".equalsIgnoreCase(eventType) && !event.getTaggedUserIds().contains(user.getId())) {
            event.getTaggedUserIds().add(user.getId());
        }

        Set<String> notifiedIds = resolveNotificationRecipients(user, event);
        event.setNotifiedUserIds(new ArrayList<>(notifiedIds));
        if (!notifiedIds.contains(user.getId())) {
            event.getNotifiedUserIds().add(user.getId());
        }

        event.setReminderSent(false);
        event.setCreatedAt(LocalDateTime.now());
        CalendarEvent saved = eventRepository.save(event);

        notifyUsers(user, saved, notifiedIds);
        return toRow(saved);
    }

    public Map<String, Object> updateEvent(String userId, String eventId, Map<String, Object> body) {
        User user = requestUserService.requireUser(userId);
        CalendarEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Event not found"));

        if ("leave".equalsIgnoreCase(event.getEventType())) {
            throw new RuntimeException("Leave events are managed on the Leave page");
        }
        requireEditPermission(user, event);

        String eventType = stringVal(body.get("eventType"), event.getEventType());
        if ("company".equalsIgnoreCase(eventType)) {
            requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");
        }

        LocalDateTime startAt = parseDateTime(body.get("startAt"));
        if (startAt == null) {
            throw new RuntimeException("Start date and time are required");
        }

        List<String> previousNotified = event.getNotifiedUserIds() != null
                ? new ArrayList<>(event.getNotifiedUserIds())
                : new ArrayList<>();

        event.setTitle(stringVal(body.get("title"), event.getTitle()));
        event.setDescription(stringVal(body.get("description"), ""));
        event.setStartAt(startAt);
        event.setEndAt(parseDateTime(body.get("endAt")));
        event.setEventType(eventType.toLowerCase());
        event.setTaggedUserIds(parseStringList(body.get("taggedUserIds")));
        event.setTargetRoles(parseStringList(body.get("targetRoles")));

        if ("personal".equalsIgnoreCase(eventType)) {
            event.setTargetRoles(new ArrayList<>());
            if (!event.getTaggedUserIds().contains(event.getCreatedByUserId())) {
                event.getTaggedUserIds().add(event.getCreatedByUserId());
            }
        }

        User creator = userRepository.findById(event.getCreatedByUserId()).orElse(user);
        Set<String> notifiedIds = resolveNotificationRecipients(creator, event);
        event.setNotifiedUserIds(new ArrayList<>(notifiedIds));
        if (!notifiedIds.contains(event.getCreatedByUserId())) {
            event.getNotifiedUserIds().add(event.getCreatedByUserId());
        }

        CalendarEvent saved = eventRepository.save(event);

        Set<String> updateRecipients = new LinkedHashSet<>();
        for (String recipientId : saved.getNotifiedUserIds()) {
            if (!recipientId.equals(user.getId()) && previousNotified.contains(recipientId)) {
                updateRecipients.add(recipientId);
            }
        }
        notifyEventUpdated(user, saved, updateRecipients);

        Set<String> newlyNotified = new LinkedHashSet<>(notifiedIds);
        newlyNotified.removeAll(previousNotified);
        newlyNotified.remove(user.getId());
        notifyUsers(user, saved, newlyNotified);

        return toRow(saved);
    }

    public void deleteEvent(String userId, String eventId) {
        User user = requestUserService.requireUser(userId);
        CalendarEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Event not found"));
        if ("leave".equalsIgnoreCase(event.getEventType())) {
            throw new RuntimeException("Leave events are managed on the Leave page");
        }
        requireEditPermission(user, event);
        eventRepository.delete(event);
    }

    public List<Map<String, Object>> listStaffForTagging(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");
        return userRepository.findAll().stream()
                .filter(u -> u.isActive() && STAFF_ROLES.contains(normalizeRole(u.getRole())))
                .map(u -> Map.<String, Object>of("id", u.getId(), "name", u.getFullName(), "role", u.getRole()))
                .collect(Collectors.toList());
    }

    private void notifyUsers(User creator, CalendarEvent event, Set<String> recipientIds) {
        if (recipientIds == null || recipientIds.isEmpty()) {
            return;
        }
        String when = formatEventWhen(event);
        String body = creator.getFullName() + " scheduled \"" + event.getTitle() + "\" for " + when
                + (event.getDescription() != null && !event.getDescription().isBlank()
                ? ". " + event.getDescription().trim()
                : "");

        for (String recipientId : recipientIds) {
            try {
                notificationService.create(
                        recipientId,
                        "New calendar event: " + event.getTitle(),
                        body,
                        "info"
                );
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyEventUpdated(User editor, CalendarEvent event, Set<String> recipientIds) {
        if (recipientIds == null || recipientIds.isEmpty()) {
            return;
        }
        String when = formatEventWhen(event);
        String body = editor.getFullName() + " updated \"" + event.getTitle() + "\" — now scheduled for " + when
                + (event.getDescription() != null && !event.getDescription().isBlank()
                ? ". " + event.getDescription().trim()
                : "");

        for (String recipientId : recipientIds) {
            try {
                notificationService.create(
                        recipientId,
                        "Calendar event updated: " + event.getTitle(),
                        body,
                        "info"
                );
            } catch (Exception ignored) {
            }
        }
    }

    private void requireEditPermission(User user, CalendarEvent event) {
        if (!user.getId().equals(event.getCreatedByUserId())) {
            throw new RuntimeException("Only the event creator can edit or delete this event");
        }
    }

    private Set<String> resolveNotificationRecipients(User creator, CalendarEvent event) {
        Set<String> ids = new LinkedHashSet<>();
        String type = event.getEventType() != null ? event.getEventType().toLowerCase() : "personal";

        if ("personal".equals(type)) {
            return ids;
        }

        if ("company".equals(type)) {
            List<String> tagged = event.getTaggedUserIds() != null ? event.getTaggedUserIds() : List.of();
            for (String taggedId : tagged) {
                if (!taggedId.equals(creator.getId())) {
                    ids.add(taggedId);
                }
            }
            if (!ids.isEmpty()) {
                return ids;
            }

            List<String> roles = event.getTargetRoles() != null ? event.getTargetRoles() : List.of();
            userRepository.findAll().stream()
                    .filter(User::isActive)
                    .filter(u -> !creator.getId().equals(u.getId()))
                    .filter(u -> roles.isEmpty() ? isStaff(u) : matchesTargetRole(u, roles))
                    .forEach(u -> ids.add(u.getId()));
            return ids;
        }

        return ids;
    }

    private boolean isVisibleToUser(CalendarEvent event, User user) {
        if (user.getId().equals(event.getCreatedByUserId())) {
            return true;
        }
        if (event.getTaggedUserIds() != null && event.getTaggedUserIds().contains(user.getId())) {
            return true;
        }
        if (event.getNotifiedUserIds() != null && event.getNotifiedUserIds().contains(user.getId())) {
            return true;
        }

        String type = event.getEventType() != null ? event.getEventType().toLowerCase() : "";
        return switch (type) {
            case "leave" -> isSupervisorOrAdmin(user);
            case "company" -> {
                List<String> roles = event.getTargetRoles();
                if (roles != null && !roles.isEmpty()) {
                    yield matchesTargetRole(user, roles);
                }
                yield true;
            }
            case "personal" -> false;
            default -> false;
        };
    }

    private boolean inMonth(CalendarEvent event, LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        if (event.getStartAt() == null) {
            return false;
        }
        return !event.getStartAt().isBefore(rangeStart) && event.getStartAt().isBefore(rangeEnd);
    }

    private boolean isStaff(User user) {
        return STAFF_ROLES.contains(normalizeRole(user.getRole()));
    }

    private boolean isSupervisorOrAdmin(User user) {
        String role = normalizeRole(user.getRole());
        return "ADMIN".equals(role) || "SUPERVISOR".equals(role);
    }

    private boolean matchesTargetRole(User user, List<String> targetRoles) {
        if (targetRoles == null || targetRoles.isEmpty()) {
            return false;
        }
        String role = normalizeRole(user.getRole());
        return targetRoles.stream().map(this::normalizeRole).anyMatch(role::equals);
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.trim().toUpperCase();
    }

    private List<String> parseStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        return new ArrayList<>();
    }

    private LocalDateTime parseDateTime(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        if (value.isBlank()) {
            return null;
        }
        if (value.length() == 10) {
            return LocalDate.parse(value).atTime(LocalTime.of(9, 0));
        }
        if (value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")) {
            value = value + ":00";
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new RuntimeException("Invalid date/time: " + value);
        }
    }

    private String formatEventWhen(CalendarEvent event) {
        if (event.getStartAt() == null) {
            return "an upcoming date";
        }
        String start = event.getStartAt().toLocalDate() + " at " + event.getStartAt().toLocalTime().withSecond(0).withNano(0);
        if (event.getEndAt() != null) {
            return start + " until " + event.getEndAt().toLocalDate() + " at "
                    + event.getEndAt().toLocalTime().withSecond(0).withNano(0);
        }
        return start;
    }

    private String stringVal(Object raw, String fallback) {
        return raw == null || raw.toString().isBlank() ? fallback : raw.toString().trim();
    }

    private Map<String, Object> toRow(CalendarEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", event.getId());
        row.put("title", event.getTitle());
        row.put("description", event.getDescription());
        row.put("startAt", event.getStartAt() != null ? event.getStartAt().toString() : null);
        row.put("endAt", event.getEndAt() != null ? event.getEndAt().toString() : null);
        row.put("eventType", event.getEventType());
        row.put("createdByUserId", event.getCreatedByUserId());
        row.put("createdByName", event.getCreatedByName());
        row.put("taggedUserIds", event.getTaggedUserIds());
        row.put("targetRoles", event.getTargetRoles());
        row.put("notifiedUserIds", event.getNotifiedUserIds());
        row.put("editable", isEditableEvent(event));
        return row;
    }

    private boolean isEditableEvent(CalendarEvent event) {
        return event.getEventType() != null && !"leave".equalsIgnoreCase(event.getEventType());
    }
}
