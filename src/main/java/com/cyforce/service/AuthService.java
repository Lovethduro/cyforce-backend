package com.cyforce.service;

import com.cyforce.dto.AuthResponse;
import com.cyforce.dto.OAuthUserInfo;
import com.cyforce.dto.RegisterRequest;
import com.cyforce.model.User;
import com.cyforce.repository.UserRepository;
import com.cyforce.util.NameUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    /** Set to true to require MFA verification after password/OAuth login. */
    private static final boolean LOGIN_MFA_ENABLED = false;

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
    private final MfaService mfaService;
    private final MongoTemplate mongoTemplate;

    public AuthService(UserRepository userRepository,
                       PasswordService passwordService,
                       EmailService emailService,
                       GoogleOAuthService googleOAuthService,
                       MicrosoftOAuthService microsoftOAuthService,
                       SecurityEventService securityEventService,
                       MfaService mfaService,
                       MongoTemplate mongoTemplate) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.emailService = emailService;
        this.googleOAuthService = googleOAuthService;
        this.microsoftOAuthService = microsoftOAuthService;
        this.securityEventService = securityEventService;
        this.mfaService = mfaService;
        this.mongoTemplate = mongoTemplate;
    }

    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("Email already registered");
        }

        User user = new User();
        user.setFullName(NameUtils.capitalizeWords(request.getFullName()));
        user.setEmail(email);
        user.setPhone(request.getPhone());
        user.setCompanyName(NameUtils.capitalizeWords(request.getCompanyName()));
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

        recordActivity(user);
        userRepository.save(user);

        if (LOGIN_MFA_ENABLED && user.isMfaEnabled()) {
            String challengeToken = mfaService.beginLoginChallenge(user);
            return toMfaChallengeResponse(user, challengeToken);
        }

        return toAuthResponse(user, "temp-token");
    }

    public AuthResponse verifyMfaLogin(String challengeToken, String code) {
        User user = mfaService.verifyLoginChallenge(challengeToken, code);
        recordActivity(user);
        userRepository.save(user);
        return toAuthResponse(user, "temp-token");
    }

    public void resendMfaLoginCode(String challengeToken) {
        mfaService.resendLoginChallenge(challengeToken);
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
            if (!user.isActive()) {
                throw new RuntimeException("Your account has been deactivated. Contact an administrator.");
            }

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
        }

        recordActivity(user);
        userRepository.save(user);

        if (LOGIN_MFA_ENABLED && user.isMfaEnabled()) {
            String challengeToken = mfaService.beginLoginChallenge(user);
            return toMfaChallengeResponse(user, challengeToken);
        }

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

    private void recordActivity(User user) {
        LocalDateTime now = LocalDateTime.now();
        user.setLastLoginAt(now);
        user.setLastActivityAt(now);
        user.setUpdatedAt(now);
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
                memberSince,
                user.isMustChangePassword(),
                user.wantsMotivationalMessages(),
                false,
                null,
                user.getMfaMethod()
        );
    }

    private AuthResponse toMfaChallengeResponse(User user, String challengeToken) {
        String profileImage = user.getAvatarUrl() != null ? user.getAvatarUrl() : user.getLogoUrl();
        String memberSince = user.getCreatedAt() == null ? null
                : user.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"));
        return new AuthResponse(
                null,
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.isEmailVerified(),
                user.isMfaEnabled(),
                user.getPhone(),
                profileImage,
                user.getPreferredPaymentMethod(),
                memberSince,
                user.isMustChangePassword(),
                user.wantsMotivationalMessages(),
                true,
                challengeToken,
                user.getMfaMethod()
        );
    }

    public Map<String, Object> forgotPassword(String email) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "If an account exists for that email, a password reset link has been sent.");

        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isBlank()) {
            return response;
        }

        userRepository.findByEmailIgnoreCase(normalizedEmail).ifPresent(user -> {
            if (!user.isActive() || !"LOCAL".equalsIgnoreCase(user.getAuthProvider())) {
                return;
            }
            String token = UUID.randomUUID().toString();
            user.setPasswordResetToken(token);
            user.setPasswordResetTokenExpiry(LocalDateTime.now().plusHours(1));
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);

            boolean emailSent = false;
            try {
                emailService.sendPasswordResetEmail(user.getEmail(), token);
                emailSent = true;
            } catch (Exception e) {
                System.err.println("Failed to send password reset email: " + e.getMessage());
            }

            String resetUrl = buildResetPasswordUrl(token);
            if (!emailSent) {
                response.put("message", "We could not send the reset email. Use the link below to choose a new password.");
            }
            response.put("resetUrl", resetUrl);
        });
        return response;
    }

    public void resetPassword(String token, String newPassword) {
        validateNewPassword(newPassword);
        String normalizedToken = normalizeResetToken(token);
        User user = userRepository.findByPasswordResetToken(normalizedToken)
                .orElseThrow(() -> new RuntimeException("Invalid or expired reset link"));

        if (user.getPasswordResetTokenExpiry() == null
                || user.getPasswordResetTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Reset link has expired. Please request a new one.");
        }

        String encoded = passwordService.encode(newPassword);
        Query query = Query.query(Criteria.where("_id").is(user.getId()));
        Update update = new Update()
                .set("password", encoded)
                .unset("passwordResetToken")
                .unset("passwordResetTokenExpiry")
                .set("mustChangePassword", false)
                .set("updatedAt", LocalDateTime.now());
        mongoTemplate.updateFirst(query, update, User.class);

        User verified = userRepository.findById(user.getId())
                .orElseThrow(() -> new RuntimeException("User not found after password reset"));
        if (!passwordService.matchesRaw(newPassword, verified.getPassword())) {
            throw new RuntimeException("Password could not be saved. Please try again.");
        }
    }

    private String normalizeResetToken(String token) {
        if (token == null) {
            return "";
        }
        String trimmed = token.trim();
        try {
            return URLDecoder.decode(trimmed, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
    }

    private String buildResetPasswordUrl(String token) {
        return "http://localhost:3000/reset-password?token=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    public void changePassword(String userId, String currentPassword, String newPassword) {
        validateNewPassword(newPassword);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!"LOCAL".equalsIgnoreCase(user.getAuthProvider())) {
            throw new RuntimeException("Password cannot be changed for accounts that use social sign-in");
        }

        if (user.getPassword() == null || !passwordService.matchesRaw(currentPassword, user.getPassword())) {
            throw new RuntimeException("Current password is incorrect");
        }

        if (passwordService.matchesRaw(newPassword, user.getPassword())) {
            throw new RuntimeException("New password must be different from your current password");
        }

        user.setPassword(passwordService.encode(newPassword));
        user.setMustChangePassword(false);
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenExpiry(null);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    private void validateNewPassword(String password) {
        if (password == null || password.length() < 8) {
            throw new RuntimeException("Password must be at least 8 characters");
        }
    }
}
