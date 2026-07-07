package com.cyforce.controller;

import com.cyforce.service.KnowledgeBaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/knowledge-base")
@CrossOrigin(origins = "http://localhost:3000")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(knowledgeBaseService.listPublished(q));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable String id) {
        try {
            return ResponseEntity.ok(knowledgeBaseService.getPublishedArticle(id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
