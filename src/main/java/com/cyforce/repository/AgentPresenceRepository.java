package com.cyforce.repository;

import com.cyforce.model.AgentPresence;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentPresenceRepository extends MongoRepository<AgentPresence, String> {
    Optional<AgentPresence> findByUserId(String userId);
    List<AgentPresence> findByTeam(String team);
    List<AgentPresence> findAllByOrderByFullNameAsc();
}
