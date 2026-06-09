package com.cyforce.repository;

import com.cyforce.model.PaymentTransaction;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends MongoRepository<PaymentTransaction, String> {
    Optional<PaymentTransaction> findByReference(String reference);
    List<PaymentTransaction> findByUserIdOrderByCreatedAtDesc(String userId);
}
