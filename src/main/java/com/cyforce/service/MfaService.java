package com.cyforce.service;

import com.cyforce.dto.MfaSetupInitResponse;
import com.cyforce.model.User;
import com.cyforce.repository.UserRepository;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorConfig;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import com.warrenstrange.googleauth.GoogleAuthenticatorQRGenerator;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MfaService {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordService passwordService;
    private final SecurityEventService securityEventService;
    private final GoogleAuthenticator googleAuthenticator;

    public MfaService(UserRepository userRepository,
                      EmailService emailService,
                      PasswordService passwordService,
                      SecurityEventService securityEventService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.passwordService = passwordService;
        this.securityEventService = securityEventService;
        GoogleAuthenticatorConfig config = new GoogleAuthenticatorConfig.GoogleAuthenticatorConfigBuilder()
                .setTimeStepSizeInMillis(TimeUnit.SECONDS.toMillis(30))
                .setWindowSize(10)
                .setCodeDigits(6)
                .build();
        this.googleAuthenticator = new GoogleAuthenticator(config);
    }

    public void resetSetup(String userId) {
        User user = getVerifiedUser(userId);
        clearPendingMfa(user);
        user.setTotpSecret(null);
        user.setMfaEnabled(false);
        user.setMfaMethod(null);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    public void disableMfa(String userId, String password) {
        disableMfa(userId, password, null, null);
    }

    public void disableMfa(String userId, String password, String code, String clientIp) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!user.isMfaEnabled()) {
            throw new RuntimeException("MFA is not enabled for this account");
        }

        if (isOAuthUser(user)) {
            verifyOAuthDisable(user, code);
        } else {
            if (password == null || password.isBlank()) {
                throw new RuntimeException("Password is required to disable MFA");
            }
            if (!passwordService.matchesRaw(password, user.getPassword())) {
                throw new RuntimeException("Incorrect password");
            }
        }

        user.setMfaEnabled(false);
        user.setMfaMethod(null);
        user.setTotpSecret(null);
        clearPendingMfa(user);
        clearLoginChallenge(user);
        clearDisableChallenge(user);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        securityEventService.recordMfaDisabled(user, clientIp);
    }

    public void prepareDisableMfa(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!user.isMfaEnabled()) {
            throw new RuntimeException("MFA is not enabled for this account");
        }
        if (!isOAuthUser(user)) {
            throw new RuntimeException("Password confirmation is used for local accounts");
        }
        String method = normalizeMfaMethod(user.getMfaMethod());
        if (!"email".equals(method)) {
            return;
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new RuntimeException("No email on file to send a verification code");
        }
        String disableCode = String.format("%06d", new Random().nextInt(1_000_000));
        user.setMfaDisableCode(disableCode);
        user.setMfaDisableCodeExpiry(LocalDateTime.now().plusMinutes(10));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        emailService.sendMfaSetupCode(user.getEmail(), disableCode);
    }

    private void verifyOAuthDisable(User user, String code) {
        if (code == null || code.isBlank()) {
            throw new RuntimeException("Enter your MFA verification code to disable");
        }
        String method = normalizeMfaMethod(user.getMfaMethod());
        boolean valid = switch (method) {
            case "authenticator" -> verifyAuthenticatorCode(user.getTotpSecret(), code);
            case "email" -> verifyDisableEmailCode(user, code);
            default -> false;
        };
        if (!valid) {
            throw new RuntimeException("Invalid verification code");
        }
    }

    private boolean verifyDisableEmailCode(User user, String code) {
        if (user.getMfaDisableCode() == null || user.getMfaDisableCodeExpiry() == null) {
            throw new RuntimeException("Request a verification code first");
        }
        if (user.getMfaDisableCodeExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Verification code has expired. Request a new one.");
        }
        String normalizedCode = code.replaceAll("\\D", "");
        return user.getMfaDisableCode().equals(normalizedCode);
    }

    private void clearDisableChallenge(User user) {
        user.setMfaDisableCode(null);
        user.setMfaDisableCodeExpiry(null);
    }

    private boolean isOAuthUser(User user) {
        String provider = user.getAuthProvider();
        return provider != null && !provider.equalsIgnoreCase("LOCAL");
    }

    public MfaSetupInitResponse initSetup(String userId, String method) {
        return initSetup(userId, method, false);
    }

    public MfaSetupInitResponse initSetup(String userId, String method, boolean reconfigure) {
        User user = getVerifiedUser(userId);

        if (user.isMfaEnabled()) {
            if (!reconfigure) {
                throw new RuntimeException("MFA is already enabled for this account");
            }
            clearPendingMfa(user);
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
        }

        return switch (method) {
            case "authenticator" -> initAuthenticatorSetup(user);
            case "email" -> initEmailSetup(user);
            default -> throw new RuntimeException("Unsupported MFA method. Choose authenticator or email.");
        };
    }

    public void verifySetup(String userId, String code, String clientSecret) {
        verifySetup(userId, code, clientSecret, null);
    }

    public void verifySetup(String userId, String code, String clientSecret, String clientIp) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.isEmailVerified()) {
            throw new RuntimeException("Please verify your email before setting up MFA");
        }

        if (user.getMfaPendingMethod() == null) {
            throw new RuntimeException("MFA setup has not been started. Please begin setup again.");
        }

        boolean valid = switch (user.getMfaPendingMethod()) {
            case "authenticator" -> verifyAuthenticatorSetup(user, code, clientSecret);
            case "email" -> verifyEmailCode(user, code);
            default -> throw new RuntimeException("Unsupported MFA method");
        };

        if (!valid) {
            if ("authenticator".equals(user.getMfaPendingMethod())) {
                throw new RuntimeException("Invalid verification code. Use the latest code from your authenticator app, or restart setup and scan the QR code again.");
            }
            throw new RuntimeException("Invalid verification code");
        }

        user.setMfaEnabled(true);
        user.setMfaMethod(user.getMfaPendingMethod());

        if ("authenticator".equals(user.getMfaPendingMethod())) {
            user.setTotpSecret(user.getMfaPendingSecret());
        }

        clearPendingMfa(user);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        securityEventService.recordMfaEnabled(user, user.getMfaMethod(), clientIp);
    }

    public String beginLoginChallenge(User user) {
        if (!user.isMfaEnabled()) {
            throw new RuntimeException("MFA is not enabled for this account");
        }

        String token = UUID.randomUUID().toString();
        user.setMfaLoginToken(token);
        user.setMfaLoginTokenExpiry(LocalDateTime.now().plusMinutes(10));
        user.setUpdatedAt(LocalDateTime.now());

        String method = normalizeMfaMethod(user.getMfaMethod());
        if ("sms".equals(user.getMfaMethod())) {
            user.setMfaMethod("email");
        }
        if ("email".equals(method)) {
            String code = String.format("%06d", new Random().nextInt(1_000_000));
            user.setMfaLoginCode(code);
            user.setMfaLoginCodeExpiry(LocalDateTime.now().plusMinutes(10));
            emailService.sendMfaSetupCode(user.getEmail(), code);
        } else {
            user.setMfaLoginCode(null);
            user.setMfaLoginCodeExpiry(null);
        }

        userRepository.save(user);
        return token;
    }

    public User verifyLoginChallenge(String challengeToken, String code) {
        if (challengeToken == null || challengeToken.isBlank()) {
            throw new RuntimeException("MFA challenge token is required");
        }

        User user = userRepository.findAll().stream()
                .filter(u -> challengeToken.equals(u.getMfaLoginToken()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Invalid or expired MFA session. Please sign in again."));

        if (user.getMfaLoginTokenExpiry() == null || user.getMfaLoginTokenExpiry().isBefore(LocalDateTime.now())) {
            clearLoginChallenge(user);
            userRepository.save(user);
            throw new RuntimeException("MFA session expired. Please sign in again.");
        }

        String method = normalizeMfaMethod(user.getMfaMethod());
        boolean valid = switch (method) {
            case "authenticator" -> verifyAuthenticatorCode(user.getTotpSecret(), code);
            case "email" -> verifyLoginCode(user, code);
            default -> false;
        };

        if (!valid) {
            throw new RuntimeException("Invalid verification code");
        }

        clearLoginChallenge(user);
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    public void resendLoginChallenge(String challengeToken) {
        User user = userRepository.findAll().stream()
                .filter(u -> challengeToken.equals(u.getMfaLoginToken()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Invalid MFA session"));

        if (user.getMfaLoginTokenExpiry() == null || user.getMfaLoginTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("MFA session expired. Please sign in again.");
        }

        String method = normalizeMfaMethod(user.getMfaMethod());
        if ("sms".equals(user.getMfaMethod())) {
            user.setMfaMethod("email");
        }
        if (!"email".equals(method)) {
            throw new RuntimeException("Resend is only available for email MFA");
        }

        String code = String.format("%06d", new Random().nextInt(1_000_000));
        user.setMfaLoginCode(code);
        user.setMfaLoginCodeExpiry(LocalDateTime.now().plusMinutes(10));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        emailService.sendMfaSetupCode(user.getEmail(), code);
    }

    private String normalizeMfaMethod(String method) {
        if (method == null || method.isBlank()) {
            return "authenticator";
        }
        if ("sms".equals(method)) {
            return "email";
        }
        return method;
    }

    private boolean verifyLoginCode(User user, String code) {
        if (user.getMfaLoginCode() == null || user.getMfaLoginCodeExpiry() == null) {
            return false;
        }
        if (user.getMfaLoginCodeExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Verification code has expired. Please sign in again.");
        }
        String normalizedCode = code == null ? "" : code.replaceAll("\\D", "");
        return user.getMfaLoginCode().equals(normalizedCode);
    }

    private void clearLoginChallenge(User user) {
        user.setMfaLoginToken(null);
        user.setMfaLoginTokenExpiry(null);
        user.setMfaLoginCode(null);
        user.setMfaLoginCodeExpiry(null);
    }

    private boolean verifyAuthenticatorSetup(User user, String code, String clientSecret) {
        String secret = resolveAuthenticatorSecret(user, clientSecret);
        if (secret == null || secret.isBlank()) {
            return false;
        }

        secret = normalizeSecret(secret);
        if (!secret.equals(normalizeSecret(user.getMfaPendingSecret()))) {
            user.setMfaPendingSecret(secret);
            user.setMfaPendingMethod("authenticator");
            userRepository.save(user);
        }

        return verifyAuthenticatorCode(secret, code);
    }

    private String resolveAuthenticatorSecret(User user, String clientSecret) {
        if (clientSecret != null && !clientSecret.isBlank()) {
            return clientSecret;
        }
        return user.getMfaPendingSecret();
    }

    private MfaSetupInitResponse initAuthenticatorSetup(User user) {
        GoogleAuthenticatorKey credentials = googleAuthenticator.createCredentials();
        String secret = credentials.getKey();

        user.setTotpSecret(null);
        user.setMfaPendingSecret(secret);
        user.setMfaPendingMethod("authenticator");
        user.setMfaPendingCode(null);
        user.setMfaPendingCodeExpiry(null);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        String accountName = user.getEmail() == null || user.getEmail().isBlank()
                ? "user"
                : user.getEmail().trim().toLowerCase();
        String otpAuthUrl = GoogleAuthenticatorQRGenerator.getOtpAuthURL("CyForce", accountName, credentials);

        return new MfaSetupInitResponse(
                "authenticator",
                secret,
                otpAuthUrl,
                "Scan with iPhone/iPad, or enter the setup key manually in Google Authenticator (laptop QR codes often fail)"
        );
    }

    private MfaSetupInitResponse initEmailSetup(User user) {
        String code = String.format("%06d", new Random().nextInt(1_000_000));

        user.setMfaPendingMethod("email");
        user.setMfaPendingSecret(null);
        user.setMfaPendingCode(code);
        user.setMfaPendingCodeExpiry(LocalDateTime.now().plusMinutes(10));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        emailService.sendMfaSetupCode(user.getEmail(), code);

        return new MfaSetupInitResponse(
                "email",
                null,
                null,
                "A verification code has been sent to " + user.getEmail()
        );
    }

    private boolean verifyAuthenticatorCode(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }

        String normalizedCode = code.replaceAll("\\D", "");
        if (normalizedCode.length() != 6) {
            return false;
        }

        try {
            int codeValue = Integer.parseInt(normalizedCode);
            return googleAuthenticator.authorize(normalizeSecret(secret), codeValue);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String normalizeSecret(String secret) {
        return secret.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private boolean verifyEmailCode(User user, String code) {
        if (user.getMfaPendingCode() == null || user.getMfaPendingCodeExpiry() == null) {
            return false;
        }
        if (user.getMfaPendingCodeExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Verification code has expired. Please request a new one.");
        }
        String normalizedCode = code == null ? "" : code.replaceAll("\\D", "");
        return user.getMfaPendingCode().equals(normalizedCode);
    }

    private User getVerifiedUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.isEmailVerified()) {
            throw new RuntimeException("Please verify your email before setting up MFA");
        }

        return user;
    }

    private void clearPendingMfa(User user) {
        user.setMfaPendingSecret(null);
        user.setMfaPendingMethod(null);
        user.setMfaPendingCode(null);
        user.setMfaPendingCodeExpiry(null);
    }
}
