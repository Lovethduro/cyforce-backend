package com.cyforce.service;

import com.cyforce.model.ApprovalRequest;
import com.cyforce.model.CalendarEvent;
import com.cyforce.model.LeaveRequest;
import com.cyforce.model.User;
import com.cyforce.repository.ApprovalRequestRepository;
import com.cyforce.repository.CalendarEventRepository;
import com.cyforce.repository.LeaveRequestRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class LeaveService {

    private static final int ANNUAL_LEAVE_DAYS = 30;
    private static final int MIN_SERVICE_MONTHS = 12;

    private final LeaveRequestRepository leaveRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final UserRepository userRepository;
    private final RequestUserService requestUserService;
    private final NotificationService notificationService;

    public LeaveService(LeaveRequestRepository leaveRepository,
                        ApprovalRequestRepository approvalRepository,
                        CalendarEventRepository calendarEventRepository,
                        UserRepository userRepository,
                        RequestUserService requestUserService,
                        NotificationService notificationService) {
        this.leaveRepository = leaveRepository;
        this.approvalRepository = approvalRepository;
        this.calendarEventRepository = calendarEventRepository;
        this.userRepository = userRepository;
        this.requestUserService = requestUserService;
        this.notificationService = notificationService;
    }

    public Map<String, Object> eligibility(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR", "SALES_AGENT", "SUPPORT_AGENT");
        long months = monthsOfService(user);
        int usedDays = usedLeaveDaysThisYear(user.getId());
        int pendingDays = pendingLeaveDaysThisYear(user.getId());
        boolean eligible = months >= MIN_SERVICE_MONTHS;
        int remaining = Math.max(0, ANNUAL_LEAVE_DAYS - usedDays - pendingDays);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("monthsOfService", months);
        result.put("monthsUntilEligible", Math.max(0, MIN_SERVICE_MONTHS - months));
        result.put("eligible", eligible);
        result.put("annualAllowanceDays", ANNUAL_LEAVE_DAYS);
        result.put("usedDaysThisYear", usedDays);
        result.put("pendingDaysThisYear", pendingDays);
        result.put("remainingDays", remaining);
        if (eligible) {
            result.put("message", "You qualify for up to " + ANNUAL_LEAVE_DAYS + " paid leave days per year.");
        } else {
            long monthsLeft = MIN_SERVICE_MONTHS - months;
            result.put("message", "Paid leave is available after " + MIN_SERVICE_MONTHS
                    + " months of continuous service (" + monthsLeft + " month"
                    + (monthsLeft == 1 ? "" : "s") + " remaining).");
        }
        return result;
    }

    public List<Map<String, Object>> myRequests(String userId) {
        User user = requestUserService.requireUser(userId);
        return leaveRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::toRow)
                .toList();
    }

    public List<Map<String, Object>> pendingForReview(String userId) {
        User reviewer = requestUserService.requireUser(userId);
        requestUserService.requireRole(reviewer, "ADMIN", "SUPERVISOR");
        return leaveRepository.findByStatusOrderByCreatedAtDesc("pending").stream()
                .filter(leave -> reviewerCanActOnLeave(reviewer, leave))
                .map(this::toRow)
                .toList();
    }

    public List<Map<String, Object>> allRequests(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN");
        return leaveRepository.findAll().stream()
                .sorted(Comparator.comparing(LeaveRequest::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toRow)
                .toList();
    }

    public boolean reviewerCanActOnLeave(User reviewer, LeaveRequest leave) {
        if (leave == null) {
            return false;
        }
        User requester = userRepository.findById(leave.getUserId()).orElse(null);
        if (requester == null) {
            return false;
        }
        return reviewerCanActOn(reviewer, requester);
    }

    public boolean reviewerCanActOnLeave(String reviewerId, String leaveId) {
        User reviewer = requestUserService.requireUser(reviewerId);
        LeaveRequest leave = leaveRepository.findById(leaveId).orElse(null);
        return reviewerCanActOnLeave(reviewer, leave);
    }

    public Map<String, Object> requestLeave(String userId, Map<String, String> body) {
        User user = requestUserService.requireUser(userId);
        if ("ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new RuntimeException("Administrators do not submit leave requests");
        }
        requestUserService.requireRole(user, "SUPERVISOR", "SALES_AGENT", "SUPPORT_AGENT");

        LocalDate start = LocalDate.parse(body.get("startDate"));
        LocalDate end = LocalDate.parse(body.get("endDate"));
        if (end.isBefore(start)) {
            throw new RuntimeException("End date must be on or after start date");
        }
        if (start.isBefore(LocalDate.now())) {
            throw new RuntimeException("Leave cannot start in the past");
        }

        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        int remaining = Math.max(0, ANNUAL_LEAVE_DAYS - usedLeaveDaysThisYear(user.getId()) - pendingLeaveDaysThisYear(user.getId()));
        if (days > remaining) {
            throw new RuntimeException("Requested days exceed your remaining allowance (" + remaining + " days)");
        }

        validateNoOverlap(user.getId(), start, end, null);

        LeaveRequest leave = new LeaveRequest();
        leave.setUserId(user.getId());
        leave.setUserName(user.getFullName());
        leave.setStartDate(start);
        leave.setEndDate(end);
        leave.setReason(body.getOrDefault("reason", ""));
        leave.setDaysRequested(days);
        leave.setStatus("pending");
        leave.setCreatedAt(LocalDateTime.now());
        LeaveRequest saved = leaveRepository.save(leave);

        ApprovalRequest approval = new ApprovalRequest();
        approval.setType("leave");
        approval.setRequestedByUserId(user.getId());
        approval.setRequestedByName(user.getFullName());
        approval.setStatus("pending");
        approval.setPayload(Map.of(
                "leaveRequestId", saved.getId(),
                "startDate", start.toString(),
                "endDate", end.toString(),
                "daysRequested", days,
                "reason", saved.getReason() != null ? saved.getReason() : ""
        ));
        approval.setCreatedAt(LocalDateTime.now());
        approvalRepository.save(approval);

        notifyReviewersOnSubmit(user,
                "Leave request submitted",
                user.getFullName() + " requested " + days + " day(s) of leave (" + start + " to " + end + ")");

        return toRow(saved);
    }

    public Map<String, Object> cancelRequest(String userId, String leaveId) {
        User user = requestUserService.requireUser(userId);
        LeaveRequest leave = leaveRepository.findById(leaveId)
                .orElseThrow(() -> new RuntimeException("Leave request not found"));
        if (!user.getId().equals(leave.getUserId())) {
            throw new RuntimeException("Leave request not found");
        }
        if (!"pending".equalsIgnoreCase(leave.getStatus())) {
            throw new RuntimeException("Only pending leave requests can be cancelled");
        }

        leave.setStatus("cancelled");
        leave.setReviewedAt(LocalDateTime.now());
        leaveRepository.save(leave);

        closeLinkedApproval(leave.getId(), "cancelled", user.getId(), user.getFullName(), "Cancelled by employee");
        return toRow(leave);
    }

    public Map<String, Object> reviewLeave(String reviewerId, String leaveId, boolean approve, String note) {
        User reviewer = requestUserService.requireUser(reviewerId);
        requestUserService.requireRole(reviewer, "ADMIN", "SUPERVISOR");
        LeaveRequest leave = leaveRepository.findById(leaveId)
                .orElseThrow(() -> new RuntimeException("Leave request not found"));

        if (!"pending".equalsIgnoreCase(leave.getStatus())) {
            throw new RuntimeException("This leave request has already been " + leave.getStatus());
        }

        User requester = userRepository.findById(leave.getUserId())
                .orElseThrow(() -> new RuntimeException("Leave requester not found"));
        assertReviewerCanActOn(reviewer, requester);

        leave.setStatus(approve ? "approved" : "rejected");
        leave.setReviewedByUserId(reviewer.getId());
        leave.setReviewedByName(reviewer.getFullName());
        leave.setReviewNote(note);
        leave.setReviewedAt(LocalDateTime.now());
        leaveRepository.save(leave);

        closeLinkedApproval(leaveId, approve ? "approved" : "rejected", reviewer.getId(), reviewer.getFullName(), note);

        if (approve) {
            createLeaveCalendarEvent(leave, reviewer);
            try {
                notificationService.create(
                        leave.getUserId(),
                        "Leave approved",
                        "Your leave from " + leave.getStartDate() + " to " + leave.getEndDate()
                                + " was approved and added to the team calendar.",
                        "success"
                );
                notifyReviewers(
                        "Leave approved",
                        leave.getUserName() + " will be on leave " + leave.getStartDate() + " to " + leave.getEndDate()
                );
            } catch (Exception ignored) {
            }
        } else {
            try {
                notificationService.create(
                        leave.getUserId(),
                        "Leave request declined",
                        "Your leave request from " + leave.getStartDate() + " to " + leave.getEndDate()
                                + (note != null && !note.isBlank() ? ". Note: " + note.trim() : "")
                                + " was not approved.",
                        "warning"
                );
            } catch (Exception ignored) {
            }
        }
        return toRow(leave);
    }

    private void createLeaveCalendarEvent(LeaveRequest leave, User reviewer) {
        if (leave.getCalendarEventId() != null) {
            calendarEventRepository.findById(leave.getCalendarEventId()).ifPresent(existing -> {
                existing.setTitle("Leave: " + leave.getUserName());
                existing.setDescription(buildLeaveDescription(leave));
                existing.setStartAt(leave.getStartDate().atTime(LocalTime.of(0, 0)));
                existing.setEndAt(leave.getEndDate().atTime(LocalTime.of(23, 59)));
                calendarEventRepository.save(existing);
            });
            return;
        }

        CalendarEvent event = new CalendarEvent();
        event.setTitle("Leave: " + leave.getUserName());
        event.setDescription(buildLeaveDescription(leave));
        event.setStartAt(leave.getStartDate().atTime(LocalTime.of(0, 0)));
        event.setEndAt(leave.getEndDate().atTime(LocalTime.of(23, 59)));
        event.setEventType("leave");
        event.setCreatedByUserId(leave.getUserId());
        event.setCreatedByName(leave.getUserName());
        event.setTaggedUserIds(List.of(leave.getUserId()));
        event.setTargetRoles(List.of("ADMIN", "SUPERVISOR"));

        List<String> notified = new ArrayList<>();
        notified.add(leave.getUserId());
        userRepository.findAll().stream()
                .filter(User::isActive)
                .filter(u -> u.getRole() != null)
                .filter(u -> {
                    String role = u.getRole().toUpperCase();
                    return "ADMIN".equals(role) || "SUPERVISOR".equals(role);
                })
                .map(User::getId)
                .forEach(notified::add);
        event.setNotifiedUserIds(notified.stream().distinct().toList());
        event.setReminderSent(false);
        event.setCreatedAt(LocalDateTime.now());
        CalendarEvent saved = calendarEventRepository.save(event);

        leave.setCalendarEventId(saved.getId());
        leaveRepository.save(leave);
    }

    private String buildLeaveDescription(LeaveRequest leave) {
        String reason = leave.getReason() != null && !leave.getReason().isBlank()
                ? leave.getReason().trim()
                : "Approved leave";
        return reason + " (" + leave.getDaysRequested() + " day(s))";
    }

    private void closeLinkedApproval(String leaveId, String status, String reviewerId, String reviewerName, String note) {
        approvalRepository.findAll().stream()
                .filter(a -> "leave".equals(a.getType()))
                .filter(a -> leaveId.equals(String.valueOf(a.getPayload().get("leaveRequestId"))))
                .filter(a -> "pending".equals(a.getStatus()))
                .findFirst()
                .ifPresent(a -> {
                    a.setStatus(status);
                    a.setReviewedByUserId(reviewerId);
                    a.setReviewedByName(reviewerName);
                    a.setReviewNote(note);
                    a.setReviewedAt(LocalDateTime.now());
                    approvalRepository.save(a);
                });
    }

    private void notifyReviewersOnSubmit(User requester, String title, String message) {
        try {
            if ("SUPERVISOR".equalsIgnoreCase(requester.getRole())) {
                notificationService.broadcastToAudience(title, message, "admins");
            } else {
                notificationService.broadcastToAudience(title, message, "supervisors");
            }
        } catch (Exception ignored) {
        }
    }

    private void notifyReviewers(String title, String message) {
        try {
            notificationService.broadcastToAudience(title, message, "supervisors");
            notificationService.broadcastToAudience(title, message, "admins");
        } catch (Exception ignored) {
        }
    }

    private void assertReviewerCanActOn(User reviewer, User requester) {
        String reviewerRole = normalizeRole(reviewer.getRole());
        String requesterRole = normalizeRole(requester.getRole());
        if ("ADMIN".equals(reviewerRole)) {
            if (!"SUPERVISOR".equals(requesterRole)) {
                throw new RuntimeException("Admins can only approve or reject leave for supervisors");
            }
            return;
        }
        if ("SUPERVISOR".equals(reviewerRole)) {
            if (!List.of("SALES_AGENT", "SUPPORT_AGENT").contains(requesterRole)) {
                throw new RuntimeException("Supervisors can only approve or reject leave for sales and support staff");
            }
            return;
        }
        throw new RuntimeException("You do not have permission to review leave requests");
    }

    private boolean reviewerCanActOn(User reviewer, User requester) {
        try {
            assertReviewerCanActOn(reviewer, requester);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.trim().toUpperCase();
    }

    private void validateNoOverlap(String userId, LocalDate start, LocalDate end, String excludeLeaveId) {
        boolean overlaps = leaveRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(l -> excludeLeaveId == null || !excludeLeaveId.equals(l.getId()))
                .filter(l -> List.of("pending", "approved").contains(l.getStatus()))
                .anyMatch(l -> datesOverlap(start, end, l.getStartDate(), l.getEndDate()));
        if (overlaps) {
            throw new RuntimeException("These dates overlap with an existing pending or approved leave request");
        }
    }

    private boolean datesOverlap(LocalDate start1, LocalDate end1, LocalDate start2, LocalDate end2) {
        if (start1 == null || end1 == null || start2 == null || end2 == null) {
            return false;
        }
        return !start1.isAfter(end2) && !start2.isAfter(end1);
    }

    private int usedLeaveDaysThisYear(String userId) {
        int year = LocalDate.now().getYear();
        return leaveRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(l -> "approved".equals(l.getStatus()))
                .filter(l -> l.getStartDate() != null && l.getStartDate().getYear() == year)
                .mapToInt(LeaveRequest::getDaysRequested)
                .sum();
    }

    private int pendingLeaveDaysThisYear(String userId) {
        int year = LocalDate.now().getYear();
        return leaveRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(l -> "pending".equals(l.getStatus()))
                .filter(l -> l.getStartDate() != null && l.getStartDate().getYear() == year)
                .mapToInt(LeaveRequest::getDaysRequested)
                .sum();
    }

    private long monthsOfService(User user) {
        if (user.getCreatedAt() == null) {
            return 0;
        }
        return ChronoUnit.MONTHS.between(user.getCreatedAt().toLocalDate(), LocalDate.now());
    }

    private Map<String, Object> toRow(LeaveRequest leave) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", leave.getId());
        row.put("userId", leave.getUserId());
        row.put("userName", leave.getUserName());
        userRepository.findById(leave.getUserId()).ifPresent(u -> row.put("userRole", u.getRole()));
        row.put("startDate", leave.getStartDate() != null ? leave.getStartDate().toString() : null);
        row.put("endDate", leave.getEndDate() != null ? leave.getEndDate().toString() : null);
        row.put("reason", leave.getReason());
        row.put("daysRequested", leave.getDaysRequested());
        row.put("status", leave.getStatus());
        row.put("reviewNote", leave.getReviewNote());
        row.put("reviewedByName", leave.getReviewedByName());
        row.put("calendarEventId", leave.getCalendarEventId());
        row.put("createdAt", leave.getCreatedAt());
        row.put("reviewedAt", leave.getReviewedAt());
        return row;
    }
}
