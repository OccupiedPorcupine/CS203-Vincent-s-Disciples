package app.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {
    Optional<AccountToken> findByTokenHash(String tokenHash);
    List<AccountToken> findByUserIdAndPurposeAndUsedAtIsNull(Long userId, AccountToken.Purpose purpose);
}
