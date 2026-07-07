package com.cyforce.service;

import com.cyforce.model.CustomerReferral;
import com.cyforce.model.User;
import com.cyforce.repository.CustomerReferralRepository;
import com.cyforce.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class ReferralService {

    private static final int[] DISCOUNT_TIERS = {3, 5, 10};
    private static final int[] DISCOUNT_PERCENTS = {5, 10, 15};

    private final CustomerReferralRepository referralRepository;
    private final UserRepository userRepository;
    private final RequestUserService requestUserService;

    public ReferralService(CustomerReferralRepository referralRepository,
                           UserRepository userRepository,
                           RequestUserService requestUserService) {
        this.referralRepository = referralRepository;
        this.userRepository = userRepository;
        this.requestUserService = requestUserService;
    }

    public Map<String, Object> getMyReferral(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "CUSTOMER");
        CustomerReferral referral = referralRepository.findByUserId(user.getId())
                .orElseGet(() -> createReferralFor(user));
        return toRow(referral);
    }

    public void applyOnRegistration(User newUser, String referralCode, String hearAboutUs) {
        if (hearAboutUs != null && !hearAboutUs.isBlank()) {
            CustomerReferral profile = referralRepository.findByUserId(newUser.getId())
                    .orElseGet(() -> createReferralFor(newUser));
            profile.setHearAboutUs(hearAboutUs.trim());
            profile.setUpdatedAt(LocalDateTime.now());
            referralRepository.save(profile);
        }
        if (referralCode == null || referralCode.isBlank()) {
            return;
        }
        referralRepository.findByReferralCodeIgnoreCase(referralCode.trim()).ifPresent(referrer -> {
            CustomerReferral newProfile = referralRepository.findByUserId(newUser.getId())
                    .orElseGet(() -> createReferralFor(newUser));
            newProfile.setReferredByCode(referralCode.trim().toUpperCase());
            newProfile.setUpdatedAt(LocalDateTime.now());
            referralRepository.save(newProfile);

            referrer.setSuccessfulReferrals(referrer.getSuccessfulReferrals() + 1);
            referrer.setUpdatedAt(LocalDateTime.now());
            referralRepository.save(referrer);
        });
    }

    public Map<String, Object> recordSuccessfulReferral(String referrerUserId) {
        CustomerReferral referral = referralRepository.findByUserId(referrerUserId)
                .orElseThrow(() -> new RuntimeException("Referral profile not found"));
        referral.setSuccessfulReferrals(referral.getSuccessfulReferrals() + 1);
        referral.setUpdatedAt(LocalDateTime.now());
        return toRow(referralRepository.save(referral));
    }

    private CustomerReferral createReferralFor(User user) {
        CustomerReferral referral = new CustomerReferral();
        referral.setUserId(user.getId());
        referral.setReferralCode(generateCode(user));
        referral.setSuccessfulReferrals(0);
        referral.setCreatedAt(LocalDateTime.now());
        referral.setUpdatedAt(LocalDateTime.now());
        return referralRepository.save(referral);
    }

    private String generateCode(User user) {
        String base = user.getFullName() != null
                ? user.getFullName().replaceAll("[^A-Za-z]", "").toUpperCase()
                : "CY";
        if (base.length() < 3) {
            base = (base + "CYF").substring(0, 3);
        } else {
            base = base.substring(0, Math.min(5, base.length()));
        }
        String code = base + (1000 + new Random().nextInt(9000));
        while (referralRepository.findByReferralCodeIgnoreCase(code).isPresent()) {
            code = base + (1000 + new Random().nextInt(9000));
        }
        return code;
    }

    private Map<String, Object> toRow(CustomerReferral referral) {
        int count = referral.getSuccessfulReferrals();
        int nextTier = 0;
        int nextDiscount = 0;
        for (int i = 0; i < DISCOUNT_TIERS.length; i++) {
            if (count < DISCOUNT_TIERS[i]) {
                nextTier = DISCOUNT_TIERS[i];
                nextDiscount = DISCOUNT_PERCENTS[i];
                break;
            }
        }
        int earnedDiscount = 0;
        for (int i = DISCOUNT_TIERS.length - 1; i >= 0; i--) {
            if (count >= DISCOUNT_TIERS[i]) {
                earnedDiscount = DISCOUNT_PERCENTS[i];
                break;
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("referralCode", referral.getReferralCode());
        row.put("successfulReferrals", count);
        row.put("earnedDiscountPercent", earnedDiscount);
        row.put("nextTierAt", nextTier);
        row.put("nextTierDiscountPercent", nextDiscount);
        row.put("inviteMessage", "Share code " + referral.getReferralCode()
                + " — earn up to 15% off after 10 successful referrals.");
        return row;
    }
}
