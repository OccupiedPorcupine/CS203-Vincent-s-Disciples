package app.config;

import app.diagnostics.SetupCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Google can't be asked whether a client ID allows this site, so this only checks the ID's shape
// and says which origin the OAuth client needs (see GoogleAuthService).
@Component
public class GoogleSignInSetupCheck implements SetupCheck {

    private final String clientId;
    private final String baseUrl;

    public GoogleSignInSetupCheck(@Value("${google.client-id:}") String clientId, @Value("${app.base-url}") String baseUrl) {
        this.clientId = clientId.strip();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Override
    public String name() {
        return "google sign-in";
    }

    @Override
    public Result check() {
        if (clientId.isEmpty()) {
            return Result.off("GOOGLE_CLIENT_ID is not set, so the Google button is hidden. Email and password login still work.");
        }
        if (!clientId.endsWith(".apps.googleusercontent.com")) {
            return Result.problem("GOOGLE_CLIENT_ID doesn't end in .apps.googleusercontent.com, so Google sign-in will fail.");
        }
        return Result.ok("Google sign-in is on. Its OAuth client must list " + baseUrl + " as an authorized JavaScript origin.");
    }
}
