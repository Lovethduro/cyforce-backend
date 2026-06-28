package com.cyforce.repository;

import com.cyforce.model.Ticket;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TicketRepository extends MongoRepository<Ticket, String> {
    List<Ticket> findByCustomerIdOrderByCreatedAtDesc(String customerId);
    List<Ticket> findByAssigneeIdOrderByCreatedAtDesc(String assigneeId);
    List<Ticket> findByStatusInOrderByCreatedAtDesc(List<String> statuses);
    long countByStatusIn(List<String> statuses);
    List<Ticket> findAllByOrderByCreatedAtDesc();
    List<Ticket> findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(String customerEmail);
    Optional<Ticket> findByGuestAccessToken(String guestAccessToken);
}
