package com.cyforce.service;

import com.cyforce.model.ApprovalRequest;
import com.cyforce.model.Lead;
import com.cyforce.model.LeadAssignmentLog;
import com.cyforce.model.User;
import com.cyforce.repository.ApprovalRequestRepository;
import com.cyforce.repository.LeadAssignmentLogRepository;
import com.cyforce.repository.LeadRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class SupervisorOpsService {

    private static final int MIN_LEADS_BEFORE_ASSIGN = 0;

    private final LeadRepository leadRepository;
    private final UserRepository userRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final LeadAssignmentLogRepository assignmentLogRepository;
    private final RequestUserService requestUserService;
    private final NotificationService notificationService;
    private final LeadService leadService;
    private final LeaveService leaveService;
    private final SalesAgentLoadService salesAgentLoadService;

    public SupervisorOpsService(LeadRepository leadRepository,
                                UserRepository userRepository,
                                ApprovalRequestRepository approvalRepository,
                                LeadAssignmentLogRepository assignmentLogRepository,
                                RequestUserService requestUserService,
                                NotificationService notificationService,
                                LeadService leadService,
                                LeaveService leaveService,
                                SalesAgentLoadService salesAgentLoadService) {
        this.leadRepository = leadRepository;
        this.userRepository = userRepository;
        this.approvalRepository = approvalRepository;
        this.assignmentLogRepository = assignmentLogRepository;
        this.requestUserService = requestUserService;
        this.notificationService = notificationService;
        this.leadService = leadService;
        this.leaveService = leaveService;
        this.salesAgentLoadService = salesAgentLoadService;
    }

    public List<Map<String, Object>> salesAgentsWithLoad(String userId) {
        User supervisor = requestUserService.requireUser(userId);
        requestUserService.requireRole(supervisor, "SUPERVISOR", "ADMIN");
        return salesAgentLoadService.agentsWithLoad();
    }

    public Map<String, Object> previewLeadAssignment(String supervisorId, String leadId) {
        User supervisor = requestUserService.requireUser(supervisorId);
        requestUserService.requireRole(supervisor, "SUPERVISOR");

        leadRepository.findById(leadId).orElseThrow(() -> new RuntimeException("Lead not found"));
        User suggested = pickAgentForAutoAssignment(supervisor);
        Map<String, Object> row = salesAgentLoadService.toAgentRow(suggested);

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("suggestedAgentId", suggested.getId());
        preview.put("suggestedAgentName", suggested.getFullName());
        preview.put("activeLeads", row.get("activeLeads"));
        preview.put("message", "Will assign to " + suggested.getFullName()
                + " (" + row.get("activeLeads") + " active leads)");
        return preview;
    }

    public Map<String, Object> assignLead(String supervisorId, String leadId, Map<String, Object> body) {
        User supervisor = requestUserService.requireUser(supervisorId);
        requestUserService.requireRole(supervisor, "SUPERVISOR");

        Lead lead = leadRepository.findById(leadId).orElseThrow(() -> new RuntimeException("Lead not found"));
        if (lead.getOwnerId() != null && !lead.getOwnerId().isBlank()) {
            throw new RuntimeException("Lead is already assigned");
        }

        String agentId = body.get("agentId") == null ? "" : body.get("agentId").toString().trim();
        boolean autoAssign = agentId.isBlank() || "auto".equalsIgnoreCase(agentId) || Boolean.TRUE.equals(body.get("auto"));
        boolean overrideAgent = Boolean.TRUE.equals(body.get("overrideAgent"));

        User agent;
        if (autoAssign && !overrideAgent) {
            agent = pickAgentForAutoAssignment(supervisor);
        } else {
            if (agentId.isBlank()) {
                throw new RuntimeException("Select a sales agent for an override assignment");
            }
            agent = userRepository.findById(agentId)
                    .orElseThrow(() -> new RuntimeException("Sales agent not found"));
            if (!"SALES_AGENT".equalsIgnoreCase(agent.getRole())) {
                throw new RuntimeException("Selected user is not a sales agent");
            }
        }

        long activeLeads = salesAgentLoadService.activeLeadCount(agent.getId());
        if (activeLeads >= SalesAgentLoadService.MAX_ACTIVE_LEADS_PER_AGENT) {
            throw new RuntimeException(agent.getFullName() + " already has the maximum active leads ("
                    + SalesAgentLoadService.MAX_ACTIVE_LEADS_PER_AGENT + ")");
        }

        boolean emergency = Boolean.TRUE.equals(body.get("emergency"));
        boolean customerRequest = Boolean.TRUE.equals(body.get("customerRequest"));
        String proofUrl = body.get("proofUrl") != null ? body.get("proofUrl").toString() : null;
        LocalDateTime monthAgo = LocalDateTime.now().minusDays(30);
        boolean assignedThisMonth = !assignmentLogRepository
                .findByAssignedByUserIdAndAgentIdAndAssignedAtAfter(supervisor.getId(), agent.getId(), monthAgo)
                .isEmpty();

        if (!autoAssign || overrideAgent) {
            if (assignedThisMonth && !emergency && !customerRequest) {
                throw new RuntimeException("This agent was already assigned a lead by you this month. "
                        + "Use emergency or customer-request with proof for admin approval.");
            }

            if (assignedThisMonth && (emergency || customerRequest)) {
                if (customerRequest && (proofUrl == null || proofUrl.isBlank())) {
                    throw new RuntimeException("Proof is required when a customer specifically requested an agent");
                }
                return submitAssignmentApproval(supervisor, lead, agent, body, emergency, customerRequest, proofUrl);
            }
        }

        String assignmentType = autoAssign && !overrideAgent ? "auto" : "manual";
        return completeAssignment(supervisor, lead, agent, assignmentType);
    }

    private User pickAgentForAutoAssignment(User supervisor) {
        LocalDateTime monthAgo = LocalDateTime.now().minusDays(30);
        List<String> usedThisMonth = assignmentLogRepository
                .findByAssignedByUserIdAndAssignedAtAfter(supervisor.getId(), monthAgo)
                .stream()
                .map(LeadAssignmentLog::getAgentId)
                .distinct()
                .toList();
        return salesAgentLoadService.pickLightestLoadAgentExcluding(usedThisMonth);
    }

    private Map<String, Object> submitAssignmentApproval(User supervisor,
                                                         Lead lead,
                                                         User agent,
                                                         Map<String, Object> body,
                                                         boolean emergency,
                                                         boolean customerRequest,
                                                         String proofUrl) {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setType("lead_assignment");
        approval.setRequestedByUserId(supervisor.getId());
        approval.setRequestedByName(supervisor.getFullName());
        approval.setStatus("pending");
        approval.setEmergencyReason(body.get("reason") != null ? body.get("reason").toString() : "");
        approval.setProofUrl(proofUrl);
        approval.setPayload(Map.of(
                "leadId", lead.getId(),
                "leadName", lead.getName(),
                "agentId", agent.getId(),
                "agentName", agent.getFullName(),
                "emergency", emergency,
                "customerRequest", customerRequest
        ));
        approval.setCreatedAt(LocalDateTime.now());
        approvalRepository.save(approval);
        try {
            notificationService.broadcastToAudience(
                    "Lead assignment needs admin approval",
                    supervisor.getFullName() + " requested to assign " + lead.getName() + " to " + agent.getFullName(),
                    "admins"
            );
        } catch (Exception ignored) {
        }
        return Map.of("needsApproval", true, "approvalId", approval.getId(), "message", "Sent to admin for approval");
    }

    public Map<String, Object> completeAssignment(User supervisor, Lead lead, User agent, String type) {
        lead.setOwnerId(agent.getId());
        lead.setOwnerName(agent.getFullName());
        lead.setUpdatedAt(LocalDateTime.now());
        leadRepository.save(lead);

        LeadAssignmentLog log = new LeadAssignmentLog();
        log.setLeadId(lead.getId());
        log.setAgentId(agent.getId());
        log.setAgentName(agent.getFullName());
        log.setAssignedByUserId(supervisor.getId());
        log.setAssignmentType(type);
        log.setAssignedAt(LocalDateTime.now());
        assignmentLogRepository.save(log);

        try {
            String assigner = "auto".equals(type)
                    ? "CyForce"
                    : supervisor.getFullName();
            notificationService.create(agent.getId(), "New lead assigned",
                    assigner + " assigned lead " + lead.getName() + " to you",
                    "info");
        } catch (Exception ignored) {
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("needsApproval", false);
        result.put("leadId", lead.getId());
        result.put("ownerId", agent.getId());
        result.put("ownerName", agent.getFullName());
        result.put("message", "Lead assigned to " + agent.getFullName());
        return result;
    }

    public Lead createLead(String supervisorId, Map<String, Object> body) {
        User supervisor = requestUserService.requireUser(supervisorId);
        requestUserService.requireRole(supervisor, "SUPERVISOR", "ADMIN");
        return leadService.createLead(supervisor.getId(), body);
    }

    public List<Map<String, Object>> pendingApprovals(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPERVISOR", "ADMIN");
        return approvalRepository.findByStatusOrderByCreatedAtDesc("pending").stream()
                .filter(a -> approvalVisibleToReviewer(user, a))
                .map(a -> approvalRow(user, a))
                .toList();
    }

    public Map<String, Object> reviewApproval(String reviewerId, String approvalId, boolean approve, String note) {
        User reviewer = requestUserService.requireUser(reviewerId);
        requestUserService.requireRole(reviewer, "ADMIN", "SUPERVISOR");

        ApprovalRequest approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> new RuntimeException("Approval not found"));
        if (!"pending".equals(approval.getStatus())) {
            throw new RuntimeException("Approval already processed");
        }

        if ("leave".equals(approval.getType())) {
            String leaveId = String.valueOf(approval.getPayload().get("leaveRequestId"));
            leaveService.reviewLeave(reviewerId, leaveId, approve, note);
            ApprovalRequest updated = approvalRepository.findById(approvalId).orElse(approval);
            return approvalRow(reviewer, updated);
        }

        if ("lead_assignment".equals(approval.getType()) && "SUPERVISOR".equalsIgnoreCase(reviewer.getRole())) {
            throw new RuntimeException("Lead assignment exceptions must be approved by an admin");
        }

        approval.setStatus(approve ? "approved" : "rejected");
        approval.setReviewedByUserId(reviewer.getId());
        approval.setReviewedByName(reviewer.getFullName());
        approval.setReviewNote(note);
        approval.setReviewedAt(LocalDateTime.now());
        approvalRepository.save(approval);

        if (approve && "lead_assignment".equals(approval.getType())) {
            String leadId = String.valueOf(approval.getPayload().get("leadId"));
            String agentId = String.valueOf(approval.getPayload().get("agentId"));
            Lead lead = leadRepository.findById(leadId).orElseThrow(() -> new RuntimeException("Lead not found"));
            User agent = userRepository.findById(agentId).orElseThrow(() -> new RuntimeException("Agent not found"));
            User requester = userRepository.findById(approval.getRequestedByUserId()).orElse(reviewer);
            completeAssignment(requester, lead, agent, "approved_exception");
        }

        return approvalRow(reviewer, approval);
    }

    public Map<String, Object> broadcast(String userId, String message, String audience) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPERVISOR", "ADMIN");
        if (message == null || message.isBlank()) {
            throw new RuntimeException("Message is required");
        }
        int count = notificationService.broadcastToAudience("Team broadcast", message.trim(), audience);
        return Map.of("message", "Broadcast sent", "recipients", count);
    }

    private boolean approvalVisibleToReviewer(User reviewer, ApprovalRequest approval) {
        if ("lead_assignment".equals(approval.getType()) && "SUPERVISOR".equalsIgnoreCase(reviewer.getRole())) {
            return false;
        }
        if ("leave".equals(approval.getType()) && approval.getPayload() != null) {
            Object leaveId = approval.getPayload().get("leaveRequestId");
            if (leaveId == null) {
                return false;
            }
            return leaveService.reviewerCanActOnLeave(reviewer.getId(), String.valueOf(leaveId));
        }
        return true;
    }

    private Map<String, Object> approvalRow(User reviewer, ApprovalRequest approval) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", approval.getId());
        row.put("approvalId", approval.getId());
        row.put("name", approval.getRequestedByName());
        row.put("role", approval.getRequestedByUserId());
        row.put("type", formatType(approval.getType()));
        row.put("status", approval.getStatus());
        row.put("payload", approval.getPayload());
        if ("leave".equals(approval.getType()) && approval.getPayload() != null) {
            Object start = approval.getPayload().get("startDate");
            Object end = approval.getPayload().get("endDate");
            Object days = approval.getPayload().get("daysRequested");
            row.put("email", (start != null ? start : "?") + " to " + (end != null ? end : "?")
                    + (days != null ? " · " + days + " day(s)" : ""));
        } else {
            row.put("email", String.valueOf(approval.getPayload().getOrDefault("leadName", approval.getType())));
        }
        row.put("proofUrl", approval.getProofUrl());
        row.put("emergencyReason", approval.getEmergencyReason());
        row.put("createdAt", approval.getCreatedAt());
        return row;
    }

    private String formatType(String type) {
        if (type == null) {
            return "Request";
        }
        return switch (type) {
            case "lead_assignment" -> "Lead assignment";
            case "leave" -> "Leave request";
            case "user_registration" -> "User registration";
            default -> type;
        };
    }
}
