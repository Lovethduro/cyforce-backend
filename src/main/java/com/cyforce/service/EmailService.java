package com.cyforce.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    private final String appUrl;

    public EmailService(@Value("${app.url:http://localhost:3000}") String appUrl) {
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    public void sendVerificationEmail(String to, String token) throws MessagingException {
        String subject = "Verify Your CyForce Account";
        String verificationUrl = appUrl + "/verify-email?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

        String htmlContent = String.format("""
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body>
                <h2>Welcome to CyForce Technologies!</h2>
                <p>Please click the link below to verify your email:</p>
                <a href="%s">Verify Email</a>
                <p>This link expires in 24 hours.</p>
            </body>
            </html>
            """, verificationUrl);

        sendEmail(to, subject, htmlContent);
    }

    public void sendWelcomeCredentialsEmail(String to, String fullName, String temporaryPassword) {
        try {
            String subject = "Your CyForce Account Has Been Created";
            String loginUrl = "http://localhost:3000/login";
            String htmlContent = String.format("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body>
                    <h2>Welcome to CyForce, %s!</h2>
                    <p>An administrator created an account for you. Sign in with the credentials below, then you will be asked to set a new password.</p>
                    <p><strong>Email:</strong> %s</p>
                    <p><strong>Temporary password:</strong> <code>%s</code></p>
                    <p><a href="%s">Sign in to CyForce</a></p>
                    <p>For security, change this password immediately after your first login.</p>
                </body>
                </html>
                """, fullName == null || fullName.isBlank() ? "there" : fullName, to, temporaryPassword, loginUrl);
            sendEmail(to, subject, htmlContent);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send welcome email: " + e.getMessage(), e);
        }
    }

    public void sendPasswordResetEmail(String to, String token) {
        try {
            String subject = "Reset Your CyForce Password";
            String resetUrl = appUrl + "/reset-password?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            String htmlContent = String.format("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body>
                    <h2>Password Reset Request</h2>
                    <p>We received a request to reset your CyForce password. Click the link below to choose a new password:</p>
                    <p><a href="%s">Reset Password</a></p>
                    <p>This link expires in 1 hour. If you did not request this, you can ignore this email.</p>
                </body>
                </html>
                """, resetUrl);
            sendEmail(to, subject, htmlContent);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send password reset email: " + e.getMessage(), e);
        }
    }

    public void sendMfaSetupCode(String to, String code) {
        try {
            String subject = "Your CyForce MFA Setup Code";
            String htmlContent = String.format("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body>
                    <h2>CyForce MFA Setup</h2>
                    <p>Use this code to finish setting up multi-factor authentication:</p>
                    <p style="font-size: 28px; font-weight: bold; letter-spacing: 4px;">%s</p>
                    <p>This code expires in 10 minutes.</p>
                </body>
                </html>
                """, code);
            sendEmail(to, subject, htmlContent);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send MFA code: " + e.getMessage(), e);
        }
    }

    public void sendEmail(String to, String subject, String htmlContent) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom("lovethdurodoye@gmail.com");
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);

        mailSender.send(message);
        System.out.println("✅ Email sent to: " + to);
    }

    public void sendCustomerReengagementEmail(String to, String fullName) {
        try {
            String subject = "We miss you at CyForce";
            String loginUrl = "http://localhost:3000/login";
            String productsUrl = "http://localhost:3000/products";
            String name = fullName == null || fullName.isBlank() ? "there" : fullName.split(" ")[0];
            String htmlContent = String.format("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #111;">
                    <h2>Hi %s, we haven't seen you in a while</h2>
                    <p>It's been over a week since your last visit to CyForce Technologies. We'd love to have you back.</p>
                    <p>Check out our latest security, solar, and enterprise solutions — or message our sales team if you need help choosing the right package.</p>
                    <p>
                        <a href="%s" style="display:inline-block;padding:12px 20px;background:#2B5CE6;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold;">Sign in to your account</a>
                    </p>
                    <p><a href="%s">Browse products</a></p>
                    <p style="font-size:12px;color:#666;">If you're all set, you can ignore this email. We won't send another for at least a week.</p>
                </body>
                </html>
                """, name, loginUrl, productsUrl);
            sendEmail(to, subject, htmlContent);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send re-engagement email: " + e.getMessage(), e);
        }
    }

    public void sendPurchaseConfirmationEmail(String to, String fullName, String amountText,
                                              String description, String surveyUrl) {
        try {
            String subject = "Your CyForce Purchase Confirmation";
            String name = fullName == null || fullName.isBlank() ? "there" : fullName.split(" ")[0];
            String details = description == null || description.isBlank() ? "your order" : description;
            String htmlContent = String.format("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #111;">
                    <h2>Thank you for your purchase, %s!</h2>
                    <p>We've received your payment of <strong>%s</strong> for %s.</p>
                    <p>Your order is being processed. We'd love to hear how your experience was — please take a moment to rate the whole process:</p>
                    <p>
                        <a href="%s" style="display:inline-block;padding:12px 20px;background:#2B5CE6;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold;">Rate your experience</a>
                    </p>
                    <p style="font-size:12px;color:#666;">This short survey helps us improve and recognize our team.</p>
                </body>
                </html>
                """, name, amountText, details, surveyUrl);
            sendEmail(to, subject, htmlContent);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send purchase confirmation email: " + e.getMessage(), e);
        }
    }

    public void sendQuoteConfirmationEmail(String to, String fullName, String agentName,
                                           String quoteSummary, String portalUrl) {
        try {
            String subject = "Your CyForce Quote Request";
            String name = firstName(fullName);
            String agent = agentName == null || agentName.isBlank() ? "our sales team" : agentName;
            String summary = quoteSummary == null || quoteSummary.isBlank() ? "your request" : quoteSummary;
            String htmlContent = String.format("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #111;">
                    <h2>Hi %s, we received your quote request</h2>
                    <p><strong>%s</strong> has been assigned to help you with %s.</p>
                    <p>You can view your request and reply to your sales agent online — no phone call required unless you prefer one.</p>
                    <p>
                        <a href="%s" style="display:inline-block;padding:12px 20px;background:#2B5CE6;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold;">View quote &amp; message your agent</a>
                    </p>
                    <p style="font-size:12px;color:#666;">Save this link to return to your conversation anytime. It is valid for 90 days.</p>
                </body>
                </html>
                """, name, agent, summary, portalUrl);
            sendEmail(to, subject, htmlContent);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send quote confirmation email: " + e.getMessage(), e);
        }
    }

    public void sendQuoteAgentReplyEmail(String to, String fullName, String agentName,
                                         String messagePreview, String portalUrl) {
        try {
            String subject = agentName + " replied to your CyForce quote";
            String name = firstName(fullName);
            String preview = messagePreview == null ? "" : messagePreview;
            if (preview.length() > 280) {
                preview = preview.substring(0, 277) + "...";
            }
            String htmlContent = String.format("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #111;">
                    <h2>Hi %s, %s sent you a message</h2>
                    <p style="background:#f5f7fb;padding:14px;border-radius:8px;color:#333;">%s</p>
                    <p>
                        <a href="%s" style="display:inline-block;padding:12px 20px;background:#2B5CE6;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold;">Reply in your quote portal</a>
                    </p>
                    <p style="font-size:12px;color:#666;">You can reply online without creating an account.</p>
                </body>
                </html>
                """, name, agentName, escapeHtml(preview), portalUrl);
            sendEmail(to, subject, htmlContent);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send quote reply email: " + e.getMessage(), e);
        }
    }

    public void sendLeadOutreachEmail(String to, String fullName, String agentName,
                                      String subject, String body, String portalUrl) {
        try {
            String name = firstName(fullName);
            String agent = agentName == null || agentName.isBlank() ? "CyForce Sales" : agentName;
            String emailSubject = subject == null || subject.isBlank()
                    ? "Message from " + agent + " at CyForce"
                    : subject.trim();
            String messageBody = body == null ? "" : body.trim().replace("\n", "<br/>");
            String portalBlock = portalUrl == null || portalUrl.isBlank() ? "" : String.format("""
                    <p>
                        <a href="%s" style="display:inline-block;padding:12px 20px;background:#2B5CE6;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold;">Open your quote conversation</a>
                    </p>
                    """, portalUrl);
            String htmlContent = String.format("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #111;">
                    <h2>Hi %s,</h2>
                    <p>%s</p>
                    %s
                    <p style="font-size:12px;color:#666;">— %s, CyForce Technologies</p>
                </body>
                </html>
                """, name, messageBody, portalBlock, agent);
            sendEmail(to, emailSubject, htmlContent);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send lead email: " + e.getMessage(), e);
        }
    }

    private String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "there";
        }
        return fullName.split(" ")[0];
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public void sendGuestTicketConfirmationEmail(String to, String fullName, String subject, String portalUrl) {
        try {
            String name = firstName(fullName);
            String ticketSubject = subject == null || subject.isBlank() ? "your support request" : subject;
            String htmlContent = String.format("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #111;">
                    <h2>Hi %s, we received your support request</h2>
                    <p>Your ticket <strong>%s</strong> has been submitted to the CyForce support team.</p>
                    <p>Track your ticket and reply to our team online — no account required.</p>
                    <p>
                        <a href="%s" style="display:inline-block;padding:12px 20px;background:#2B5CE6;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold;">View your support ticket</a>
                    </p>
                    <p style="font-size:12px;color:#666;">Save this link to return anytime. It is valid for 90 days.</p>
                </body>
                </html>
                """, name, escapeHtml(ticketSubject), portalUrl);
            sendEmail(to, "Your CyForce Support Ticket", htmlContent);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send guest ticket confirmation email: " + e.getMessage(), e);
        }
    }

    public void sendGuestTicketAgentReplyEmail(String to, String fullName, String agentName,
                                               String messagePreview, String portalUrl) {
        try {
            String name = firstName(fullName);
            String agent = agentName == null || agentName.isBlank() ? "CyForce Support" : agentName;
            String preview = messagePreview == null ? "" : messagePreview;
            if (preview.length() > 280) {
                preview = preview.substring(0, 277) + "...";
            }
            String htmlContent = String.format("""
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #111;">
                    <h2>Hi %s, %s replied to your ticket</h2>
                    <p style="background:#f5f7fb;padding:14px;border-radius:8px;color:#333;">%s</p>
                    <p>
                        <a href="%s" style="display:inline-block;padding:12px 20px;background:#2B5CE6;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold;">Reply in your support portal</a>
                    </p>
                    <p style="font-size:12px;color:#666;">You can reply online without creating an account.</p>
                </body>
                </html>
                """, name, agent, escapeHtml(preview), portalUrl);
            sendEmail(to, agent + " replied to your CyForce support ticket", htmlContent);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send guest ticket reply email: " + e.getMessage(), e);
        }
    }
}
