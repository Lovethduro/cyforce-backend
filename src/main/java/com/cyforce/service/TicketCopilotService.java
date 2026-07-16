package com.cyforce.service;

import com.cyforce.model.KnowledgeArticle;
import com.cyforce.model.Ticket;
import com.cyforce.model.TicketMessage;
import com.cyforce.model.User;
import com.cyforce.repository.KnowledgeArticleRepository;
import com.cyforce.repository.TicketMessageRepository;
import com.cyforce.repository.TicketRepository;
import com.cyforce.util.SensitiveDataMasker;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TicketCopilotService {

    private final RequestUserService requestUserService;
    private final TicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final KnowledgeArticleRepository articleRepository;
    private final GroqAiService groqAiService;

    public TicketCopilotService(RequestUserService requestUserService,
                                TicketRepository ticketRepository,
                                TicketMessageRepository messageRepository,
                                KnowledgeArticleRepository articleRepository,
                                GroqAiService groqAiService) {
        this.requestUserService = requestUserService;
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.articleRepository = articleRepository;
        this.groqAiService = groqAiService;
    }

    public Map<String, Object> summarize(String userId, String ticketId) {
        TicketContext ctx = loadContext(userId, ticketId);
        String fallback = buildFallbackSummary(ctx);
        String summary = groqAiService.isConfigured()
                ? groqAiService.complete(copilotSystemPrompt(), buildSummarizePrompt(ctx))
                : fallback;
        return result("summary", summary, ctx, fallback);
    }

    public Map<String, Object> suggestReply(String userId, String ticketId) {
        TicketContext ctx = loadContext(userId, ticketId);
        String fallback = "Thank you for reaching out. I am reviewing your ticket and will update you shortly.";
        String reply = groqAiService.isConfigured()
                ? groqAiService.complete(copilotSystemPrompt(), buildReplyPrompt(ctx))
                : fallback;
        return result("suggestedReply", reply, ctx, fallback);
    }

    public Map<String, Object> analyze(String userId, String ticketId) {
        TicketContext ctx = loadContext(userId, ticketId);
        String heuristic = analyzeHeuristic(ctx);
        String analysis = groqAiService.isConfigured()
                ? groqAiService.complete(copilotSystemPrompt(), buildAnalyzePrompt(ctx, heuristic))
                : heuristic;
        return result("analysis", analysis, ctx, heuristic);
    }

    private Map<String, Object> result(String key, String text, TicketContext ctx, String fallback) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(key, text == null || text.isBlank() ? fallback : text);
        response.put("aiEnabled", groqAiService.isConfigured());
        response.put("relatedArticles", ctx.relatedArticles());
        response.put("suggestedPriority", ctx.suggestedPriority());
        return response;
    }

    private TicketContext loadContext(String userId, String ticketId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "SUPPORT_AGENT", "ADMIN");

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        List<TicketMessage> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .filter(m -> !m.isInternalNote())
                .toList();

        List<Map<String, Object>> relatedArticles = findRelatedArticles(ticket);
        String suggestedPriority = suggestPriority(ticket, messages);

        return new TicketContext(ticket, messages, relatedArticles, suggestedPriority);
    }

    private List<Map<String, Object>> findRelatedArticles(Ticket ticket) {
        String haystack = ((ticket.getSubject() == null ? "" : ticket.getSubject()) + " "
                + (ticket.getDescription() == null ? "" : ticket.getDescription())).toLowerCase();
        Set<String> tokens = tokenize(haystack);
        if (tokens.isEmpty()) {
            return List.of();
        }

        return articleRepository.findByPublishedTrueOrderByViewsDesc().stream()
                .map(article -> Map.entry(article, scoreArticle(article, tokens)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(entry -> {
                    KnowledgeArticle article = entry.getKey();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", article.getId());
                    row.put("title", article.getTitle());
                    row.put("category", article.getCategory());
                    String content = article.getContent() == null ? "" : article.getContent();
                    row.put("excerpt", content.length() > 220 ? content.substring(0, 217) + "..." : content);
                    return row;
                })
                .toList();
    }

    private int scoreArticle(KnowledgeArticle article, Set<String> tokens) {
        String text = ((article.getTitle() == null ? "" : article.getTitle()) + " "
                + (article.getCategory() == null ? "" : article.getCategory()) + " "
                + (article.getTags() == null ? "" : article.getTags()) + " "
                + (article.getContent() == null ? "" : article.getContent())).toLowerCase();
        int score = 0;
        for (String token : tokens) {
            if (text.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream(text.split("[^a-z0-9]+"))
                .filter(t -> t.length() > 3)
                .limit(20)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String suggestPriority(Ticket ticket, List<TicketMessage> messages) {
        String combined = ((ticket.getSubject() == null ? "" : ticket.getSubject()) + " "
                + (ticket.getDescription() == null ? "" : ticket.getDescription()) + " "
                + messages.stream().map(TicketMessage::getMessage).filter(Objects::nonNull).collect(Collectors.joining(" ")))
                .toLowerCase();

        if (containsAny(combined, "urgent", "down", "offline", "breach", "outage", "not working", "failed", "critical")) {
            return "high";
        }
        if (containsAny(combined, "billing", "payment", "invoice", "refund", "charge")) {
            return "medium";
        }
        if (containsAny(combined, "how to", "question", "info", "when will")) {
            return "low";
        }
        String current = ticket.getPriority() == null ? "medium" : ticket.getPriority().toLowerCase();
        return current.isBlank() ? "medium" : current;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String buildFallbackSummary(TicketContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Customer ").append(nullToDash(ctx.ticket().getCustomerName()))
                .append(" reported: ").append(nullToDash(ctx.ticket().getSubject())).append(". ");
        sb.append("Status ").append(nullToDash(ctx.ticket().getStatus()))
                .append(", priority ").append(ctx.suggestedPriority()).append(". ");
        sb.append(ctx.messages().size()).append(" customer-visible message(s).");
        return sb.toString();
    }

    private String analyzeHeuristic(TicketContext ctx) {
        return "Suggested priority: " + ctx.suggestedPriority() + ". "
                + (ctx.relatedArticles().isEmpty()
                ? "No close knowledge-base matches — consider asking for device model, site address, and photos."
                : "Review the linked knowledge-base articles before replying.");
    }

    private String copilotSystemPrompt() {
        return """
                You are CyForce Support Copilot for a Nigerian technology company selling CCTV, solar, security, and automation solutions.
                Be professional, concise, and practical. Do not invent policies, prices, or SLA commitments.
                If information is missing, say what the agent should ask next.
                Use plain text only — no markdown, no asterisks, no bold markers.
                Use simple lines like "Priority: medium" or short sentences separated by line breaks.
                """;
    }

    private String buildSummarizePrompt(TicketContext ctx) {
        boolean sparse = isBlank(ctx.ticket().getDescription())
                && (ctx.ticket().getSubject() == null || ctx.ticket().getSubject().toLowerCase().contains("test"));
        if (sparse) {
            return "This ticket has little detail (subject/description may be a test). "
                    + "In 2-3 short plain-text lines (no markdown), tell the agent to ask the customer for product, site, and error details. "
                    + "Do not invent issues.\n"
                    + "Customer: " + nullToDash(ctx.ticket().getCustomerName());
        }
        return """
                Summarize this support ticket in 3-5 short plain-text lines for a support agent (no markdown).
                Ticket subject: %s
                Description: %s
                Status: %s | Current priority: %s | Suggested priority: %s
                Conversation:
                %s
                Knowledge base hints:
                %s
                """.formatted(
                nullToDash(ctx.ticket().getSubject()),
                redact(ctx.ticket().getDescription()),
                nullToDash(ctx.ticket().getStatus()),
                nullToDash(ctx.ticket().getPriority()),
                ctx.suggestedPriority(),
                formatMessages(ctx.messages()),
                formatArticles(ctx.relatedArticles())
        );
    }

    private String buildReplyPrompt(TicketContext ctx) {
        return """
                Draft a helpful customer-facing reply (under 120 words) for this ticket.
                Use a warm professional tone. Do not promise exact timelines unless already stated.
                Ticket subject: %s
                Description: %s
                Conversation:
                %s
                Knowledge base hints:
                %s
                """.formatted(
                nullToDash(ctx.ticket().getSubject()),
                redact(ctx.ticket().getDescription()),
                formatMessages(ctx.messages()),
                formatArticles(ctx.relatedArticles())
        );
    }

    private String buildAnalyzePrompt(TicketContext ctx, String heuristic) {
        return """
                Analyze urgency and next best action for the support agent.
                Return 2-3 short plain-text lines (no markdown), e.g.:
                Priority: medium
                Risk: low
                Next step: ask the customer for more details about...
                Heuristic baseline: %s
                Ticket subject: %s
                Description: %s
                Conversation:
                %s
                """.formatted(
                heuristic,
                nullToDash(ctx.ticket().getSubject()),
                redact(ctx.ticket().getDescription()),
                formatMessages(ctx.messages())
        );
    }

    private String formatMessages(List<TicketMessage> messages) {
        if (messages.isEmpty()) {
            return "(no messages yet)";
        }
        StringBuilder sb = new StringBuilder();
        for (TicketMessage message : messages) {
            sb.append("- ").append(nullToDash(message.getAuthorName())).append(": ")
                    .append(redact(message.getMessage())).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatArticles(List<Map<String, Object>> articles) {
        if (articles.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> article : articles) {
            sb.append("- ").append(article.get("title")).append(": ").append(article.get("excerpt")).append("\n");
        }
        return sb.toString().trim();
    }

    private String redact(String value) {
        return SensitiveDataMasker.redactText(nullToDash(value));
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record TicketContext(
            Ticket ticket,
            List<TicketMessage> messages,
            List<Map<String, Object>> relatedArticles,
            String suggestedPriority
    ) {}
}
