package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "sales_playbook")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalesPlaybookEntry {
    @Id
    private String id;
    /** product | discount | objection | process | general */
    private String category;
    private String productCategory;
    private String title;
    private String summary;
    private String body;
    private Integer maxDiscountPercent;
    private Integer supervisorApprovalAbove;
    private String keywords;
    private boolean pinned;
    private boolean active = true;
    private int sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
