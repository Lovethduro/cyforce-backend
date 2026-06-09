package com.cyforce.service;

import com.cyforce.model.Lead;
import com.cyforce.model.User;
import com.cyforce.repository.LeadRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class LeadService {

    private final LeadRepository leadRepository;
    private final RequestUserService requestUserService;
    private final AuditLogService auditLogService;

    public LeadService(LeadRepository leadRepository,
                       RequestUserService requestUserService,
                       AuditLogService auditLogService) {
        this.leadRepository = leadRepository;
        this.requestUserService = requestUserService;
        this.auditLogService = auditLogService;
    }

    public List<Lead> myLeads(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SALES_AGENT", "ADMIN", "SUPERVISOR");
        return leadRepository.findByOwnerIdOrderByCreatedAtDesc(user.getId());
    }

    public List<Lead> allLeads(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN", "SUPERVISOR");
        return leadRepository.findAllByOrderByCreatedAtDesc();
    }

    public Lead createLead(String userId, Map<String, Object> body) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SALES_AGENT", "ADMIN", "SUPERVISOR");
        Lead lead = new Lead();
        lead.setName((String) body.get("name"));
        lead.setEmail((String) body.get("email"));
        lead.setPhone((String) body.get("phone"));
        lead.setCompany((String) body.get("company"));
        lead.setSource((String) body.getOrDefault("source", "website"));
        lead.setStatus("new");
        lead.setScore(body.get("score") instanceof Number ? ((Number) body.get("score")).intValue() : 50);
        lead.setOwnerId(user.getId());
        lead.setOwnerName(user.getFullName());
        lead.setCreatedAt(LocalDateTime.now());
        lead.setUpdatedAt(LocalDateTime.now());
        Lead saved = leadRepository.save(lead);
        auditLogService.log(user, "LEAD_CREATE", "Lead Management", saved.getName());
        return saved;
    }

    public Lead updateLead(String userId, String leadId, Map<String, Object> body) {
        User user = requestUserService.requireUser(userId);
        Lead lead = leadRepository.findById(leadId).orElseThrow(() -> new RuntimeException("Lead not found"));
        if (body.get("status") != null) lead.setStatus((String) body.get("status"));
        if (body.get("name") != null) lead.setName((String) body.get("name"));
        if (body.get("email") != null) lead.setEmail((String) body.get("email"));
        if (body.get("phone") != null) lead.setPhone((String) body.get("phone"));
        if (body.get("company") != null) lead.setCompany((String) body.get("company"));
        if (body.get("score") instanceof Number) lead.setScore(((Number) body.get("score")).intValue());
        lead.setUpdatedAt(LocalDateTime.now());
        return leadRepository.save(lead);
    }

    public Map<String, Object> salesStats(String userId) {
        List<Lead> leads = myLeads(userId);
        long qualified = leads.stream().filter(l -> "qualified".equals(l.getStatus())).count();
        long converted = leads.stream().filter(l -> "converted".equals(l.getStatus())).count();
        return Map.of("totalLeads", leads.size(), "qualifiedLeads", qualified, "convertedLeads", converted);
    }
}
