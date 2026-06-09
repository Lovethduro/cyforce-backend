package com.cyforce.service;

import com.cyforce.dto.AuthResponse;
import com.cyforce.dto.OAuthUserInfo;
import com.cyforce.dto.RegisterRequest;
import com.cyforce.model.User;
import com.cyforce.repository.UserRepository;
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
    private final PasswordService passwordService;
    private final EmailService emailService;
    private final GoogleOAuthService googleOAuthService;
    private final MicrosoftOAuthService microsoftOAuthService;
    private final SecurityEventService securityEventService;

    public AuthService(UserRepository userRepository,
                       PasswordService passwordService,
                       EmailService emailService,
                       GoogleOAuthService googleOAuthService,
                       MicrosoftOAuthService microsoftOAuthService,
                       SecurityEventService securityEventService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.emailService = emailService;
        this.googleOAuthService = googleOAuthService;
        this.microsoftOAuthService = microsoftOAuthService;
        this.securityEventService = securityEventService;
    }

    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("Email already registered");
        }

        User user = new User();
        user.setFullName(request.getFullName());
        user.setEmail(email);
        user.setPhone(request.getPhone());
        user.setCompanyName(request.getCompanyName());
        user.setCustomerType(request.getCustomerType());
        user.setRole("CUSTOMER");
        user.setAuthProvider("LOCAL");
        user.setPassword(passwordService.encode(request.getPassword()));
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
        String normalizedEmail = normalizeEmail(email);
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        if (user == null) {
            securityEventService.recordLoginFailure(normalizedEmail, "Unknown email");
            throw new RuntimeException("Invalid email or password");
        }

        if (!user.isActive()) {
            securityEventService.recordLoginFailure(user.getEmail(), "Deactivated account");
            throw new RuntimeException("Your account has been deactivated. Contact an administrator.");
        }

        if (!"LOCAL".equalsIgnoreCase(user.getAuthProvider())) {
            securityEventService.recordLoginFailure(user.getEmail(), "Wrong auth provider: " + user.getAuthProvider());
            throw new RuntimeException("Please sign in with " + formatProvider(user.getAuthProvider()));
        }

        if (user.getPassword() == null || !passwordService.matchesRaw(password, user.getPassword())) {
            securityEventService.recordLoginFailure(user.getEmail(), "Invalid password");
            throw new RuntimeException("Invalid email or password");
        }

        if (passwordService.needsRehash(user.getPassword())) {
            user.setPassword(passwordService.encode(password));
        }

        try {
            validateRole(user, role);
        } catch (RuntimeException e) {
            securityEventService.recordRoleMismatch(user.getEmail(), role, user.getRole());
            throw e;
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
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
        User user = userRepository.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.isEmailVerified();
    }

    public void resendVerificationEmail(String email) {
        User user = userRepository.findByEmailIgnoreCase(normalizeEmail(email))
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
                .orElseGet(() -> userRepository.findByEmailIgnoreCase(normalizeEmail(userInfo.getEmail())).orElse(null));

        if (user == null) {
            user = new User();
            user.setFullName(userInfo.getFullName());
            user.setEmail(userInfo.getEmail());
            user.setAuthProvider(userInfo.getProvider());
            user.setProviderId(userInfo.getProviderId());
            user.setRole(mapRole(role, "CUSTOMER"));
            user.setCustomerType("INDIVIDUAL");
            user.setPassword(passwordService.encode(UUID.randomUUID().toString()));
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

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        return toAuthResponse(user, "oauth-token");
    }

    private void validateRole(User user, String requestedRole) {
        String mappedRole = mapRole(requestedRole, user.getRole());
        if (user.getRole() == null || !mappedRole.equalsIgnoreCase(user.getRole())) {
            throw new RuntimeException(
                    "Wrong role selected. Your account is registered as "
                            + formatRoleLabel(user.getRole())
                            + ". Please choose that role on the login screen."
            );
        }
    }

    private String formatRoleLabel(String role) {
        if (role == null || role.isBlank()) {
            return "Unknown";
        }
        return role.replace('_', ' ').toLowerCase();
    }

    private String mapRole(String role, String fallback) {
        if (role == null || role.isBlank()) {
            return fallback;
        }
        return ROLE_MAP.getOrDefault(role.toLowerCase(), fallback);
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        return email.trim().toLowerCase();
    }

    private String formatProvider(String provider) {
        if (provider == null) {
            return "your provider";
        }
        return provider.charAt(0) + provider.substring(1).toLowerCase();
    }

    private AuthResponse toAuthResponse(User user, String token) {
        String profileImage = user.getAvatarUrl() != null ? user.getAvatarUrl() : user.getLogoUrl();
        String memberSince = user.getCreatedAt() == null ? null
                : user.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"));
        return new AuthResponse(
                token,
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.isEmailVerified(),
                user.isMfaEnabled(),
                user.getPhone(),
                profileImage,
                user.getPreferredPaymentMethod(),
                memberSince
        );
    }
}
