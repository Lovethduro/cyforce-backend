package com.cyforce.service;

import com.cyforce.model.User;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class RequestUserService {

    private final UserRepository userRepository;

    public RequestUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public void requireRole(User user, String... roles) {
        String role = user.getRole() == null ? "" : user.getRole().toUpperCase();
        for (String allowed : roles) {
            if (role.equals(allowed.toUpperCase())) {
                return;
            }
        }
        throw new RuntimeException("You do not have permission to perform this action");
    }
}
