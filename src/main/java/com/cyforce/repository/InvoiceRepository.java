package com.cyforce.repository;

import com.cyforce.model.Invoice;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends MongoRepository<Invoice, String> {
    List<Invoice> findByCustomerIdOrderByCreatedAtDesc(String customerId);
    List<Invoice> findBySalesAgentIdOrderByCreatedAtDesc(String salesAgentId);
    List<Invoice> findBySalesAgentIdAndStatus(String salesAgentId, String status);
    List<Invoice> findAllByOrderByCreatedAtDesc();
    Optional<Invoice> findBySurveyToken(String surveyToken);
}
