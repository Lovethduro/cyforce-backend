package com.cyforce.controller;

import com.cyforce.service.MessagingService;
import com.cyforce.service.PaymentService;
import com.cyforce.service.RatingService;
import com.cyforce.service.TicketService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/customer")
@CrossOrigin(origins = "http://localhost:3000")
public class CustomerController {

    private final TicketService ticketService;
    private final PaymentService paymentService;
    private final MessagingService messagingService;
    private final RatingService ratingService;

    public CustomerController(TicketService ticketService,
                              PaymentService paymentService,
                              MessagingService messagingService,
                              RatingService ratingService) {
        this.ticketService = ticketService;
        this.paymentService = paymentService;
        this.messagingService = messagingService;
        this.ratingService = ratingService;
    }

    @GetMapping("/dashboard/stats")
    public ResponseEntity<?> stats(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(ticketService.customerStats(userId));
    }

    @GetMapping("/tickets")
    public ResponseEntity<?> tickets(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(ticketService.customerTickets(userId));
    }

    @PostMapping("/tickets")
    public ResponseEntity<?> createTicket(@RequestHeader("X-User-Id") String userId, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(ticketService.createTicket(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/tickets/with-attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createTicketWithAttachment(@RequestHeader("X-User-Id") String userId,
                                                        @RequestParam String subject,
                                                        @RequestParam String description,
                                                        @RequestParam(defaultValue = "general") String category,
                                                        @RequestParam(defaultValue = "medium") String priority,
                                                        @RequestParam(required = false) MultipartFile attachment) {
        try {
            Map<String, String> body = new HashMap<>();
            body.put("subject", subject);
            body.put("description", description);
            body.put("category", category);
            body.put("priority", priority);
            return ResponseEntity.ok(ticketService.createTicket(userId, body, attachment));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tickets/{id}")
    public ResponseEntity<?> ticket(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(Map.of(
                    "ticket", ticketService.getTicket(userId, id),
                    "messages", ticketService.getMessages(userId, id)
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tickets/{id}/reply")
    public ResponseEntity<?> reply(@RequestHeader("X-User-Id") String userId,
                                   @PathVariable String id,
                                   @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(ticketService.customerReply(userId, id, body.get("message")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/conversations")
    public ResponseEntity<?> conversations(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(messagingService.customerConversations(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/conversations")
    public ResponseEntity<?> startConversation(@RequestHeader("X-User-Id") String userId,
                                               @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(messagingService.startConversation(userId, body.get("subject"), body.get("message")));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<?> conversationDetail(@RequestHeader("X-User-Id") String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(messagingService.conversationDetail(userId, id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<?> sendMessage(@RequestHeader("X-User-Id") String userId,
                                         @PathVariable String id,
                                         @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(messagingService.sendMessage(userId, id, body.get("message"), null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/conversations/{id}/rating")
    public ResponseEntity<?> rateConversation(@RequestHeader("X-User-Id") String userId,
                                              @PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        try {
            int rating = body.get("rating") instanceof Number n ? n.intValue() : Integer.parseInt(body.get("rating").toString());
            String comment = body.get("comment") != null ? body.get("comment").toString() : "";
            return ResponseEntity.ok(ratingService.rateConversation(userId, id, rating, comment));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tickets/{id}/rating")
    public ResponseEntity<?> rateTicket(@RequestHeader("X-User-Id") String userId,
                                        @PathVariable String id,
                                        @RequestBody Map<String, Object> body) {
        try {
            int rating = body.get("rating") instanceof Number n ? n.intValue() : Integer.parseInt(body.get("rating").toString());
            String comment = body.get("comment") != null ? body.get("comment").toString() : "";
            return ResponseEntity.ok(ratingService.rateTicket(userId, id, rating, comment));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/invoices")
    public ResponseEntity<?> invoices(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(paymentService.userInvoices(userId));
    }

    @GetMapping("/billing/overview")
    public ResponseEntity<?> billingOverview(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(paymentService.billingOverview(userId));
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestHeader("X-User-Id") String userId,
                                      @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(paymentService.checkoutCart(userId, body));
        } catch (RuntimeException e) {
            paymentService.notifyCheckoutFailed(userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
