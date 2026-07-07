package com.cyforce.repository;

import com.cyforce.model.CustomerReferral;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CustomerReferralRepository extends MongoRepository<CustomerReferral, String> {
    Optional<CustomerReferral> findByUserId(String userId);
    Optional<CustomerReferral> findByReferralCodeIgnoreCase(String referralCode);
}
