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
    private final SmsService smsService;
    private final GoogleAuthenticator googleAuthenticator;

    public MfaService(UserRepository userRepository, EmailService emailService, SmsService smsService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.smsService = smsService;
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

    public MfaSetupInitResponse initSetup(String userId, String method) {
        User user = getVerifiedUser(userId);

        if (user.isMfaEnabled()) {
            throw new RuntimeException("MFA is already enabled for this account");
        }

        return switch (method) {
            case "authenticator" -> initAuthenticatorSetup(user);
            case "email" -> initEmailSetup(user);
            case "sms" -> initSmsSetup(user);
            default -> throw new RuntimeException("Unsupported MFA method");
        };
    }

    public void verifySetup(String userId, String code, String clientSecret) {
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
            case "email", "sms" -> verifyEmailCode(user, code);
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
    }

    public String beginLoginChallenge(User user) {
        if (!user.isMfaEnabled()) {
            throw new RuntimeException("MFA is not enabled for this account");
        }

        String token = UUID.randomUUID().toString();
        user.setMfaLoginToken(token);
        user.setMfaLoginTokenExpiry(LocalDateTime.now().plusMinutes(10));
        user.setUpdatedAt(LocalDateTime.now());

        String method = user.getMfaMethod() == null ? "authenticator" : user.getMfaMethod();
        if ("email".equals(method) || "sms".equals(method)) {
            String code = String.format("%06d", new Random().nextInt(1_000_000));
            user.setMfaLoginCode(code);
            user.setMfaLoginCodeExpiry(LocalDateTime.now().plusMinutes(10));
            if ("sms".equals(method)) {
                smsService.sendMfaCode(user.getPhone(), code);
            } else {
                emailService.sendMfaSetupCode(user.getEmail(), code);
            }
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

        String method = user.getMfaMethod() == null ? "authenticator" : user.getMfaMethod();
        boolean valid = switch (method) {
            case "authenticator" -> verifyAuthenticatorCode(user.getTotpSecret(), code);
            case "email", "sms" -> verifyLoginCode(user, code);
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

        String method = user.getMfaMethod();
        if (!"email".equals(method) && !"sms".equals(method)) {
            throw new RuntimeException("Resend is only available for email or SMS MFA");
        }

        String code = String.format("%06d", new Random().nextInt(1_000_000));
        user.setMfaLoginCode(code);
        user.setMfaLoginCodeExpiry(LocalDateTime.now().plusMinutes(10));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        if ("sms".equals(method)) {
            smsService.sendMfaCode(user.getPhone(), code);
        } else {
            emailService.sendMfaSetupCode(user.getEmail(), code);
        }
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

    private MfaSetupInitResponse initSmsSetup(User user) {
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw new RuntimeException("No phone number on your account. Please register with a valid phone number.");
        }

        String code = String.format("%06d", new Random().nextInt(1_000_000));

        user.setMfaPendingMethod("sms");
        user.setMfaPendingSecret(null);
        user.setMfaPendingCode(code);
        user.setMfaPendingCodeExpiry(LocalDateTime.now().plusMinutes(10));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        String smsMessage = smsService.sendMfaCode(user.getPhone(), code);
        String responseMessage = smsMessage != null
                ? smsMessage
                : "A verification code has been sent to " + maskPhone(user.getPhone());

        return new MfaSetupInitResponse(
                "sms",
                null,
                null,
                responseMessage
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

    private String maskPhone(String phone) {
        String digits = phone.replaceAll("\\s+", "");
        if (digits.length() <= 4) {
            return digits;
        }
        return "***" + digits.substring(digits.length() - 4);
    }

    private void clearPendingMfa(User user) {
        user.setMfaPendingSecret(null);
        user.setMfaPendingMethod(null);
        user.setMfaPendingCode(null);
        user.setMfaPendingCodeExpiry(null);
    }
}
