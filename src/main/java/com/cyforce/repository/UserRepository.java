package com.cyforce.repository;

import com.cyforce.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByVerificationToken(String token);

    Optional<User> findByAuthProviderAndProviderId(String authProvider, String providerId);

    Optional<User> findByPasswordResetToken(String token);

    List<User> findByRoleIn(List<String> roles);

    List<User> findTop10ByIsEmailVerifiedFalseOrIsActiveFalseOrderByCreatedAtDesc();

    List<User> findTop10ByIsActiveTrueAndIsEmailVerifiedFalseOrderByCreatedAtDesc();

    List<User> findTop5ByOrderByCreatedAtDesc();

    List<User> findTop500ByOrderByCreatedAtDesc();

    List<User> findByCreatedAtAfter(LocalDateTime createdAt);

    List<User> findByLastLoginAtAfter(LocalDateTime lastLoginAt);

    long countByIsActiveTrue();

    long countByIsEmailVerifiedTrue();

    long countByMfaEnabledTrue();

    long countByIsActiveTrueAndIsEmailVerifiedFalse();

    long countByRole(String role);
}