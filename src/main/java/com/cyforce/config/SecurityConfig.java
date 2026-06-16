package com.cyforce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/**",
                                "/api/users/**",
                                "/api/dashboard/**",
                                "/api/notifications/**",
                                "/api/admin/**",
                                "/api/customer/**",
                                "/api/support/**",
                                "/api/sales/**",
                                "/api/supervisor/**",
                                "/api/products/**",
                                "/api/payments/**",
                                "/api/content/**",
                                "/api/feedback/**",
                                "/api/quotes/**",
                                "/api/knowledge-base/**",
                                "/api/public/**",
                                "/uploads/**",
                                "/api/analytics/**",
                                "/api/team-chat/**",
                                "/api/calendar/**",
                                "/api/leave/**",
                                "/api/referrals/**",
                                "/api/test/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }
}