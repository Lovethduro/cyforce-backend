package com.cyforce.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    @Autowired
    private JavaMailSender mailSender;

    public void sendVerificationEmail(String to, String token) throws MessagingException {
        String subject = "Verify Your CyForce Account";
        String verificationUrl = "http://localhost:3000/verify-email?token=" + token;

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
}