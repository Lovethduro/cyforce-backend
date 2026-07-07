package com.cyforce.repository;

import com.cyforce.model.LeadAssignmentLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface LeadAssignmentLogRepository extends MongoRepository<LeadAssignmentLog, String> {
    List<LeadAssignmentLog> findByAssignedByUserIdAndAgentIdAndAssignedAtAfter(
            String assignedByUserId, String agentId, LocalDateTime after);
    List<LeadAssignmentLog> findByAssignedByUserIdAndAssignedAtAfter(String assignedByUserId, LocalDateTime after);
    long countByAgentIdAndAssignedAtAfter(String agentId, LocalDateTime after);
}
