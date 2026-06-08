package com.cyforce.service;

import com.cyforce.dto.AuthResponse;
import com.cyforce.dto.OAuthUserInfo;
import com.cyforce.dto.RegisterRequest;
import com.cyforce.model.User;
import com.cyforce.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private static final Map<String, String> ROLE_MAP = Map.of(
            "customer", "CUSTOMER",
            "sales_agent", "SALES_AGENT",
            "support_agent", "SUPPORT_AGENT",
            "supervisor", "SUPERVISOR",
            "admin", "ADMIN"
    );

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final GoogleOAuthService googleOAuthService;
    private final MicrosoftOAuthService microsoftOAuthService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       EmailService emailService,
                       GoogleOAuthService googleOAuthService,
                       MicrosoftOAuthService microsoftOAuthService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.googleOAuthService = googleOAuthService;
        this.microsoftOAuthService = microsoftOAuthService;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already registered");
        }

        User user = new User();
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setCompanyName(request.getCompanyName());
        user.setCustomerType(request.getCustomerType());
        user.setRole("CUSTOMER");
        user.setAuthProvider("LOCAL");
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmailVerified(false);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        String verificationToken = UUID.randomUUID().toString();
        user.setVerificationToken(verificationToken);
        user.setVerificationTokenExpiryDate(LocalDateTime.now().plusHours(24));

        User savedUser = userRepository.save(user);

        try {
            emailService.sendVerificationEmail(savedUser.getEmail(), verificationToken);
        } catch (Exception e) {
            System.err.println("Failed to send verification email: " + e.getMessage());
        }

        return toAuthResponse(savedUser, "temp-token");
    }

    public AuthResponse login(String email, String password, String role) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        if (!"LOCAL".equalsIgnoreCase(user.getAuthProvider())) {
            throw new RuntimeException("Please sign in with " + formatProvider(user.getAuthProvider()));
        }

        if (user.getPassword() == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("Invalid email or password");
        }

        validateRole(user, role);
        return toAuthResponse(user, "temp-token");
    }

    public AuthResponse googleLogin(String idToken, String role) {
        OAuthUserInfo userInfo = googleOAuthService.verifyToken(idToken);
        return loginOrRegisterOAuthUser(userInfo, role);
    }

    public AuthResponse microsoftLogin(String accessToken, String role) {
        OAuthUserInfo userInfo = microsoftOAuthService.verifyAccessToken(accessToken);
        return loginOrRegisterOAuthUser(userInfo, role);
    }

    public String verifyEmail(String token) {
        User user = userRepository.findByVerificationToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired verification link"));

        if (user.getVerificationTokenExpiryDate().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Verification link has expired. Please request a new one.");
        }

        if (user.isEmailVerified()) {
            return "Email already verified";
        }

        user.setEmailVerified(true);
        user.setVerificationToken(null);
        user.setVerificationTokenExpiryDate(null);
        user.setEmailVerifiedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        return "Email verified successfully";
    }

    public boolean isEmailVerified(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.isEmailVerified();
    }

    public void resendVerificationEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isEmailVerified()) {
            throw new RuntimeException("Email already verified");
        }

        String newToken = UUID.randomUUID().toString();
        user.setVerificationToken(newToken);
        user.setVerificationTokenExpiryDate(LocalDateTime.now().plusHours(24));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        try {
            emailService.sendVerificationEmail(user.getEmail(), newToken);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send verification email: " + e.getMessage());
        }
    }

    private AuthResponse loginOrRegisterOAuthUser(OAuthUserInfo userInfo, String role) {
        User user = userRepository.findByAuthProviderAndProviderId(userInfo.getProvider(), userInfo.getProviderId())
                .orElseGet(() -> userRepository.findByEmail(userInfo.getEmail()).orElse(null));

        if (user == null) {
            user = new User();
            user.setFullName(userInfo.getFullName());
            user.setEmail(userInfo.getEmail());
            user.setAuthProvider(userInfo.getProvider());
            user.setProviderId(userInfo.getProviderId());
            user.setRole(mapRole(role, "CUSTOMER"));
            user.setCustomerType("INDIVIDUAL");
            user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(LocalDateTime.now());
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            user = userRepository.save(user);
        } else {
            if (!userInfo.getProvider().equalsIgnoreCase(user.getAuthProvider())
                    && !"LOCAL".equalsIgnoreCase(user.getAuthProvider())) {
                throw new RuntimeException("This email is linked to a different sign-in method");
            }

            user.setAuthProvider(userInfo.getProvider());
            user.setProviderId(userInfo.getProviderId());
            user.setFullName(userInfo.getFullName());
            user.setEmailVerified(true);
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
            validateRole(user, role);
        }

        return toAuthResponse(user, "oauth-token");
    }

    private void validateRole(User user, String requestedRole) {
        String mappedRole = mapRole(requestedRole, user.getRole());
        if (!mappedRole.equalsIgnoreCase(user.getRole())) {
            throw new RuntimeException("You do not have access for the selected role");
        }
    }

    private String mapRole(String role, String fallback) {
        if (role == null || role.isBlank()) {
            return fallback;
        }
        return ROLE_MAP.getOrDefault(role.toLowerCase(), fallback);
    }

    private String formatProvider(String provider) {
        if (provider == null) {
            return "your provider";
        }
        return provider.charAt(0) + provider.substring(1).toLowerCase();
    }

    private AuthResponse toAuthResponse(User user, String token) {
        return new AuthResponse(
                token,
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.isEmailVerified(),
                user.isMfaEnabled(),
                user.getPhone()
        );
    }
}
