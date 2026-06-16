package com.cyforce.repository;

import com.cyforce.model.Conversation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends MongoRepository<Conversation, String> {
    List<Conversation> findByCustomerIdOrderByUpdatedAtDesc(String customerId);
    List<Conversation> findBySalesAgentIdOrderByUpdatedAtDesc(String salesAgentId);
    List<Conversation> findBySupervisorIdOrderByUpdatedAtDesc(String supervisorId);
    List<Conversation> findByStatusOrderByCreatedAtDesc(String status);
    List<Conversation> findByStatusInAndUpdatedAtBefore(Collection<String> statuses, LocalDateTime updatedAt);
    Optional<Conversation> findByGuestAccessToken(String guestAccessToken);
    Optional<Conversation> findByLeadId(String leadId);
}
