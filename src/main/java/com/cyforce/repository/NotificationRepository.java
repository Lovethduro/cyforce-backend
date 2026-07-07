package com.cyforce.repository;

import com.cyforce.model.Notification;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends MongoRepository<Notification, String> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(String userId);
    List<Notification> findTop30ByUserIdOrderByCreatedAtDesc(String userId);
    long countByUserIdAndReadFalse(String userId);
    Optional<Notification> findByUserIdAndReferenceId(String userId, String referenceId);
    void deleteByUserId(String userId);
}
