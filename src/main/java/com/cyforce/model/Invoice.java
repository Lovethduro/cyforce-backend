package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "invoices")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Invoice {
    @Id
    private String id;
    private String customerId;
    private String customerName;
    private long amount;
    private String currency;
    private String status;
    private String description;
    private LocalDateTime dueDate;
    private String paymentTransactionId;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}
