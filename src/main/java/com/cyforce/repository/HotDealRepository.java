package com.cyforce.repository;

import com.cyforce.model.HotDeal;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HotDealRepository extends MongoRepository<HotDeal, String> {
    List<HotDeal> findByActiveTrueOrderByCreatedAtDesc();

    @Query(value = "{ '$or': [ { 'active': true }, { 'isActive': true } ] }", sort = "{ 'createdAt': -1 }")
    List<HotDeal> findPublishedOrderByCreatedAtDesc();
}
