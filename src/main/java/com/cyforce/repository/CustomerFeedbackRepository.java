package com.cyforce.repository;

import com.cyforce.model.CustomerFeedback;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerFeedbackRepository extends MongoRepository<CustomerFeedback, String> {
    List<CustomerFeedback> findByTypeOrderByCreatedAtDesc(String type);
    List<CustomerFeedback> findByAgentIdOrderByCreatedAtDesc(String agentId);
    Optional<CustomerFeedback> findByTypeAndReferenceIdAndCustomerId(String type, String referenceId, String customerId);
    List<CustomerFeedback> findAllByOrderByCreatedAtDesc();
}
