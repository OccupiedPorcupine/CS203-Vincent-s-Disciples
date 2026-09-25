package app.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/*
 * JpaRepository gives you methods like:
 * save()
 * findById()
 * findAll()
 * delete()
 *
 * We add findByGoogleId() because we want to find users
 * by their Google account ID.
 * 
 * findByEmailIgnoreCase() searches for accounts through the user's preferred email
 * the 'normal' way to login
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByGoogleId(String googleId);

    Optional<AppUser> findByEmailIgnoreCase(String email);
}
