package app.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Sends through any SMTP provider (Brevo, Resend, Gmail...), configured by the MAIL_* variables.
// Sending happens in the background, so a request takes as long whether or not an email was sent
// and the response doesn't reveal which addresses have accounts.
class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "account-email");
        thread.setDaemon(true);
        return thread;
    });

    SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        executor.execute(() -> {
            try {
                mailSender.send(message);
            } catch (RuntimeException exception) {
                log.error("Could not send \"{}\" email: {}", subject, exception.toString());
            }
        });
    }
}
