package com.cyforce.controller;

import com.cyforce.model.PaymentTransaction;
import com.cyforce.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
@CrossOrigin(origins = "http://localhost:3000")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/paystack/initialize")
    public ResponseEntity<?> initPaystack(@RequestHeader("X-User-Id") String userId,
                                            @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(paymentService.initializePaystack(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/flutterwave/initialize")
    public ResponseEntity<?> initFlutterwave(@RequestHeader("X-User-Id") String userId,
                                               @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(paymentService.initializeFlutterwave(userId, body));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/paystack/verify/{reference}")
    public ResponseEntity<?> verifyPaystack(@PathVariable String reference) {
        try {
            PaymentTransaction tx = paymentService.verifyPaystack(reference);
            return ResponseEntity.ok(paymentService.paymentResult(tx));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/flutterwave/verify/{reference}")
    public ResponseEntity<?> verifyFlutterwave(@PathVariable String reference) {
        try {
            PaymentTransaction tx = paymentService.verifyFlutterwave(reference);
            return ResponseEntity.ok(paymentService.paymentResult(tx));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/paystack/webhook")
    public ResponseEntity<?> paystackWebhook(@RequestBody Map<String, Object> payload) {
        paymentService.handlePaystackWebhook(payload);
        return ResponseEntity.ok(Map.of("received", true));
    }

    @PostMapping("/flutterwave/webhook")
    public ResponseEntity<?> flutterwaveWebhook(@RequestBody Map<String, Object> payload) {
        paymentService.handleFlutterwaveWebhook(payload);
        return ResponseEntity.ok(Map.of("received", true));
    }

    @PostMapping("/complete-local/{reference}")
    public ResponseEntity<?> completeLocal(@PathVariable String reference) {
        try {
            return ResponseEntity.ok(paymentService.completePaymentWithDetails(reference));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sandbox/complete/{reference}")
    public ResponseEntity<?> sandboxComplete(@PathVariable String reference) {
        return completeLocal(reference);
    }

    @GetMapping("/transactions")
    public ResponseEntity<?> transactions(@RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(paymentService.userTransactions(userId));
    }
}
