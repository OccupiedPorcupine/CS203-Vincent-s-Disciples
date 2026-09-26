package app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Authentication is established explicitly after Google token verification, so
// the generated development username/password user is intentionally disabled.
//main entry point
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class Application {

    public static void main(String[] args) {
        //starts the backend
        SpringApplication.run(Application.class, args);
    }
}
