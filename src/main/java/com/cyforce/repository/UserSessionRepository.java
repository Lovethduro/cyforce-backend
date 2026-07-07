package com.cyforce.repository;

import com.cyforce.model.UserSession;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends MongoRepository<UserSession, String> {
    Optional<UserSession> findBySessionId(String sessionId);
    List<UserSession> findByUserIdAndActiveTrue(String userId);
    long countByUserIdAndActiveTrue(String userId);
    long countByActiveTrue();
    List<UserSession> findTop200ByOrderByStartedAtDesc();
}
