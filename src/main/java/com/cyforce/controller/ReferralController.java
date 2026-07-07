package com.cyforce.controller;

import com.cyforce.service.ReferralService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/referrals")
@CrossOrigin(origins = "http://localhost:3000")
public class ReferralController {

    private final ReferralService referralService;

    public ReferralController(ReferralService referralService) {
        this.referralService = referralService;
    }

    @GetMapping("/me")
    public ResponseEntity<?> myReferral(@RequestHeader("X-User-Id") String userId) {
        try {
            return ResponseEntity.ok(referralService.getMyReferral(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
