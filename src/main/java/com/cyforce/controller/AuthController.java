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
import com.cyforce.service.UserService;
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
    private final UserService userService;

    public AuthController(AuthService authService, EmailService emailService, MfaService mfaService, UserService userService) {
        this.authService = authService;
        this.emailService = emailService;
        this.mfaService = mfaService;
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        try {
            return ResponseEntity.ok(userService.getProfile(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        }
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
            mfaService.verifySetup(request.getUserId(), request.getCode(), request.getSecret());
            return ResponseEntity.ok(Map.of("message", "MFA enabled successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/mfa/setup/reset")
    public ResponseEntity<?> resetMfaSetup(@RequestBody MfaSetupInitRequest request) {
        try {
            mfaService.resetSetup(request.getUserId());
            return ResponseEntity.ok(Map.of("message", "MFA setup reset. You can start again."));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/mfa/login/verify")
    public ResponseEntity<?> verifyMfaLogin(@RequestBody Map<String, String> request) {
        try {
            AuthResponse response = authService.verifyMfaLogin(
                    request.get("challengeToken"),
                    request.get("code")
            );
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/mfa/login/resend")
    public ResponseEntity<?> resendMfaLogin(@RequestBody Map<String, String> request) {
        try {
            authService.resendMfaLoginCode(request.get("challengeToken"));
            return ResponseEntity.ok(Map.of("message", "A new verification code has been sent."));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }
        return ResponseEntity.ok(authService.forgotPassword(email));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> request) {
        try {
            String token = request.get("token");
            String password = request.get("password");
            if (password == null || password.isBlank()) {
                password = request.get("newPassword");
            }
            if (token == null || token.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Reset token is required"));
            }
            if (password == null || password.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "New password is required"));
            }
            authService.resetPassword(token, password);
            return ResponseEntity.ok(Map.of("message", "Password updated successfully. You can sign in now."));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody Map<String, String> request) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        try {
            authService.changePassword(userId, request.get("currentPassword"), request.get("newPassword"));
            return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
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