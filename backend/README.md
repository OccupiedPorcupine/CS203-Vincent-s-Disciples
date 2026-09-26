## Requirements

- Java 21+
- Maven 3.6.3+
- A Google OAuth 2.0 Web client ID

## Run locally

Set the required client ID and start the app:

```powershell
$env:GOOGLE_CLIENT_ID="your-client-id.apps.googleusercontent.com"
```

If you're using WSL/Linux/Mac, run the following instead
```wsl
export GOOGLE_CLIENT_ID="your-client-id.apps.googleusercontent.com"
```

Check that the GOOGLE_CLIENT_ID has been configured properly by using
```wsl
echo $GOOGLE_CLIENT_ID
```

Start the backend with the following:
```wsl
./mvnw spring-boot:run
```


If you use a globally installed Maven, run `mvn spring-boot:run` instead. The default frontend origins are `http://localhost:3000`, `http://localhost:5173`, and `http://localhost:5500`. Override them with `APP_CORS_ALLOWED_ORIGINS` as a comma-separated list, but just leave as is if you are not using these ports for anything else.

Serve the static frontend with the included dependency-free Node.js server (do not open it as a `file://` URL). I recommend using a separate terminal for this after you start the backend.

```powershell/wsl
node frontend/server.mjs
```

You might need to download Node.js with `sudo apt install nodejs`, or download directly from Node.js website if you're on Windows

Then open `http://localhost:5500`. To use a different port, set `FRONTEND_PORT` (inside server.mjs in frontend directory) before starting the server; remember to add that origin to `APP_CORS_ALLOWED_ORIGINS` and Google Cloud Console.

The frontend reads the backend address from `frontend/index.html` and fetches the public Google OAuth client ID from the backend's `GET /api/config` endpoint. Both Google token verification and the browser button therefore use the same `GOOGLE_CLIENT_ID` environment variable. Register `http://localhost:5500` as an authorized JavaScript origin in Google Cloud Console.

## API outline

- `GET /api/csrf` — public; initializes and returns the CSRF token
- `GET /api/config` — public browser configuration, including the Google client ID
- `POST /api/auth/signup` — creates an email/password account and session
- `POST /api/auth/login` — authenticates an email/password account and creates a session
- `POST /api/auth/google` — public authentication exchange; requires the CSRF header
- `POST /api/auth/logout` — invalidates the application session
- `GET /api/profile` — returns the authenticated application user
- `/h2-console` — local development database console

For cookie-based browser calls, use `credentials: "include"`. Fetch `/api/csrf` first, then send its `token` value in the header named by `headerName` on POST/PUT/PATCH/DELETE requests.

Example signup body:

```json
{
  "email": "person@example.com",
  "password": "a-long-unique-password",
  "name": "Person"
}
```

You can also query the user database directly (SQL style) by uncommenting certain codes inside app.config.SecurityConfig.java, and following the instructions in there.

Passwords must contain 12–256 characters and are stored using Spring Security's delegating password encoder with PBKDF2, never as plaintext. Email addresses are normalized to lowercase and must be unique. Google and password accounts are deliberately not linked merely because their emails match; authenticated account linking should be implemented as a separate flow.

This is a development skeleton. Before production, replace H2 and `ddl-auto=update` with a production database and migrations, set secure cookie options, remove H2 console access, rate-limit authentication attempts, and add an email-verification and password-reset flow.
