package app.account;

// Sends the account emails (password reset, email verification) as plain text.
public interface EmailSender {
    void send(String to, String subject, String text);
}
