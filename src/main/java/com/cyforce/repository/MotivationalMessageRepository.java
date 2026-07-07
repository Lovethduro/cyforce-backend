package com.cyforce.repository;

import com.cyforce.model.MotivationalMessage;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MotivationalMessageRepository extends MongoRepository<MotivationalMessage, String> {
    List<MotivationalMessage> findByActiveTrueOrderByCreatedAtDesc();
}
