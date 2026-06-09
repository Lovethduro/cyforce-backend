package com.cyforce.repository;

import com.cyforce.model.Lead;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadRepository extends MongoRepository<Lead, String> {
    List<Lead> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    List<Lead> findAllByOrderByCreatedAtDesc();
}
