package app.account;

import app.diagnostics.SetupCheck.Result;
import app.diagnostics.SetupCheck.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The email setup check against a stand-in SMTP server: logging in, sending a test email, and the
// server not being there.
class EmailSetupCheckTest {

    private ServerSocket smtp;
    private final List<String> received = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() throws IOException {
        if (smtp != null) {
            smtp.close();
        }
    }

    @Test
    void withoutAHostEmailIsOff() {
        EmailSetupCheck check = check("", 25);

        assertEquals(Status.OFF, check.check().status());
        assertEquals(Status.OFF, check.sendTestEmail("owner@example.com").status());
    }

    @Test
    void aServerThatAnswersIsOkAndGetsTheTestEmail() throws IOException {
        startSmtpServer();
        EmailSetupCheck check = check("localhost", smtp.getLocalPort());

        Result result = check.check();
        assertEquals(Status.OK, result.status(), result.detail());
        assertTrue(result.detail().contains("https://chicky.test"), result.detail());

        Result sent = check.sendTestEmail("owner@example.com");
        assertEquals(Status.OK, sent.status(), sent.detail());
        assertTrue(received.contains("RCPT TO:<owner@example.com>"), received.toString());
        assertTrue(received.contains("Subject: Chicky test email"), received.toString());
    }

    @Test
    void aServerThatIsntThereIsAProblemWithTheReason() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        EmailSetupCheck check = check("localhost", closedPort);

        Result result = check.check();
        assertEquals(Status.PROBLEM, result.status());
        assertTrue(result.detail().startsWith("Could not log in to localhost:" + closedPort), result.detail());
        assertEquals(Status.PROBLEM, check.sendTestEmail("owner@example.com").status());
    }

    private static EmailSetupCheck check(String host, int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "2000");
        sender.getJavaMailProperties().put("mail.smtp.timeout", "2000");
        var mailSender = new StaticListableBeanFactory(Map.of("mailSender", sender)).getBeanProvider(JavaMailSender.class);
        return new EmailSetupCheck(host, port, "", "Chicky <no-reply@chicky.test>", "https://chicky.test", mailSender);
    }

    // Just enough SMTP for one connection at a time: accepts everything and records the lines.
    private void startSmtpServer() throws IOException {
        smtp = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            while (!smtp.isClosed()) {
                try (Socket client = smtp.accept();
                     var in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                     var out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.US_ASCII)) {
                    out.print("220 fake ESMTP\r\n");
                    out.flush();
                    boolean inData = false;
                    for (String line; (line = in.readLine()) != null; ) {
                        received.add(line);
                        if (inData) {
                            if (line.equals(".")) {
                                inData = false;
                                out.print("250 queued\r\n");
                            }
                        } else if (line.startsWith("DATA")) {
                            inData = true;
                            out.print("354 go ahead\r\n");
                        } else if (line.startsWith("QUIT")) {
                            out.print("221 bye\r\n");
                            out.flush();
                            break;
                        } else {
                            out.print("250 ok\r\n");
                        }
                        out.flush();
                    }
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }
}
