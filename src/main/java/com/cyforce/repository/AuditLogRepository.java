package com.cyforce.repository;

import com.cyforce.model.AuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends MongoRepository<AuditLog, String> {
    List<AuditLog> findTop20ByOrderByCreatedAtDesc();
    List<AuditLog> findAllByOrderByCreatedAtDesc();
    List<AuditLog> findTop10ByActionInOrderByCreatedAtDesc(List<String> actions);
    List<AuditLog> findByActionInOrderByCreatedAtDesc(List<String> actions);
}
