package com.cyforce.repository;

import com.cyforce.model.ConversationMessage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationMessageRepository extends MongoRepository<ConversationMessage, String> {
    List<ConversationMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);
}
