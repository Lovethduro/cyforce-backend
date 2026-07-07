package com.cyforce.service;

import com.cyforce.model.SalesPlaybookEntry;
import com.cyforce.model.User;
import com.cyforce.repository.SalesPlaybookRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SalesPlaybookService {

    private final SalesPlaybookRepository playbookRepository;
    private final RequestUserService requestUserService;

    public SalesPlaybookService(SalesPlaybookRepository playbookRepository,
                                RequestUserService requestUserService) {
        this.playbookRepository = playbookRepository;
        this.requestUserService = requestUserService;
    }

    public List<Map<String, Object>> listForAgent(String userId, String category, String query) {
        requestUserService.requireRole(requestUserService.requireUser(userId),
                "SALES_AGENT", "ADMIN", "SUPERVISOR");
        return filterAndMap(playbookRepository.findByActiveTrueOrderByPinnedDescSortOrderAscTitleAsc(), category, query);
    }

    public Map<String, Object> getForAgent(String userId, String id) {
        requestUserService.requireRole(requestUserService.requireUser(userId),
                "SALES_AGENT", "ADMIN", "SUPERVISOR");
        SalesPlaybookEntry entry = playbookRepository.findById(id)
                .filter(SalesPlaybookEntry::isActive)
                .orElseThrow(() -> new RuntimeException("Playbook entry not found"));
        return toMap(entry, true);
    }

    public List<Map<String, Object>> listForManage(String userId) {
        requestUserService.requireRole(requestUserService.requireUser(userId), "ADMIN", "SUPERVISOR");
        return playbookRepository.findAllByOrderByPinnedDescSortOrderAscTitleAsc().stream()
                .map(e -> toMap(e, true))
                .collect(Collectors.toList());
    }

    public Map<String, Object> create(String userId, Map<String, Object> body) {
        requestUserService.requireRole(requestUserService.requireUser(userId), "ADMIN", "SUPERVISOR");
        SalesPlaybookEntry entry = new SalesPlaybookEntry();
        applyBody(entry, body);
        entry.setCreatedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());
        return toMap(playbookRepository.save(entry), true);
    }

    public Map<String, Object> update(String userId, String id, Map<String, Object> body) {
        requestUserService.requireRole(requestUserService.requireUser(userId), "ADMIN", "SUPERVISOR");
        SalesPlaybookEntry entry = playbookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Playbook entry not found"));
        applyBody(entry, body);
        entry.setUpdatedAt(LocalDateTime.now());
        return toMap(playbookRepository.save(entry), true);
    }

    public void delete(String userId, String id) {
        requestUserService.requireRole(requestUserService.requireUser(userId), "ADMIN", "SUPERVISOR");
        if (!playbookRepository.existsById(id)) {
            throw new RuntimeException("Playbook entry not found");
        }
        playbookRepository.deleteById(id);
    }

    public List<String> categories() {
        return List.of("product", "discount", "objection", "process", "general");
    }

    private List<Map<String, Object>> filterAndMap(List<SalesPlaybookEntry> entries, String category, String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        return entries.stream()
                .filter(e -> category == null || category.isBlank() || category.equalsIgnoreCase(e.getCategory()))
                .filter(e -> q.isEmpty() || matchesQuery(e, q))
                .map(e -> toMap(e, false))
                .collect(Collectors.toList());
    }

    private boolean matchesQuery(SalesPlaybookEntry e, String q) {
        return contains(e.getTitle(), q)
                || contains(e.getSummary(), q)
                || contains(e.getKeywords(), q)
                || contains(e.getProductCategory(), q)
                || contains(e.getBody(), q);
    }

    private boolean contains(String value, String q) {
        return value != null && value.toLowerCase().contains(q);
    }

    private void applyBody(SalesPlaybookEntry entry, Map<String, Object> body) {
        if (body.get("title") != null) entry.setTitle(String.valueOf(body.get("title")).trim());
        if (body.get("category") != null) entry.setCategory(String.valueOf(body.get("category")).trim().toLowerCase());
        if (body.get("productCategory") != null) entry.setProductCategory(String.valueOf(body.get("productCategory")).trim());
        if (body.get("summary") != null) entry.setSummary(String.valueOf(body.get("summary")).trim());
        if (body.get("body") != null) entry.setBody(String.valueOf(body.get("body")).trim());
        if (body.get("keywords") != null) entry.setKeywords(String.valueOf(body.get("keywords")).trim());
        if (body.get("maxDiscountPercent") != null && !String.valueOf(body.get("maxDiscountPercent")).isBlank()) {
            entry.setMaxDiscountPercent(Integer.parseInt(String.valueOf(body.get("maxDiscountPercent"))));
        }
        if (body.get("supervisorApprovalAbove") != null && !String.valueOf(body.get("supervisorApprovalAbove")).isBlank()) {
            entry.setSupervisorApprovalAbove(Integer.parseInt(String.valueOf(body.get("supervisorApprovalAbove"))));
        }
        if (body.get("sortOrder") != null && !String.valueOf(body.get("sortOrder")).isBlank()) {
            entry.setSortOrder(Integer.parseInt(String.valueOf(body.get("sortOrder"))));
        }
        if (body.get("pinned") != null) entry.setPinned(Boolean.parseBoolean(String.valueOf(body.get("pinned"))));
        if (body.get("active") != null) entry.setActive(Boolean.parseBoolean(String.valueOf(body.get("active"))));
        if (entry.getTitle() == null || entry.getTitle().isBlank()) {
            throw new RuntimeException("Title is required");
        }
        if (entry.getCategory() == null || entry.getCategory().isBlank()) {
            entry.setCategory("general");
        }
    }

    private Map<String, Object> toMap(SalesPlaybookEntry e, boolean includeBody) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId());
        row.put("category", e.getCategory());
        row.put("productCategory", e.getProductCategory());
        row.put("title", e.getTitle());
        row.put("summary", e.getSummary());
        if (includeBody) row.put("body", e.getBody());
        row.put("maxDiscountPercent", e.getMaxDiscountPercent());
        row.put("supervisorApprovalAbove", e.getSupervisorApprovalAbove());
        row.put("keywords", e.getKeywords());
        row.put("pinned", e.isPinned());
        row.put("active", e.isActive());
        row.put("sortOrder", e.getSortOrder());
        row.put("updatedAt", e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
        return row;
    }
}
