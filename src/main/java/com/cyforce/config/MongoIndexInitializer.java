package com.cyforce.config;

import jakarta.annotation.PostConstruct;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class MongoIndexInitializer {

    private final MongoTemplate mongoTemplate;

    public MongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void ensureIndexes() {
        mongoTemplate.indexOps("tickets")
                .ensureIndex(new Index().on("assigneeId", Sort.Direction.ASC).on("status", Sort.Direction.ASC));
        mongoTemplate.indexOps("tickets")
                .ensureIndex(new Index().on("status", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
        mongoTemplate.indexOps("tickets")
                .ensureIndex(new Index().on("customerId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
        mongoTemplate.indexOps("ticket_messages")
                .ensureIndex(new Index().on("ticketId", Sort.Direction.ASC).on("createdAt", Sort.Direction.ASC));
        mongoTemplate.indexOps("notifications")
                .ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
        mongoTemplate.indexOps("notifications")
                .ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("read", Sort.Direction.ASC));
        mongoTemplate.indexOps("tickets")
                .ensureIndex(new Index().on("createdAt", Sort.Direction.DESC));
        mongoTemplate.indexOps("tickets")
                .ensureIndex(new Index().on("assigneeId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
        mongoTemplate.indexOps("ticket_feedback")
                .ensureIndex(new Index().on("assigneeId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
        mongoTemplate.indexOps("ticket_feedback")
                .ensureIndex(new Index().on("createdAt", Sort.Direction.DESC));
        mongoTemplate.indexOps("users")
                .ensureIndex(new Index().on("role", Sort.Direction.ASC));
        mongoTemplate.indexOps("users")
                .ensureIndex(new Index().on("createdAt", Sort.Direction.DESC));
        mongoTemplate.indexOps("users")
                .ensureIndex(new Index().on("lastLoginAt", Sort.Direction.DESC));
        mongoTemplate.indexOps("users")
                .ensureIndex(new Index().on("isActive", Sort.Direction.ASC).on("isEmailVerified", Sort.Direction.ASC));
        mongoTemplate.indexOps("leads")
                .ensureIndex(new Index().on("createdAt", Sort.Direction.DESC));
    }
}
