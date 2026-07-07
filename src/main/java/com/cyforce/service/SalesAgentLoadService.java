package com.cyforce.service;

import com.cyforce.model.AgentPresence;
import com.cyforce.model.User;
import com.cyforce.repository.AgentPresenceRepository;
import com.cyforce.repository.LeadRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SalesAgentLoadService {

    static final int MAX_ACTIVE_LEADS_PER_AGENT = 25;

    private final UserRepository userRepository;
    private final LeadRepository leadRepository;
    private final AgentPresenceRepository agentPresenceRepository;

    public SalesAgentLoadService(UserRepository userRepository,
                                 LeadRepository leadRepository,
                                 AgentPresenceRepository agentPresenceRepository) {
        this.userRepository = userRepository;
        this.leadRepository = leadRepository;
        this.agentPresenceRepository = agentPresenceRepository;
    }

    public List<Map<String, Object>> agentsWithLoad() {
        return userRepository.findAll().stream()
                .filter(u -> u.isActive() && "SALES_AGENT".equalsIgnoreCase(u.getRole()))
                .map(this::toAgentRow)
                .sorted(Comparator.comparingLong(r -> ((Number) r.get("activeLeads")).longValue()))
                .collect(Collectors.toList());
    }

    public User pickLightestLoadAgent() {
        List<Map<String, Object>> agents = agentsWithLoad().stream()
                .filter(row -> Boolean.TRUE.equals(row.get("canAssign")))
                .toList();
        if (agents.isEmpty()) {
            throw new RuntimeException("No sales agents are available for assignment right now.");
        }
        String agentId = String.valueOf(agents.get(0).get("id"));
        return userRepository.findById(agentId)
                .orElseThrow(() -> new RuntimeException("Sales agent not found"));
    }

    public User pickLightestLoadAgentExcluding(List<String> excludedAgentIds) {
        List<Map<String, Object>> agents = agentsWithLoad().stream()
                .filter(row -> Boolean.TRUE.equals(row.get("canAssign")))
                .filter(row -> excludedAgentIds == null || !excludedAgentIds.contains(String.valueOf(row.get("id"))))
                .toList();
        if (agents.isEmpty()) {
            return pickLightestLoadAgent();
        }
        String agentId = String.valueOf(agents.get(0).get("id"));
        return userRepository.findById(agentId)
                .orElseThrow(() -> new RuntimeException("Sales agent not found"));
    }

    public long activeLeadCount(String agentId) {
        return leadRepository.findByOwnerIdOrderByCreatedAtDesc(agentId).stream()
                .filter(l -> !List.of("converted", "lost").contains(l.getStatus()))
                .count();
    }

    public Map<String, Object> toAgentRow(User agent) {
        long activeLeads = activeLeadCount(agent.getId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", agent.getId());
        row.put("name", agent.getFullName());
        row.put("activeLeads", activeLeads);
        row.put("canAssign", activeLeads < MAX_ACTIVE_LEADS_PER_AGENT);
        return row;
    }

    public User pickAvailableSalesAgentForQuotes() {
        List<AgentPresence> availableSales = agentPresenceRepository.findByTeam("sales").stream()
                .filter(p -> "available".equalsIgnoreCase(p.getStatus()))
                .sorted(Comparator.comparing(AgentPresence::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (AgentPresence presence : availableSales) {
            User agent = userRepository.findById(presence.getUserId()).orElse(null);
            if (agent != null && agent.isActive() && "SALES_AGENT".equalsIgnoreCase(agent.getRole())
                    && activeLeadCount(agent.getId()) < MAX_ACTIVE_LEADS_PER_AGENT) {
                return agent;
            }
        }
        return pickLightestLoadAgent();
    }
}
