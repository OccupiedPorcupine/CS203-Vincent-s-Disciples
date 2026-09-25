package app.service;

import app.dto.LoginRequest;
import app.dto.SignupRequest;
import app.exception.AccountAlreadyExistsException;
import app.exception.InvalidLoginException;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class LocalAuthService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public LocalAuthService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public AppUser signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (appUserRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new AccountAlreadyExistsException();
        }

        AppUser user = AppUser.localUser(
                email,
                passwordEncoder.encode(request.password()),
                request.name().trim()
        );
        try {
            return appUserRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // Covers concurrent attempts that pass the initial existence check.
            throw new AccountAlreadyExistsException();
        }
    }

    public AppUser login(LoginRequest request) {
        AppUser user = appUserRepository.findByEmailIgnoreCase(normalizeEmail(request.email()))
                .orElseThrow(InvalidLoginException::new);

        if (!user.hasPassword() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Use the same response for unknown emails, Google-only accounts, and bad passwords.
            throw new InvalidLoginException();
        }
        return user;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
