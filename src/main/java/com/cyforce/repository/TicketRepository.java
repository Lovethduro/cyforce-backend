package com.cyforce.repository;

import com.cyforce.model.Ticket;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketRepository extends MongoRepository<Ticket, String> {
    List<Ticket> findByCustomerIdOrderByCreatedAtDesc(String customerId);
    List<Ticket> findByAssigneeIdOrderByCreatedAtDesc(String assigneeId);
    List<Ticket> findByStatusInOrderByCreatedAtDesc(List<String> statuses);
    List<Ticket> findAllByOrderByCreatedAtDesc();
}
