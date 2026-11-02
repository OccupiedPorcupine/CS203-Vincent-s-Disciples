package app.account;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class EmailConfig {

    @Bean
    EmailSender emailSender(@Value("${spring.mail.host:}") String host, @Value("${app.mail.from}") String from,
                            ObjectProvider<JavaMailSender> mailSender) {
        return host.isBlank() ? new LogEmailSender() : new SmtpEmailSender(mailSender.getObject(), from);
    }
}
