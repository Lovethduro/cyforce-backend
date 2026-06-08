package com.cyforce.controller;

import com.cyforce.dto.AuthResponse;
import com.cyforce.dto.LoginRequest;
import com.cyforce.dto.MfaSetupInitRequest;
import com.cyforce.dto.MfaSetupVerifyRequest;
import com.cyforce.dto.OAuthLoginRequest;
import com.cyforce.dto.RegisterRequest;
import com.cyforce.service.AuthService;
import com.cyforce.service.EmailService;
import com.cyforce.service.MfaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:3000")
public class AuthController {

    private final AuthService authService;
    private final EmailService emailService;
    private final MfaService mfaService;

    public AuthController(AuthService authService, EmailService emailService, MfaService mfaService) {
        this.authService = authService;
        this.emailService = emailService;
        this.mfaService = mfaService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            AuthResponse response = authService.login(
                    request.getEmail(),
                    request.getPassword(),
                    request.getRole()
            );
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/oauth/google")
    public ResponseEntity<?> googleLogin(@RequestBody OAuthLoginRequest request) {
        try {
            return ResponseEntity.ok(authService.googleLogin(request.getToken(), request.getRole()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/oauth/microsoft")
    public ResponseEntity<?> microsoftLogin(@RequestBody OAuthLoginRequest request) {
        try {
            return ResponseEntity.ok(authService.microsoftLogin(request.getToken(), request.getRole()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        try {
            AuthResponse response = authService.register(request);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/test")
    public String test() {
        return "Auth controller is working!";
    }

    @GetMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestParam String token) {
        try {
            String message = authService.verifyEmail(token);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", message
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "success", false,
                            "error", e.getMessage()
                    ));
        }
    }

    @GetMapping("/verification-status")
    public ResponseEntity<?> verificationStatus(@RequestParam String email) {
        try {
            boolean verified = authService.isEmailVerified(email);
            return ResponseEntity.ok(Map.of("verified", verified));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        try {
            authService.resendVerificationEmail(email);
            return ResponseEntity.ok(Map.of("message", "Verification email sent successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/mfa/setup/init")
    public ResponseEntity<?> initMfaSetup(@RequestBody MfaSetupInitRequest request) {
        try {
            return ResponseEntity.ok(mfaService.initSetup(request.getUserId(), request.getMethod()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/mfa/setup/verify")
    public ResponseEntity<?> verifyMfaSetup(@RequestBody MfaSetupVerifyRequest request) {
        try {
            mfaService.verifySetup(request.getUserId(), request.getCode());
            return ResponseEntity.ok(Map.of("message", "MFA enabled successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/test-email")
    public ResponseEntity<?> testEmail(@RequestParam String to) {
        try {
            emailService.sendEmail(to, "Test Email from CyForce",
                    "<h1>✅ Test Successful!</h1><p>Your email configuration is working!</p>");
            return ResponseEntity.ok(Map.of("message", "Test email sent to: " + to));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}