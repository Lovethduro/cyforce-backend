package com.cyforce.controller;

import com.cyforce.dto.UpdateProfileRequest;
import com.cyforce.dto.UserListItemResponse;
import com.cyforce.dto.UserProfileResponse;
import com.cyforce.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "http://localhost:3000")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<?> getProfile(@RequestHeader("X-User-Id") String userId) {
        try {
            UserProfileResponse profile = userService.getProfile(userId);
            return ResponseEntity.ok(profile);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/me")
    public ResponseEntity<?> updateProfile(@RequestHeader("X-User-Id") String userId,
                                           @RequestBody UpdateProfileRequest request) {
        try {
            UserProfileResponse profile = userService.updateProfile(userId, request);
            return ResponseEntity.ok(profile);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAvatar(@RequestHeader("X-User-Id") String userId,
                                          @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(userService.updateAvatar(userId, file));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/me/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadLogo(@RequestHeader("X-User-Id") String userId,
                                        @RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(userService.updateLogo(userId, file));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> listUsers(@RequestHeader("X-User-Id") String userId) {
        try {
            List<UserListItemResponse> users = userService.listUsers(userId);
            return ResponseEntity.ok(users);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{targetUserId}/status")
    public ResponseEntity<?> updateUserStatus(@RequestHeader("X-User-Id") String userId,
                                              @PathVariable String targetUserId,
                                              @RequestBody Map<String, Boolean> body) {
        try {
            boolean active = Boolean.TRUE.equals(body.get("active"));
            UserListItemResponse updated = userService.updateUserStatus(userId, targetUserId, active);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        }
    }
}
