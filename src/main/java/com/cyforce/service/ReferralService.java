package com.cyforce.service;

import com.cyforce.model.CustomerReferral;
import com.cyforce.model.User;
import com.cyforce.repository.CustomerReferralRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    private static final int[] DISCOUNT_TIERS = {3, 5, 10};
    private static final int[] DISCOUNT_PERCENTS = {5, 10, 15};
    /** Minimum next-purchase reward when a referred customer completes a purchase. */
    private static final int BASE_REFERRAL_REWARD_PERCENT = 5;

    private final CustomerReferralRepository referralRepository;
    private final RequestUserService requestUserService;
    private final NotificationService notificationService;

    public ReferralService(CustomerReferralRepository referralRepository,
                           RequestUserService requestUserService,
                           NotificationService notificationService) {
        this.referralRepository = referralRepository;
        this.requestUserService = requestUserService;
        this.notificationService = notificationService;
    }

    public Map<String, Object> getMyReferral(String userId) {
        User user = requestUserService.requireUser(userId);
        requestUserService.requireRole(user, "CUSTOMER");
        CustomerReferral referral = referralRepository.findByUserId(user.getId())
                .orElseGet(() -> createReferralFor(user));
        return toRow(referral);
    }

    /**
     * Attributes a new signup to a referral code. Discount is NOT granted until that
     * customer completes a purchase ({@link #rewardReferrerForPurchase(String)}).
     */
    public void applyOnRegistration(User newUser, String referralCode, String hearAboutUs) {
        if (hearAboutUs != null && !hearAboutUs.isBlank()) {
            CustomerReferral profile = referralRepository.findByUserId(newUser.getId())
                    .orElseGet(() -> createReferralFor(newUser));
            profile.setHearAboutUs(hearAboutUs.trim());
            profile.setUpdatedAt(LocalDateTime.now());
            referralRepository.save(profile);
        }

        String normalizedCode = normalizeReferralCode(referralCode);
        if (normalizedCode == null) {
            return;
        }

        Optional<CustomerReferral> referrerOpt = referralRepository.findByReferralCodeIgnoreCase(normalizedCode);
        if (referrerOpt.isEmpty()) {
            log.warn("Referral code '{}' used at signup for user {} was not found",
                    normalizedCode, newUser.getEmail());
            return;
        }

        CustomerReferral referrer = referrerOpt.get();
        if (referrer.getUserId() != null && referrer.getUserId().equals(newUser.getId())) {
            log.warn("Ignoring self-referral for user {}", newUser.getId());
            return;
        }

        CustomerReferral newProfile = referralRepository.findByUserId(newUser.getId())
                .orElseGet(() -> createReferralFor(newUser));
        if (normalizedCode.equalsIgnoreCase(nullToEmpty(newProfile.getReferredByCode()))) {
            return;
        }

        newProfile.setReferredByCode(normalizedCode);
        newProfile.setUpdatedAt(LocalDateTime.now());
        referralRepository.save(newProfile);

        log.info("User {} attributed to referral code {} (reward pending first purchase)",
                newUser.getId(), normalizedCode);
    }

    /**
     * When a referred customer pays for the first time, unlock a next-purchase discount
     * for the referrer and notify them.
     */
    public void rewardReferrerForPurchase(String buyerUserId) {
        if (buyerUserId == null || buyerUserId.isBlank()) {
            return;
        }

        Optional<CustomerReferral> buyerOpt = referralRepository.findByUserId(buyerUserId);
        if (buyerOpt.isEmpty()) {
            return;
        }

        CustomerReferral buyerProfile = buyerOpt.get();
        String code = normalizeReferralCode(buyerProfile.getReferredByCode());
        if (code == null) {
            return;
        }
        if (buyerProfile.getReferrerRewardedAt() != null) {
            return;
        }

        Optional<CustomerReferral> referrerOpt = referralRepository.findByReferralCodeIgnoreCase(code);
        if (referrerOpt.isEmpty()) {
            log.warn("Buyer {} has referredByCode {} but referrer profile was not found", buyerUserId, code);
            return;
        }

        CustomerReferral referrer = referrerOpt.get();
        if (referrer.getUserId() != null && referrer.getUserId().equals(buyerUserId)) {
            return;
        }

        buyerProfile.setReferrerRewardedAt(LocalDateTime.now());
        buyerProfile.setUpdatedAt(LocalDateTime.now());
        referralRepository.save(buyerProfile);

        referrer.setSuccessfulReferrals(Math.max(0, referrer.getSuccessfulReferrals()) + 1);
        String reason = "A customer who signed up with your referral code completed a purchase. "
                + "Enjoy " + Math.max(BASE_REFERRAL_REWARD_PERCENT, earnedDiscountPercent(referrer.getSuccessfulReferrals()))
                + "% off your next purchase.";
        grantPendingDiscount(referrer, buyerUserId + ":referral-purchase-reward", reason, true);

        log.info("Referral reward granted to {} after purchase by {}", referrer.getUserId(), buyerUserId);
    }

    /**
     * Returns a pending referral discount without consuming it (for cart totals before pay).
     */
    public Optional<PendingReferralDiscount> peekPendingDiscount(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return referralRepository.findByUserId(userId).flatMap(referral -> {
            Integer percent = referral.getPendingDiscountPercent();
            if (percent == null || percent <= 0) {
                return Optional.empty();
            }
            String reason = referral.getPendingDiscountReason() != null
                    ? referral.getPendingDiscountReason()
                    : "Referral discount (" + percent + "% off)";
            return Optional.of(new PendingReferralDiscount(percent, reason));
        });
    }

    /**
     * Applies and clears a pending referral discount for the customer's next checkout.
     */
    public Optional<PendingReferralDiscount> consumePendingDiscount(String userId) {
        Optional<PendingReferralDiscount> pending = peekPendingDiscount(userId);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        referralRepository.findByUserId(userId).ifPresent(referral -> {
            referral.setPendingDiscountPercent(null);
            referral.setPendingDiscountReason(null);
            referral.setPendingDiscountConsumedAt(LocalDateTime.now());
            referral.setUpdatedAt(LocalDateTime.now());
            referralRepository.save(referral);
        });
        return pending;
    }

    public Map<String, Object> recordSuccessfulReferral(String referrerUserId) {
        CustomerReferral referral = referralRepository.findByUserId(referrerUserId)
                .orElseThrow(() -> new RuntimeException("Referral profile not found"));
        referral.setSuccessfulReferrals(referral.getSuccessfulReferrals() + 1);
        String reason = "Referral reward unlocked. Enjoy "
                + Math.max(BASE_REFERRAL_REWARD_PERCENT, earnedDiscountPercent(referral.getSuccessfulReferrals()))
                + "% off your next purchase.";
        grantPendingDiscount(referral, referrerUserId + ":manual-referral-reward:"
                + referral.getSuccessfulReferrals(), reason, true);
        return toRow(referralRepository.findByUserId(referrerUserId).orElse(referral));
    }

    private void grantPendingDiscount(CustomerReferral referrer,
                                      String notificationRef,
                                      String reason,
                                      boolean notify) {
        int rewardPercent = Math.max(
                BASE_REFERRAL_REWARD_PERCENT,
                earnedDiscountPercent(referrer.getSuccessfulReferrals())
        );
        String finalReason = reason != null ? reason
                : ("Enjoy " + rewardPercent + "% off your next purchase.");

        // Keep reason percent in sync with computed reward.
        if (reason == null || !reason.contains(rewardPercent + "%")) {
            finalReason = "A customer who signed up with your referral code completed a purchase. "
                    + "Enjoy " + rewardPercent + "% off your next purchase.";
        }

        referrer.setPendingDiscountPercent(rewardPercent);
        referrer.setPendingDiscountReason(finalReason);
        referrer.setPendingDiscountGrantedAt(LocalDateTime.now());
        referrer.setPendingDiscountConsumedAt(null);
        referrer.setUpdatedAt(LocalDateTime.now());
        referralRepository.save(referrer);

        if (notify && referrer.getUserId() != null) {
            notificationService.createOnce(
                    referrer.getUserId(),
                    notificationRef,
                    "Referral discount unlocked",
                    finalReason,
                    "promo"
            );
        }
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
        int earnedDiscount = earnedDiscountPercent(count);
        boolean hasPending = referral.getPendingDiscountPercent() != null
                && referral.getPendingDiscountPercent() > 0;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("referralCode", referral.getReferralCode());
        row.put("successfulReferrals", count);
        row.put("earnedDiscountPercent", earnedDiscount);
        row.put("nextTierAt", nextTier);
        row.put("nextTierDiscountPercent", nextDiscount);
        row.put("hasPendingDiscount", hasPending);
        row.put("pendingDiscountPercent", hasPending ? referral.getPendingDiscountPercent() : 0);
        row.put("pendingDiscountReason", hasPending ? referral.getPendingDiscountReason() : null);
        row.put("pendingDiscountGrantedAt", referral.getPendingDiscountGrantedAt());
        row.put("inviteMessage", "Share code " + referral.getReferralCode()
                + " — when someone signs up with it and makes a purchase, you get a discount on your next order.");
        return row;
    }

    private int earnedDiscountPercent(int successfulReferrals) {
        for (int i = DISCOUNT_TIERS.length - 1; i >= 0; i--) {
            if (successfulReferrals >= DISCOUNT_TIERS[i]) {
                return DISCOUNT_PERCENTS[i];
            }
        }
        return 0;
    }

    public static String normalizeReferralCode(String referralCode) {
        if (referralCode == null) {
            return null;
        }
        String normalized = referralCode.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record PendingReferralDiscount(int percent, String reason) {
        long amountKobo(long totalKobo) {
            return Math.round(totalKobo * (percent / 100.0));
        }
    }
}
