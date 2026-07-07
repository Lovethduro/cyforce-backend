package com.cyforce.repository;

import com.cyforce.model.ApprovalRequest;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ApprovalRequestRepository extends MongoRepository<ApprovalRequest, String> {
    List<ApprovalRequest> findByStatusOrderByCreatedAtDesc(String status);
    List<ApprovalRequest> findByRequestedByUserIdOrderByCreatedAtDesc(String userId);
}
