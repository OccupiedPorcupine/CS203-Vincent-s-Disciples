package app.account;

import app.diagnostics.SetupCheck;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

// Logs in to the SMTP server without sending anything. Account emails are sent in the background
// (SmtpEmailSender), so otherwise a wrong password only shows in the log when someone asks for a
// reset link. sendTestEmail sends one for real, for POST /api/admin/setup-checks/email.
@Component
public class EmailSetupCheck implements SetupCheck {

    private final String host;
    private final int port;
    private final String username;
    private final String from;
    private final String baseUrl;
    private final ObjectProvider<JavaMailSender> mailSender;

    public EmailSetupCheck(@Value("${spring.mail.host:}") String host, @Value("${spring.mail.port:587}") int port,
                           @Value("${spring.mail.username:}") String username, @Value("${app.mail.from}") String from,
                           @Value("${app.base-url}") String baseUrl, ObjectProvider<JavaMailSender> mailSender) {
        this.host = host.strip();
        this.port = port;
        this.username = username.strip();
        this.from = from;
        this.baseUrl = baseUrl;
        this.mailSender = mailSender;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public Result check() {
        if (host.isEmpty()) {
            return Result.off("MAIL_HOST is not set, so reset and verification emails are written to the log instead of sent.");
        }
        if (!(mailSender.getIfAvailable() instanceof JavaMailSenderImpl sender)) {
            return Result.problem("MAIL_HOST is set, but no SMTP sender was configured.");
        }
        try {
            sender.testConnection();
        } catch (MessagingException exception) {
            return Result.problem("Could not log in to %s:%d as %s: %s".formatted(host, port, username, exception.getMessage()));
        }
        return Result.ok("Logged in to %s:%d as %s. Emails come from %s and their links point to %s."
                .formatted(host, port, username, from, baseUrl));
    }

    // Sends straight away rather than in the background, so a refusal shows up here.
    public Result sendTestEmail(String to) {
        if (host.isEmpty()) {
            return Result.off("MAIL_HOST is not set, so nothing can be sent.");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Chicky test email");
        message.setText("""
                This is a test from Chicky's setup checks. If it arrived, password reset and verification emails work too.

                Links in them point to %s.
                """.formatted(baseUrl));
        try {
            mailSender.getObject().send(message);
        } catch (MailException exception) {
            return Result.problem("Could not send to %s: %s".formatted(to, exception.getMessage()));
        }
        return Result.ok("Sent to %s from %s. If it doesn't arrive within a few minutes, check the spam folder.".formatted(to, from));
    }
}
