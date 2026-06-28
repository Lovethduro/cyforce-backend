package com.cyforce.repository;

import com.cyforce.model.TicketMessage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketMessageRepository extends MongoRepository<TicketMessage, String> {
    List<TicketMessage> findByTicketIdOrderByCreatedAtAsc(String ticketId);
    List<TicketMessage> findTop5ByAuthorIdOrderByCreatedAtDesc(String authorId);
}
