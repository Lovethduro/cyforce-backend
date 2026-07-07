package com.cyforce.service;

import com.cyforce.model.KnowledgeArticle;
import com.cyforce.model.User;
import com.cyforce.repository.KnowledgeArticleRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeBaseService {

    private final KnowledgeArticleRepository articleRepository;
    private final RequestUserService requestUserService;

    public KnowledgeBaseService(KnowledgeArticleRepository articleRepository,
                                RequestUserService requestUserService) {
        this.articleRepository = articleRepository;
        this.requestUserService = requestUserService;
    }

    public List<Map<String, Object>> listPublished(String query) {
        return articleRepository.findByPublishedTrueOrderByViewsDesc().stream()
                .filter(article -> matchesQuery(article, query))
                .map(this::toListView)
                .toList();
    }

    public Map<String, Object> getPublishedArticle(String id) {
        KnowledgeArticle article = articleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Article not found"));
        if (!article.isPublished()) {
            throw new RuntimeException("Article not found");
        }
        article.setViews(article.getViews() + 1);
        articleRepository.save(article);
        return toDetailView(article);
    }

    public List<Map<String, Object>> listForAdmin(String userId) {
        requireManageRole(userId);
        return articleRepository.findAll().stream()
                .sorted((a, b) -> {
                    LocalDateTime left = a.getCreatedAt() != null ? a.getCreatedAt() : LocalDateTime.MIN;
                    LocalDateTime right = b.getCreatedAt() != null ? b.getCreatedAt() : LocalDateTime.MIN;
                    return right.compareTo(left);
                })
                .map(this::toAdminView)
                .toList();
    }

    public Map<String, Object> createArticle(String userId, Map<String, Object> body) {
        requireManageRole(userId);
        KnowledgeArticle article = new KnowledgeArticle();
        article.setTitle(requireText(body.get("title"), "Title is required"));
        article.setCategory(stringVal(body.get("category"), "General"));
        article.setContent(stringVal(body.get("content"), ""));
        article.setTags(stringVal(body.get("tags"), ""));
        article.setViews(0);
        article.setPublished(Boolean.TRUE.equals(body.get("published")) || "published".equalsIgnoreCase(stringVal(body.get("status"), "")));
        article.setCreatedAt(LocalDateTime.now());
        return toAdminView(articleRepository.save(article));
    }

    public Map<String, Object> updateArticle(String userId, String id, Map<String, Object> body) {
        requireManageRole(userId);
        KnowledgeArticle article = articleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Article not found"));
        if (body.get("title") != null) article.setTitle(requireText(body.get("title"), "Title is required"));
        if (body.get("category") != null) article.setCategory(stringVal(body.get("category"), "General"));
        if (body.get("content") != null) article.setContent(stringVal(body.get("content"), ""));
        if (body.get("tags") != null) article.setTags(stringVal(body.get("tags"), ""));
        if (body.containsKey("published")) {
            article.setPublished(Boolean.TRUE.equals(body.get("published")));
        }
        if (body.get("status") != null) {
            article.setPublished("published".equalsIgnoreCase(body.get("status").toString()));
        }
        return toAdminView(articleRepository.save(article));
    }

    public void deleteArticle(String userId, String id) {
        requireManageRole(userId);
        if (!articleRepository.existsById(id)) {
            throw new RuntimeException("Article not found");
        }
        articleRepository.deleteById(id);
    }

    private void requireManageRole(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "ADMIN");
    }

    private boolean matchesQuery(KnowledgeArticle article, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase();
        return (article.getTitle() != null && article.getTitle().toLowerCase().contains(q))
                || (article.getCategory() != null && article.getCategory().toLowerCase().contains(q))
                || (article.getContent() != null && article.getContent().toLowerCase().contains(q))
                || (article.getTags() != null && article.getTags().toLowerCase().contains(q));
    }

    private Map<String, Object> toListView(KnowledgeArticle article) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", article.getId());
        view.put("title", article.getTitle());
        view.put("category", article.getCategory());
        view.put("views", article.getViews());
        view.put("status", article.isPublished() ? "published" : "draft");
        return view;
    }

    private Map<String, Object> toDetailView(KnowledgeArticle article) {
        Map<String, Object> view = toListView(article);
        view.put("content", article.getContent());
        view.put("tags", article.getTags());
        return view;
    }

    private Map<String, Object> toAdminView(KnowledgeArticle article) {
        Map<String, Object> view = toDetailView(article);
        view.put("createdAt", article.getCreatedAt());
        return view;
    }

    private String requireText(Object value, String errorMessage) {
        String text = stringVal(value, "");
        if (text.isBlank()) {
            throw new RuntimeException(errorMessage);
        }
        return text;
    }

    private String stringVal(Object value, String fallback) {
        return value == null ? fallback : value.toString().trim();
    }
}
