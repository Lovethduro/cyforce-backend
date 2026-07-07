package com.cyforce.service;

import com.cyforce.model.Conversation;
import com.cyforce.model.CustomerFeedback;
import com.cyforce.model.Invoice;
import com.cyforce.model.Ticket;
import com.cyforce.model.TicketFeedback;
import com.cyforce.model.User;
import com.cyforce.repository.ConversationRepository;
import com.cyforce.repository.CustomerFeedbackRepository;
import com.cyforce.repository.InvoiceRepository;
import com.cyforce.repository.TicketFeedbackRepository;
import com.cyforce.repository.TicketRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RatingService {

    private final CustomerFeedbackRepository feedbackRepository;
    private final TicketFeedbackRepository ticketFeedbackRepository;
    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final InvoiceRepository invoiceRepository;
    private final TicketRepository ticketRepository;
    private final RequestUserService requestUserService;
    private final NotificationService notificationService;

    public RatingService(CustomerFeedbackRepository feedbackRepository,
                         TicketFeedbackRepository ticketFeedbackRepository,
                         UserRepository userRepository,
                         ConversationRepository conversationRepository,
                         InvoiceRepository invoiceRepository,
                         TicketRepository ticketRepository,
                         RequestUserService requestUserService,
                         NotificationService notificationService) {
        this.feedbackRepository = feedbackRepository;
        this.ticketFeedbackRepository = ticketFeedbackRepository;
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.invoiceRepository = invoiceRepository;
        this.ticketRepository = ticketRepository;
        this.requestUserService = requestUserService;
        this.notificationService = notificationService;
    }

    public Map<String, Object> rateConversation(String userId, String conversationId, int rating, String comment) {
        User customer = requestUserService.requireUser(userId);
        requestUserService.requireRole(customer, "CUSTOMER");

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));
        if (!customer.getId().equals(conversation.getCustomerId())) {
            throw new RuntimeException("Conversation not found");
        }
        if (!"pending_rating".equalsIgnoreCase(conversation.getStatus())
                && !"closed".equalsIgnoreCase(conversation.getStatus())) {
            throw new RuntimeException("This conversation cannot be rated yet");
        }
        if (conversation.getCustomerRating() != null && conversation.getCustomerRating() > 0) {
            throw new RuntimeException("You already rated this conversation");
        }

        validateRating(rating);
        saveFeedback("CONVERSATION", conversationId, customer, conversation.getSalesAgentId(),
                conversation.getSalesAgentName(), "SALES_AGENT", rating, comment, null);

        conversation.setCustomerRating(rating);
        conversation.setRatingComment(comment);
        conversation.setRatedAt(LocalDateTime.now());
        conversation.setStatus("closed");
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        notifySupervisors("Conversation rated",
                customer.getFullName() + " rated a sales chat " + rating + "/5");

        return Map.of("message", "Thank you for your feedback", "rating", rating);
    }

    public Map<String, Object> rateTicket(String userId, String ticketId, int rating, String comment) {
        User customer = requestUserService.requireUser(userId);
        requestUserService.requireRole(customer, "CUSTOMER");

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        if (!customer.getId().equals(ticket.getCustomerId())) {
            throw new RuntimeException("Ticket not found");
        }

        validateRating(rating);
        if (feedbackRepository.findByTypeAndReferenceIdAndCustomerId("TICKET", ticketId, customer.getId()).isPresent()) {
            throw new RuntimeException("You already rated this ticket");
        }

        saveFeedback("TICKET", ticketId, customer, ticket.getAssigneeId(),
                ticket.getAssigneeName(), "SUPPORT_AGENT", rating, comment, null);

        notifySupervisors("Support ticket rated",
                customer.getFullName() + " rated support " + rating + "/5 on \"" + ticket.getSubject() + "\"");

        return Map.of("message", "Thank you for your feedback", "rating", rating);
    }

    public Map<String, Object> getPurchaseSurvey(String token) {
        Invoice invoice = invoiceRepository.findBySurveyToken(token)
                .orElseThrow(() -> new RuntimeException("Survey link is invalid or expired"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invoiceId", invoice.getId());
        result.put("description", invoice.getDescription());
        result.put("amount", invoice.getAmount());
        result.put("completed", invoice.isSurveyCompleted());
        result.put("agentName", invoice.getSalesAgentName());
        return result;
    }

    public Map<String, Object> submitPurchaseSurvey(String token, Map<String, Object> body) {
        Invoice invoice = invoiceRepository.findBySurveyToken(token)
                .orElseThrow(() -> new RuntimeException("Survey link is invalid or expired"));
        if (invoice.isSurveyCompleted()) {
            throw new RuntimeException("Survey already submitted");
        }

        int processRating = parseRating(body.get("processRating"));
        int agentRating = parseOptionalAgentRating(body.get("agentRating"));
        String comment = body.get("comment") != null ? body.get("comment").toString().trim() : "";

        Map<String, Object> questionnaire = new LinkedHashMap<>();
        questionnaire.put("processRating", processRating);
        questionnaire.put("checkoutEase", parseOptionalRating(body.get("checkoutEase")));
        questionnaire.put("recommendScore", parseOptionalRating(body.get("recommendScore")));
        questionnaire.put("deliveryExpectation", stringVal(body.get("deliveryExpectation")));
        questionnaire.put("wouldBuyAgain", stringVal(body.get("wouldBuyAgain")));

        User customer = userRepository.findById(invoice.getCustomerId()).orElse(null);
        String customerName = customer != null ? customer.getFullName() : invoice.getCustomerName();
        String customerId = customer != null ? customer.getId() : invoice.getCustomerId();

        CustomerFeedback feedback = new CustomerFeedback();
        feedback.setType("PURCHASE");
        feedback.setReferenceId(invoice.getId());
        feedback.setCustomerId(customerId);
        feedback.setCustomerName(customerName);
        feedback.setAgentId(null);
        feedback.setAgentName(invoice.getSalesAgentName());
        feedback.setAgentRole(null);
        feedback.setRating(processRating);
        feedback.setComment(comment);
        feedback.setQuestionnaire(questionnaire);
        feedback.setCreatedAt(LocalDateTime.now());
        feedbackRepository.save(feedback);

        if (invoice.getSalesAgentId() != null && agentRating > 0) {
            recalculateAgentRating(invoice.getSalesAgentId());
            User agent = userRepository.findById(invoice.getSalesAgentId()).orElse(null);
            if (agent != null) {
                CustomerFeedback agentFb = new CustomerFeedback();
                agentFb.setType("PURCHASE_AGENT");
                agentFb.setReferenceId(invoice.getId());
                agentFb.setCustomerId(customerId);
                agentFb.setCustomerName(customerName);
                agentFb.setAgentId(agent.getId());
                agentFb.setAgentName(agent.getFullName());
                agentFb.setAgentRole(agent.getRole());
                agentFb.setRating(agentRating);
                agentFb.setComment(comment);
                agentFb.setCreatedAt(LocalDateTime.now());
                feedbackRepository.save(agentFb);
                recalculateAgentRating(agent.getId());
            }
        }

        invoice.setSurveyCompleted(true);
        invoiceRepository.save(invoice);

        if (customerId != null) {
            notificationService.clearPurchaseSurveyPrompt(customerId, invoice.getId());
        }

        notifySupervisors("Purchase survey received",
                (customerName != null ? customerName : "A customer") + " rated their purchase experience " + processRating + "/5");

        return Map.of("message", "Thank you for completing the survey");
    }

    public List<Map<String, Object>> listFeedbackForStaff(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");

        return collectUnifiedFeedback().stream()
                .map(this::toFeedbackView)
                .collect(Collectors.toList());
    }

    public Map<String, Object> feedbackOverview(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");

        List<UnifiedFeedbackItem> all = collectUnifiedFeedback();
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate lastMonthStart = monthStart.minusMonths(1);
        LocalDate lastMonthEnd = monthStart.minusDays(1);

        List<UnifiedFeedbackItem> thisMonth = filterByDateRange(all, monthStart, today);
        List<UnifiedFeedbackItem> lastMonth = filterByDateRange(all, lastMonthStart, lastMonthEnd);

        double csat = averageRating(all);
        double csatThisMonth = averageRating(thisMonth);
        double csatLastMonth = averageRating(lastMonth);
        int nps = calculateNps(all);
        int npsThisMonth = calculateNps(thisMonth);
        int npsLastMonth = calculateNps(lastMonth);

        long totalReviews = all.size();
        long reviewsThisMonth = thisMonth.size();
        long reviewsLastMonth = lastMonth.size();

        int responseRate = calculateResponseRate(all.size());
        int responseRateThisMonth = calculateResponseRate(thisMonth.size(), monthStart, today);
        int responseRateLastMonth = calculateResponseRate(lastMonth.size(), lastMonthStart, lastMonthEnd);

        Map<String, Object> sentiment = buildSentimentBreakdown(all);

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            LocalDate start = today.withDayOfMonth(1).minusMonths(i);
            LocalDate end = start.plusMonths(1).minusDays(1);
            List<UnifiedFeedbackItem> monthItems = filterByDateRange(all, start, end);
            trend.add(Map.of(
                    "label", start.format(DateTimeFormatter.ofPattern("MMM")),
                    "value", Math.round(averageRating(monthItems) * 10)
            ));
        }

        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = 0; i < Math.min(all.size(), 50); i++) {
            UnifiedFeedbackItem item = all.get(i);
            Map<String, Object> row = toFeedbackView(item);
            row.put("displayId", String.format("F-%03d", i + 1));
            row.put("sentiment", sentimentLabel(item.rating()));
            row.put("dateLabel", formatRelative(item.createdAt()));
            recent.add(row);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stats", Map.of(
                "csat", roundOneDecimal(csat),
                "csatTrend", percentChange(csatThisMonth, csatLastMonth),
                "nps", nps,
                "npsTrend", nps - npsLastMonth,
                "totalReviews", totalReviews,
                "reviewsTrend", percentChange(reviewsThisMonth, reviewsLastMonth),
                "responseRate", responseRate,
                "responseRateTrend", responseRate - responseRateLastMonth
        ));
        response.put("sentiment", sentiment);
        response.put("trend", trend);
        response.put("recent", recent);
        return response;
    }

    private record UnifiedFeedbackItem(
            String id,
            String source,
            String type,
            String referenceId,
            String customerName,
            String agentName,
            String agentRole,
            int rating,
            String comment,
            LocalDateTime createdAt,
            Map<String, Object> questionnaire
    ) {}

    private List<UnifiedFeedbackItem> collectUnifiedFeedback() {
        Set<String> seenTicketRefs = new HashSet<>();
        List<UnifiedFeedbackItem> items = new ArrayList<>();

        for (CustomerFeedback feedback : feedbackRepository.findAllByOrderByCreatedAtDesc()) {
            if ("TICKET".equalsIgnoreCase(feedback.getType()) && feedback.getReferenceId() != null) {
                seenTicketRefs.add(feedback.getReferenceId());
            }
            items.add(new UnifiedFeedbackItem(
                    feedback.getId(),
                    "customer_feedback",
                    feedback.getType(),
                    feedback.getReferenceId(),
                    feedback.getCustomerName(),
                    feedback.getAgentName(),
                    feedback.getAgentRole(),
                    feedback.getRating(),
                    feedback.getComment(),
                    feedback.getCreatedAt(),
                    feedback.getQuestionnaire()
            ));
        }

        for (TicketFeedback feedback : ticketFeedbackRepository.findAllByOrderByCreatedAtDesc()) {
            if (feedback.getTicketId() != null && seenTicketRefs.contains(feedback.getTicketId())) {
                continue;
            }
            String agentName = resolveAgentName(feedback.getAssigneeId());
            items.add(new UnifiedFeedbackItem(
                    feedback.getId(),
                    "ticket_feedback",
                    "TICKET",
                    feedback.getTicketId(),
                    feedback.getCustomerName(),
                    agentName,
                    "SUPPORT_AGENT",
                    feedback.getRating(),
                    feedback.getComment(),
                    feedback.getCreatedAt(),
                    null
            ));
        }

        items.sort(Comparator.comparing(UnifiedFeedbackItem::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    private String resolveAgentName(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return "—";
        }
        return userRepository.findById(agentId)
                .map(User::getFullName)
                .orElse("—");
    }

    private List<UnifiedFeedbackItem> filterByDateRange(List<UnifiedFeedbackItem> items,
                                                        LocalDate start,
                                                        LocalDate end) {
        return items.stream()
                .filter(item -> item.createdAt() != null)
                .filter(item -> {
                    LocalDate date = item.createdAt().toLocalDate();
                    return !date.isBefore(start) && !date.isAfter(end);
                })
                .toList();
    }

    private double averageRating(List<UnifiedFeedbackItem> items) {
        if (items.isEmpty()) {
            return 0;
        }
        return items.stream().mapToInt(UnifiedFeedbackItem::rating).average().orElse(0);
    }

    private double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private int percentChange(double current, double previous) {
        if (previous <= 0) {
            return current > 0 ? 100 : 0;
        }
        return (int) Math.round(((current - previous) / previous) * 100);
    }

    private int calculateNps(List<UnifiedFeedbackItem> items) {
        if (items.isEmpty()) {
            return 0;
        }
        int promoters = 0;
        int detractors = 0;
        int counted = 0;
        for (UnifiedFeedbackItem item : items) {
            int score = npsScore(item);
            if (score < 0) {
                continue;
            }
            counted++;
            if (score >= 9) {
                promoters++;
            } else if (score <= 6) {
                detractors++;
            }
        }
        if (counted == 0) {
            return 0;
        }
        return (int) Math.round(((promoters * 100.0) / counted) - ((detractors * 100.0) / counted));
    }

    private int npsScore(UnifiedFeedbackItem item) {
        if (item.questionnaire() != null && item.questionnaire().get("recommendScore") != null) {
            Object value = item.questionnaire().get("recommendScore");
            int score = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
            return Math.max(0, Math.min(10, score));
        }
        if (item.rating() <= 0) {
            return -1;
        }
        return Math.max(0, Math.min(10, item.rating() * 2));
    }

    private int calculateResponseRate(long reviewCount) {
        long eligible = countEligibleFeedbackOpportunities();
        if (eligible <= 0) {
            return reviewCount > 0 ? 100 : 0;
        }
        return (int) Math.min(100, Math.round((reviewCount * 100.0) / eligible));
    }

    private int calculateResponseRate(long reviewCount, LocalDate start, LocalDate end) {
        long eligible = countEligibleFeedbackOpportunities(start, end);
        if (eligible <= 0) {
            return reviewCount > 0 ? 100 : 0;
        }
        return (int) Math.min(100, Math.round((reviewCount * 100.0) / eligible));
    }

    private long countEligibleFeedbackOpportunities() {
        return countEligibleFeedbackOpportunities(null, null);
    }

    private long countEligibleFeedbackOpportunities(LocalDate start, LocalDate end) {
        long closedTickets = ticketRepository.findAll().stream()
                .filter(ticket -> isClosedTicket(ticket.getStatus()))
                .filter(ticket -> inDateRange(ticket.getUpdatedAt() != null ? ticket.getUpdatedAt() : ticket.getCreatedAt(), start, end))
                .count();
        long closedConversations = conversationRepository.findAll().stream()
                .filter(conversation -> isClosedConversation(conversation.getStatus()))
                .filter(conversation -> inDateRange(conversation.getUpdatedAt() != null ? conversation.getUpdatedAt() : conversation.getCreatedAt(), start, end))
                .count();
        long paidInvoices = invoiceRepository.findAll().stream()
                .filter(invoice -> "paid".equalsIgnoreCase(invoice.getStatus()))
                .filter(invoice -> invoice.getSurveyToken() != null && !invoice.getSurveyToken().isBlank())
                .filter(invoice -> inDateRange(invoice.getPaidAt() != null ? invoice.getPaidAt() : invoice.getCreatedAt(), start, end))
                .count();
        return closedTickets + closedConversations + paidInvoices;
    }

    private boolean isClosedTicket(String status) {
        return status != null && List.of("resolved", "closed").contains(status.toLowerCase());
    }

    private boolean isClosedConversation(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.toLowerCase();
        return "closed".equals(normalized) || "pending_rating".equals(normalized);
    }

    private boolean inDateRange(LocalDateTime dateTime, LocalDate start, LocalDate end) {
        if (dateTime == null) {
            return false;
        }
        if (start == null || end == null) {
            return true;
        }
        LocalDate date = dateTime.toLocalDate();
        return !date.isBefore(start) && !date.isAfter(end);
    }

    private Map<String, Object> buildSentimentBreakdown(List<UnifiedFeedbackItem> items) {
        long positive = items.stream().filter(item -> item.rating() >= 4).count();
        long neutral = items.stream().filter(item -> item.rating() == 3).count();
        long negative = items.stream().filter(item -> item.rating() <= 2).count();
        long total = Math.max(items.size(), 1);
        Map<String, Object> sentiment = new LinkedHashMap<>();
        sentiment.put("positive", positive);
        sentiment.put("neutral", neutral);
        sentiment.put("negative", negative);
        sentiment.put("positivePercent", (int) Math.round((positive * 100.0) / total));
        sentiment.put("neutralPercent", (int) Math.round((neutral * 100.0) / total));
        sentiment.put("negativePercent", (int) Math.round((negative * 100.0) / total));
        return sentiment;
    }

    private String sentimentLabel(int rating) {
        if (rating >= 4) {
            return "Positive";
        }
        if (rating == 3) {
            return "Neutral";
        }
        return "Negative";
    }

    private String formatRelative(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "—";
        }
        long minutes = ChronoUnit.MINUTES.between(dateTime, LocalDateTime.now());
        if (minutes < 1) {
            return "Just now";
        }
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " min ago" : " mins ago");
        }
        long hours = ChronoUnit.HOURS.between(dateTime, LocalDateTime.now());
        if (hours < 24) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        long days = ChronoUnit.DAYS.between(dateTime.toLocalDate(), LocalDate.now());
        if (days == 1) {
            return "1 day ago";
        }
        if (days < 7) {
            return days + " days ago";
        }
        if (days < 14) {
            return "1 week ago";
        }
        long weeks = days / 7;
        if (weeks < 5) {
            return weeks + " weeks ago";
        }
        return dateTime.format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
    }

    public Map<String, Object> agentRatingSummary(String agentId) {
        User agent = userRepository.findById(agentId)
                .orElseThrow(() -> new RuntimeException("Agent not found"));
        return Map.of(
                "agentId", agent.getId(),
                "agentName", agent.getFullName(),
                "role", agent.getRole(),
                "averageRating", agent.getAverageRating(),
                "ratingCount", agent.getRatingCount()
        );
    }

    private void saveFeedback(String type, String referenceId, User customer, String agentId, String agentName,
                              String agentRole, int rating, String comment, Map<String, Object> questionnaire) {
        if (feedbackRepository.findByTypeAndReferenceIdAndCustomerId(type, referenceId, customer.getId()).isPresent()) {
            throw new RuntimeException("Feedback already submitted");
        }

        CustomerFeedback feedback = new CustomerFeedback();
        feedback.setType(type);
        feedback.setReferenceId(referenceId);
        feedback.setCustomerId(customer.getId());
        feedback.setCustomerName(customer.getFullName());
        feedback.setAgentId(agentId);
        feedback.setAgentName(agentName);
        feedback.setAgentRole(agentRole);
        feedback.setRating(rating);
        feedback.setComment(comment);
        feedback.setQuestionnaire(questionnaire);
        feedback.setCreatedAt(LocalDateTime.now());
        feedbackRepository.save(feedback);

        if (agentId != null && !agentId.isBlank()) {
            recalculateAgentRating(agentId);
        }
    }

    public void recalculateAgentRating(String agentId) {
        List<CustomerFeedback> ratings = feedbackRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .filter(f -> !"PURCHASE".equalsIgnoreCase(f.getType()))
                .toList();
        if (ratings.isEmpty()) {
            userRepository.findById(agentId).ifPresent(agent -> {
                agent.setAverageRating(0);
                agent.setRatingCount(0);
                agent.setUpdatedAt(LocalDateTime.now());
                userRepository.save(agent);
            });
            return;
        }
        double avg = ratings.stream().mapToInt(CustomerFeedback::getRating).average().orElse(0);
        userRepository.findById(agentId).ifPresent(agent -> {
            agent.setAverageRating(Math.round(avg * 10.0) / 10.0);
            agent.setRatingCount(ratings.size());
            agent.setUpdatedAt(LocalDateTime.now());
            userRepository.save(agent);
        });
    }

    private Map<String, Object> toFeedbackView(CustomerFeedback f) {
        return toFeedbackView(new UnifiedFeedbackItem(
                f.getId(),
                "customer_feedback",
                f.getType(),
                f.getReferenceId(),
                f.getCustomerName(),
                f.getAgentName(),
                f.getAgentRole(),
                f.getRating(),
                f.getComment(),
                f.getCreatedAt(),
                f.getQuestionnaire()
        ));
    }

    private Map<String, Object> toFeedbackView(UnifiedFeedbackItem f) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", f.id());
        row.put("type", f.type());
        row.put("referenceId", f.referenceId());
        row.put("customerName", f.customerName());
        row.put("agentName", f.agentName() != null ? f.agentName() : "—");
        row.put("agentRole", f.agentRole());
        row.put("rating", f.rating());
        row.put("comment", f.comment());
        row.put("questionnaire", f.questionnaire());
        row.put("createdAt", f.createdAt());
        row.put("sentiment", sentimentLabel(f.rating()));
        row.put("dateLabel", formatRelative(f.createdAt()));
        return row;
    }

    private void notifySupervisors(String title, String message) {
        userRepository.findAll().stream()
                .filter(u -> u.isActive() && ("ADMIN".equalsIgnoreCase(u.getRole()) || "SUPERVISOR".equalsIgnoreCase(u.getRole())))
                .forEach(u -> notificationService.create(u.getId(), title, message, "info"));
    }

    private void validateRating(int rating) {
        if (rating < 1 || rating > 5) {
            throw new RuntimeException("Rating must be between 1 and 5");
        }
    }

    private int parseRating(Object value) {
        if (value == null) throw new RuntimeException("Rating is required");
        int rating = value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
        validateRating(rating);
        return rating;
    }

    private int parseOptionalAgentRating(Object value) {
        if (value == null) {
            return 0;
        }
        int rating = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
        if (rating <= 0) {
            return 0;
        }
        validateRating(rating);
        return rating;
    }

    private int parseOptionalRating(Object value) {
        if (value == null) return 0;
        int rating = value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
        return Math.max(0, Math.min(10, rating));
    }

    private String stringVal(Object value) {
        return value == null ? null : value.toString();
    }
}
