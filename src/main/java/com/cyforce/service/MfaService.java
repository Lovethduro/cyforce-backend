package com.cyforce.service;

import com.cyforce.dto.MfaSetupInitResponse;
import com.cyforce.model.User;
import com.cyforce.repository.UserRepository;
import dev.samstevens.totp.code.*;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;

@Service
public class MfaService {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final SmsService smsService;
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final TimeProvider timeProvider = new SystemTimeProvider();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final CodeVerifier codeVerifier = new DefaultCodeVerifier(codeGenerator, timeProvider);

    public MfaService(UserRepository userRepository, EmailService emailService, SmsService smsService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.smsService = smsService;
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

    public void verifySetup(String userId, String code) {
        User user = getVerifiedUser(userId);

        if (user.getMfaPendingMethod() == null) {
            throw new RuntimeException("MFA setup has not been started. Please begin setup again.");
        }

        boolean valid = switch (user.getMfaPendingMethod()) {
            case "authenticator" -> verifyAuthenticatorCode(user.getMfaPendingSecret(), code);
            case "email", "sms" -> verifyEmailCode(user, code);
            default -> throw new RuntimeException("Unsupported MFA method");
        };

        if (!valid) {
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

    private MfaSetupInitResponse initAuthenticatorSetup(User user) {
        String secret = secretGenerator.generate();
        user.setMfaPendingSecret(secret);
        user.setMfaPendingMethod("authenticator");
        user.setMfaPendingCode(null);
        user.setMfaPendingCodeExpiry(null);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        String otpAuthUrl = buildOtpAuthUrl(user.getEmail(), secret);

        return new MfaSetupInitResponse(
                "authenticator",
                secret,
                otpAuthUrl,
                "Scan the QR code with your authenticator app"
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
        if (secret == null || code == null || code.length() != 6) {
            return false;
        }
        return codeVerifier.isValidCode(secret, code);
    }

    private boolean verifyEmailCode(User user, String code) {
        if (user.getMfaPendingCode() == null || user.getMfaPendingCodeExpiry() == null) {
            return false;
        }
        if (user.getMfaPendingCodeExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Verification code has expired. Please request a new one.");
        }
        return user.getMfaPendingCode().equals(code);
    }

    private User getVerifiedUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!user.isEmailVerified()) {
            throw new RuntimeException("Please verify your email before setting up MFA");
        }

        return user;
    }

    private String buildOtpAuthUrl(String email, String secret) {
        QrData data = new QrData.Builder()
                .label(email)
                .secret(secret)
                .issuer("CyForce")
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();

        return data.getUri();
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
