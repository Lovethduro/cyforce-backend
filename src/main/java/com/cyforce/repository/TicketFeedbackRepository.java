package com.cyforce.repository;

import com.cyforce.model.TicketFeedback;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketFeedbackRepository extends MongoRepository<TicketFeedback, String> {
    List<TicketFeedback> findTop20ByAssigneeIdOrderByCreatedAtDesc(String assigneeId);
    List<TicketFeedback> findByAssigneeIdOrderByCreatedAtDesc(String assigneeId);
    List<TicketFeedback> findAllByOrderByCreatedAtDesc();
    List<TicketFeedback> findTop100ByOrderByCreatedAtDesc();
}
