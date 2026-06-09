package com.cyforce.config;

import com.cyforce.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class StaffBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffBootstrap.class);

    private final UserService userService;

    @Value("${app.bootstrap-staff-password:}")
    private String bootstrapStaffPassword;

    public StaffBootstrap(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapStaffPassword == null || bootstrapStaffPassword.isBlank()) {
            return;
        }

        record StaffAccount(String email, String fullName, String role) {}

        StaffAccount[] accounts = {
                new StaffAccount("bethel.rufus@elizadeuniversity.edu.ng", "Bethel Rufus", "SUPERVISOR"),
                new StaffAccount("groupfcmp@gmail.com", "Group FCMP", "SALES_AGENT"),
                new StaffAccount("practicemp1234@gmail.com", "Practice MP", "SUPPORT_AGENT"),
        };

        for (StaffAccount account : accounts) {
            try {
                userService.ensureStaffUser(
                        account.email(),
                        account.fullName(),
                        account.role(),
                        bootstrapStaffPassword
                );
            } catch (Exception e) {
                log.error("Failed to bootstrap {} ({}): {}", account.email(), account.role(), e.getMessage());
            }
        }
    }
}
