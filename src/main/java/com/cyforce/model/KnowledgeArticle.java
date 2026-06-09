package com.cyforce.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "knowledge_articles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeArticle {
    @Id
    private String id;
    private String title;
    private String category;
    private String content;
    private String tags;
    private int views;
    private boolean published;
    private LocalDateTime createdAt;
}
