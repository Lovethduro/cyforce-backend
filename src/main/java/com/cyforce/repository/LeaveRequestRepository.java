package com.cyforce.repository;

import com.cyforce.model.LeaveRequest;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface LeaveRequestRepository extends MongoRepository<LeaveRequest, String> {
    List<LeaveRequest> findByUserIdOrderByCreatedAtDesc(String userId);
    List<LeaveRequest> findByStatusOrderByCreatedAtDesc(String status);
}
