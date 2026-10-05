package com.brideside.crm.service.impl;

import com.brideside.crm.entity.User;
import com.brideside.crm.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailServiceImpl.class);

    @Autowired
    private JavaMailSender mailSender;

    @Value("${app.email.from:tech@houseofbarqat.in}")
    private String fromEmail;

    @Value("${app.email.from-name:House of Barqat CRM}")
    private String fromName;

    @Value("${app.frontend.base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Value("${app.frontend.invitation-path:/accept-invitation}")
    private String invitationPath;

    @Value("${app.frontend.reset-path:/reset-password}")
    private String resetPath;

    @Value("${app.frontend.login-path:/login}")
    private String loginPath;

    @Override
    public void sendInvitationEmail(User user, String token) {
        String subject = "Welcome to Houseofbarqat CRM - Complete Your Registration";
        String body = buildInvitationEmailBody(user, token);

        sendEmail(user.getEmail(), subject, body, false);
    }

    @Override
    public void sendPasswordResetEmail(User user, String token) {
        String subject = "Houseofbarqat CRM - Password Reset Request";
        String body = buildPasswordResetEmailBody(user, token);

        sendEmail(user.getEmail(), subject, body, false);
    }

    @Override
    public void sendTestEmail(String toEmail, String subject, String message) {
        sendEmail(toEmail, subject, message, true);
    }

    @Override
    public void sendHtmlEmail(String toEmail, String subject, String html, String plainTextFallback) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, fromName);
            helper.setTo(toEmail);
            helper.setSubject(subject);

            String plain = plainTextFallback != null ? plainTextFallback : "";
            String safeHtml = html != null ? html : "";
            helper.setText(plain, safeHtml);

            mailSender.send(mimeMessage);
            logger.info("HTML email sent successfully to: {}", toEmail);
        } catch (MessagingException e) {
            String errorMessage = "Failed to send email to: " + toEmail;
            logger.error(errorMessage, e);
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Failed to send email to: {}", toEmail, e);
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendHtmlEmailWithCc(String toEmail, List<String> ccEmails, String subject, String html, String plainTextFallback) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail, fromName);
            helper.setTo(toEmail);
            helper.setSubject(subject);

            if (ccEmails != null && !ccEmails.isEmpty()) {
                List<String> cleaned = new ArrayList<>();
                for (String cc : ccEmails) {
                    if (cc != null && !cc.isBlank()) {
                        cleaned.add(cc.trim());
                    }
                }
                if (!cleaned.isEmpty()) {
                    helper.setCc(cleaned.toArray(new String[0]));
                }
            }

            String plain = plainTextFallback != null ? plainTextFallback : "";
            String safeHtml = html != null ? html : "";
            helper.setText(plain, safeHtml);

            mailSender.send(mimeMessage);
            logger.info("HTML email sent successfully to: {} with ccCount={}", toEmail,
                    ccEmails != null ? ccEmails.size() : 0);
        } catch (MessagingException e) {
            logger.error("Failed to send email to: {}", toEmail, e);
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Failed to send email to: {}", toEmail, e);
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        }
    }

    private void sendEmail(String toEmail, String subject, String text, boolean throwOnError) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            // Set from address
            helper.setFrom(fromEmail, fromName);

            // Set to address
            helper.setTo(toEmail);

            // Set subject
            helper.setSubject(subject);

            // Set plain text content
            helper.setText(text, false);

            // Send email
            mailSender.send(mimeMessage);

            logger.info("Email sent successfully to: {}", toEmail);
        } catch (MessagingException e) {
            String errorMessage = "Failed to send email to: " + toEmail;
            logger.error(errorMessage, e);
            
            if (throwOnError) {
                throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
            }
            // For regular emails (invitation, password reset), log but don't throw
            // This ensures user creation/reset doesn't fail if email service has issues
        } catch (Exception e) {
            logger.error("Failed to send email to: {}", toEmail, e);
            if (throwOnError) {
                throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
            }
            // For regular emails (invitation, password reset), log but don't throw
            // This ensures user creation/reset doesn't fail if email service has issues
        }
    }

    private String buildInvitationEmailBody(User user, String token) {
        String invitationLink = buildFrontendLink(invitationPath, token);
        String loginLink = buildFrontendLink(loginPath, null);
        
        return "Dear " + user.getFirstName() + " " + user.getLastName() + ",\n\n" +
                "You have been invited to join Houseofbarqat CRM.\n\n" +
                "Please click on the link below to accept the invitation and set your password:\n" +
                invitationLink + "\n\n" +
                "Once your password is set, you can log in here:\n" +
                loginLink + "\n\n" +
                "This link will expire in 7 days.\n\n" +
                "If you did not request this invitation, please ignore this email.\n\n" +
                "Best regards,\n" +
                "Houseofbarqat CRM Team";
    }

    private String buildPasswordResetEmailBody(User user, String token) {
        String resetLink = buildFrontendLink(resetPath, token);
        String loginLink = buildFrontendLink(loginPath, null);
        
        return "Dear " + user.getFirstName() + " " + user.getLastName() + ",\n\n" +
                "We received a request to reset your password for your Houseofbarqat CRM account.\n\n" +
                "Please click on the link below to reset your password:\n" +
                resetLink + "\n\n" +
                "After resetting, you can log in here:\n" +
                loginLink + "\n\n" +
                "This link will expire in 24 hours.\n\n" +
                "If you did not request a password reset, please ignore this email. Your password will remain unchanged.\n\n" +
                "Best regards,\n" +
                "Houseofbarqat CRM Team";
    }

    private String buildFrontendLink(String path, String token) {
        String base = frontendBaseUrl != null ? frontendBaseUrl.trim() : "";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String normalizedPath = path != null ? path.trim() : "";
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        String url = base + normalizedPath;
        if (token != null && !token.isBlank()) {
            url += (url.contains("?") ? "&" : "?") + "token=" + token;
        }
        return url;
    }
}
