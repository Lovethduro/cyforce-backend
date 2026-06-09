package com.cyforce.repository;

import com.cyforce.model.KnowledgeArticle;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeArticleRepository extends MongoRepository<KnowledgeArticle, String> {
    List<KnowledgeArticle> findByPublishedTrueOrderByViewsDesc();
}
