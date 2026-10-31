package app.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// For local development without an SMTP server: the email, link included, goes to the log.
// The production profile refuses to start without MAIL_HOST, so links never end up in real logs.
class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void send(String to, String subject, String text) {
        log.warn("MAIL_HOST is not set, so this email was not sent. To {}: {}\n{}", to, subject, text);
    }
}
