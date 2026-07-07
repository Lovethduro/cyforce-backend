package com.cyforce.service;

import com.cyforce.model.User;
import com.cyforce.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CustomerReengagementService {

    private static final Logger log = LoggerFactory.getLogger(CustomerReengagementService.class);
    private static final int INACTIVITY_DAYS = 7;
    private static final int EMAIL_COOLDOWN_DAYS = 7;

    private final UserRepository userRepository;
    private final EmailService emailService;

    public CustomerReengagementService(UserRepository userRepository, EmailService emailService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    @Scheduled(cron = "0 0 9 * * *")
    public void sendWeeklyReengagementEmails() {
        LocalDateTime inactivityCutoff = LocalDateTime.now().minusDays(INACTIVITY_DAYS);
        LocalDateTime emailCooldownCutoff = LocalDateTime.now().minusDays(EMAIL_COOLDOWN_DAYS);

        List<User> customers = userRepository.findAll().stream()
                .filter(u -> "CUSTOMER".equalsIgnoreCase(u.getRole()))
                .filter(User::isActive)
                .filter(User::isEmailVerified)
                .toList();

        int sent = 0;
        for (User customer : customers) {
            LocalDateTime lastActive = resolveLastActivity(customer);
            if (lastActive == null || !lastActive.isBefore(inactivityCutoff)) {
                continue;
            }

            if (customer.getLastReengagementEmailAt() != null
                    && customer.getLastReengagementEmailAt().isAfter(emailCooldownCutoff)) {
                continue;
            }

            try {
                emailService.sendCustomerReengagementEmail(customer.getEmail(), customer.getFullName());
                customer.setLastReengagementEmailAt(LocalDateTime.now());
                customer.setUpdatedAt(LocalDateTime.now());
                userRepository.save(customer);
                sent++;
            } catch (Exception e) {
                log.warn("Failed to send re-engagement email to {}: {}", customer.getEmail(), e.getMessage());
            }
        }

        if (sent > 0) {
            log.info("Sent {} customer re-engagement email(s)", sent);
        }
    }

    private LocalDateTime resolveLastActivity(User user) {
        if (user.getLastActivityAt() != null) {
            return user.getLastActivityAt();
        }
        if (user.getLastLoginAt() != null) {
            return user.getLastLoginAt();
        }
        return user.getCreatedAt();
    }
}
