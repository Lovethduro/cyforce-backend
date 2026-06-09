package com.cyforce.config;

import com.cyforce.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserService userService;

    @Value("${app.bootstrap-admin-email:}")
    private String bootstrapAdminEmail;

    @Value("${app.bootstrap-admin-name:CyForce Admin}")
    private String bootstrapAdminName;

    @Value("${app.bootstrap-admin-password:}")
    private String bootstrapAdminPassword;

    public AdminBootstrap(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapAdminEmail == null || bootstrapAdminEmail.isBlank()) {
            return;
        }

        try {
            userService.ensureAdminUser(
                    bootstrapAdminEmail.trim(),
                    bootstrapAdminName,
                    bootstrapAdminPassword
            );
        } catch (Exception e) {
            log.error("Failed to bootstrap admin user {}: {}", bootstrapAdminEmail, e.getMessage());
        }
    }
}
