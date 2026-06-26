package com.cyforce.service;

import com.cyforce.dto.DashboardStatsResponse;
import com.cyforce.dto.UpdateProfileRequest;
import com.cyforce.dto.UserListItemResponse;
import com.cyforce.dto.UserProfileResponse;
import com.cyforce.model.User;
import com.cyforce.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final FileStorageService fileStorageService;
    private final NotificationService notificationService;

    public UserService(UserRepository userRepository,
                       PasswordService passwordService,
                       FileStorageService fileStorageService,
                       NotificationService notificationService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.fileStorageService = fileStorageService;
        this.notificationService = notificationService;
    }

    public User ensureAdminUser(String email, String fullName, String password) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        boolean passwordSetFromBootstrap = false;

        if (user == null) {
            if (password == null || password.isBlank()) {
                throw new RuntimeException("User not found. Set app.bootstrap-admin-password to create a new admin account.");
            }

            user = new User();
            user.setFullName(fullName == null || fullName.isBlank() ? "CyForce Admin" : fullName.trim());
            user.setEmail(normalizedEmail);
            user.setPhone("");
            user.setAuthProvider("LOCAL");
            user.setCustomerType("enterprise");
            user.setCompanyName("CyForce Technologies");
            user.setPassword(passwordService.encode(password));
            user.setCreatedAt(LocalDateTime.now());
            passwordSetFromBootstrap = true;
            log.info("Creating new admin account for {}", normalizedEmail);
        } else {
            log.info("Promoting existing account to ADMIN: {}", user.getEmail());
            if (password != null && !password.isBlank()
                    && (user.getPassword() == null || user.getPassword().isBlank())) {
                user.setPassword(passwordService.encode(password));
                user.setAuthProvider("LOCAL");
                passwordSetFromBootstrap = true;
                log.info("Set initial password for bootstrap admin {}", user.getEmail());
            }
        }

        user.setRole("ADMIN");
        user.setActive(true);
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(user.getEmailVerifiedAt() == null ? LocalDateTime.now() : user.getEmailVerifiedAt());
        user.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);

        if (passwordSetFromBootstrap && password != null && !password.isBlank()) {
            if (!passwordService.matchesRaw(password, saved.getPassword())) {
                log.error("Bootstrap password verification failed for {} — hash may be corrupt", saved.getEmail());
            } else {
                log.info("Bootstrap password verified (BCrypt) for {}", saved.getEmail());
            }
        }

        log.info("Ensured admin access for {}", saved.getEmail());
        return saved;
    }

    public User ensureStaffUser(String email, String fullName, String role, String password) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        String normalizedRole = role == null ? "CUSTOMER" : role.trim().toUpperCase();
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);

        if (user == null) {
            if (password == null || password.isBlank()) {
                throw new RuntimeException("Password required to create staff account for " + normalizedEmail);
            }
            user = new User();
            user.setFullName(fullName == null || fullName.isBlank() ? normalizedEmail : fullName.trim());
            user.setEmail(normalizedEmail);
            user.setPhone("");
            user.setAuthProvider("LOCAL");
            user.setCustomerType("individual");
            user.setCompanyName("CyForce Technologies");
            user.setPassword(passwordService.encode(password));
            user.setCreatedAt(LocalDateTime.now());
            log.info("Creating new {} account for {}", normalizedRole, normalizedEmail);
        } else {
            log.info("Updating existing account to {}: {}", normalizedRole, user.getEmail());
            if (password != null && !password.isBlank()
                    && (user.getPassword() == null || user.getPassword().isBlank())) {
                user.setPassword(passwordService.encode(password));
                user.setAuthProvider("LOCAL");
                log.info("Set initial password for bootstrap staff {}", user.getEmail());
            }
        }

        user.setRole(normalizedRole);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(user.getEmailVerifiedAt() == null ? LocalDateTime.now() : user.getEmailVerifiedAt());
        if (user.getId() == null) {
            user.setMfaEnabled(false);
        }
        user.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        log.info("Ensured {} access for {}", normalizedRole, saved.getEmail());
        return saved;
    }

    public UserProfileResponse getProfile(String userId) {
        User user = requireUser(userId);
        touchActivity(user);
        return toProfileResponse(user);
    }

    public UserProfileResponse updateProfile(String userId, UpdateProfileRequest request) {
        User user = requireUser(userId);

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName().trim());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone().trim());
        }
        if (request.getCompanyName() != null) {
            user.setCompanyName(request.getCompanyName().trim());
        }
        if (request.getCustomerType() != null && !request.getCustomerType().isBlank()) {
            user.setCustomerType(request.getCustomerType().trim());
        }
        if (request.getPreferredPaymentMethod() != null && !request.getPreferredPaymentMethod().isBlank()) {
            String method = request.getPreferredPaymentMethod().trim().toLowerCase();
            if ("paystack".equals(method) || "flutterwave".equals(method)) {
                user.setPreferredPaymentMethod(method);
            }
        }
        if (request.getShowMotivationalMessages() != null) {
            user.setShowMotivationalMessages(request.getShowMotivationalMessages());
        }

        user.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        notifyProfileChange(saved, "Profile updated");
        return toProfileResponse(saved);
    }

    public UserProfileResponse updateAvatar(String userId, org.springframework.web.multipart.MultipartFile file) {
        return updateProfileImage(userId, file);
    }

    public UserProfileResponse updateLogo(String userId, org.springframework.web.multipart.MultipartFile file) {
        return updateProfileImage(userId, file);
    }

    private UserProfileResponse updateProfileImage(String userId, org.springframework.web.multipart.MultipartFile file) {
        User user = requireUser(userId);
        fileStorageService.deleteIfStored(user.getAvatarUrl());
        if (user.getLogoUrl() != null && !user.getLogoUrl().equals(user.getAvatarUrl())) {
            fileStorageService.deleteIfStored(user.getLogoUrl());
        }
        String imageUrl = fileStorageService.storeAvatar(file);
        user.setAvatarUrl(imageUrl);
        user.setLogoUrl(imageUrl);
        user.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        notifyProfileChange(saved, "Profile photo updated");
        return toProfileResponse(saved);
    }

    private void notifyProfileChange(User user, String title) {
        if ("CUSTOMER".equalsIgnoreCase(user.getRole())) {
            notificationService.create(user.getId(), title, "Your account details were saved successfully.", "info");
        }
    }

    public List<UserListItemResponse> listUsers(String requesterId) {
        User requester = requireUser(requesterId);
        requireStaff(requester);

        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toListItem)
                .collect(Collectors.toList());
    }

    public UserListItemResponse updateUserStatus(String requesterId, String targetUserId, boolean active) {
        User requester = requireUser(requesterId);
        requireAdmin(requester);

        User target = requireUser(targetUserId);
        target.setActive(active);
        target.setUpdatedAt(LocalDateTime.now());
        return toListItem(userRepository.save(target));
    }

    public DashboardStatsResponse getDashboardStats(String requesterId) {
        User requester = requireUser(requesterId);
        requireStaff(requester);

        List<User> users = userRepository.findAll();
        long totalUsers = users.size();
        long activeUsers = users.stream().filter(User::isActive).count();
        long pendingApprovals = users.stream().filter(UserService::isPendingAccountApproval).count();
        long mfaEnabledUsers = users.stream().filter(User::isMfaEnabled).count();
        long verifiedUsers = users.stream().filter(User::isEmailVerified).count();

        Map<String, Long> usersByRole = users.stream()
                .collect(Collectors.groupingBy(u -> u.getRole() == null ? "UNKNOWN" : u.getRole(), Collectors.counting()));

        List<UserListItemResponse> recentUsers = users.stream()
                .sorted(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(this::toListItem)
                .collect(Collectors.toList());

        return new DashboardStatsResponse(
                totalUsers,
                activeUsers,
                pendingApprovals,
                mfaEnabledUsers,
                verifiedUsers,
                usersByRole,
                recentUsers
        );
    }

    public User requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /** Active accounts still awaiting email verification (not deactivated users). */
    public static boolean isPendingAccountApproval(User user) {
        return user != null && user.isActive() && !user.isEmailVerified();
    }

    public void touchActivity(String userId) {
        User user = requireUser(userId);
        touchActivity(user);
    }

    private void touchActivity(User user) {
        user.setLastActivityAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    private void requireStaff(User user) {
        String role = user.getRole() == null ? "" : user.getRole().toUpperCase();
        if (!role.equals("ADMIN") && !role.equals("SUPERVISOR")) {
            throw new RuntimeException("You do not have permission to perform this action");
        }
    }

    private void requireAdmin(User user) {
        if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new RuntimeException("Admin access required");
        }
    }

    private UserProfileResponse toProfileResponse(User user) {
        String profileImage = user.getAvatarUrl() != null ? user.getAvatarUrl() : user.getLogoUrl();
        return new UserProfileResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getCompanyName(),
                user.getAvatarUrl(),
                user.getLogoUrl(),
                profileImage,
                user.getPreferredPaymentMethod(),
                formatMemberSince(user.getCreatedAt()),
                user.getCustomerType(),
                user.getRole(),
                user.isEmailVerified(),
                user.isMfaEnabled(),
                user.getMfaMethod(),
                user.isActive(),
                user.isMustChangePassword(),
                user.wantsMotivationalMessages(),
                user.getAverageRating(),
                user.getRatingCount()
        );
    }

    private String formatMemberSince(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
    }

    public UserListItemResponse toListItem(User user) {
        return new UserListItemResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.isActive(),
                user.isEmailVerified(),
                user.isMfaEnabled(),
                formatDate(user.getCreatedAt()),
                formatDate(user.getLastLoginAt())
        );
    }

    private String formatDate(LocalDateTime dateTime) {
        return dateTime == null ? "Never" : dateTime.format(FORMATTER);
    }
}
