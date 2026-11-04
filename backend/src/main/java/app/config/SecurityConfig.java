package app.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletResponse;

import app.chat.telegram.TelegramWebhookController;
import app.controller.ChatJobController;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository securityContextRepository,
                                                   @Value("${app.security.require-https:false}") boolean requireHttps,
                                                   @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled) throws Exception {

        // The H2 console is only reachable while it is turned on (local development). Everywhere
        // else (the supabase and prod profiles turn it off) /h2-console/** is refused outright,
        // instead of relying on the console simply not being there.
        AuthorizationManager<RequestAuthorizationContext> h2ConsoleAccess =
                (authentication, context) -> new AuthorizationDecision(h2ConsoleEnabled);

        CsrfTokenRequestAttributeHandler csrfTokenRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfTokenRequestHandler.setCsrfRequestAttributeName(null);

        // Comment this out to block future views of DB.
        // var h2Console = PathRequest.toH2Console();

        http
                .cors(Customizer.withDefaults())

                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfTokenRequestHandler)

                        // Comment this out to block future views of DB.
                        // The H2 console does not send the application's CSRF token.
                        // This exception should only exist for local development.
                        // .ignoringRequestMatchers(h2Console)

                        // Telegram calls the bot's webhook, and the scheduler the chat job, with no
                        // session; each proves itself with a secret header instead
                        // (TelegramWebhookController, ChatJobController).
                        .ignoringRequestMatchers(TelegramWebhookController.PATH, ChatJobController.PATH)
                )

                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true)
                )

                .authorizeHttpRequests(auth -> auth

                        // Public GET endpoints.
                        .requestMatchers(HttpMethod.GET,
                                "/api/csrf",
                                "/api/config",
                                "/api/health"
                        ).permitAll()

                        // Public authentication endpoints.
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/google",
                                "/api/auth/signup",
                                "/api/auth/login",
                                "/api/auth/password-reset",
                                "/api/auth/password-reset/confirm",
                                "/api/auth/verify-email",
                                TelegramWebhookController.PATH,
                                ChatJobController.PATH
                        ).permitAll()

                        // Error responses ({code, message}, see ApiErrorAttributes), so a 401 or 403
                        // keeps its body instead of being refused a second time.
                        .requestMatchers("/error").permitAll()

                        // Swagger / OpenAPI.
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**"
                        ).permitAll()

                        // H2 console for local development only (see h2ConsoleAccess above).
                        .requestMatchers("/h2-console/**").access(h2ConsoleAccess)

                        // Change back to the previous H2 matcher if you don't
                        // want to expose the DB console anymore.
                        // .requestMatchers(h2Console).permitAll()

                        // Admin views (assistant usage and plans). Make an account an admin with
                        // UPDATE app_users SET role = 'ROLE_ADMIN', then sign in again.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        .anyRequest().authenticated()
                )

                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                )

                .headers(headers -> headers
                        // Clickjacking: no other site may show our responses in a frame. The H2
                        // console is itself made of frames, so it needs same-origin while it's on.
                        .frameOptions(frame -> {
                            if (h2ConsoleEnabled) {
                                frame.sameOrigin();
                            } else {
                                frame.deny();
                            }
                        })
                        // Don't leak full URLs (which can hold reset or verification tokens) to other sites.
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )

                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                );

        if (requireHttps) {
            http.redirectToHttps(Customizer.withDefaults());
        }

        return http.build();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // The {pbkdf2} prefix lets the application migrate hashes in the future.
        String encodingId = "pbkdf2";
        return new DelegatingPasswordEncoder(encodingId, Map.of(encodingId, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowCredentials(true);

        // Top 4 HTTP methods we use are GET, POST, PUT and DELETE,
        // but the others are allowed as well.
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}