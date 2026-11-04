## Session expiry

New sign-ins default to 60 minutes idle and 8 hours absolute lifetime. Account → Sign-in duration
lets each user save 5–1,440 idle minutes and 1–168 absolute hours (idle cannot exceed absolute).
Preferences are stored on `app_users` by migration V35 and apply to subsequent sign-ins on all devices.
Existing sessions keep their original limits; saving preferences never resets the sign-in clock.
Spring Session JDBC enforces inactivity since the last request, including background requests.
The absolute deadline is persisted in the session at authentication and checked before security
loads the session. At the deadline, the next protected request is refused; no browser timer is required.
Older sessions without a deadline expire eight hours after their original creation time.
Settings are available through authenticated, CSRF-protected GET/PUT `/api/account/session-settings`
with `{ "idleMinutes": 60, "absoluteMinutes": 480 }` (GET does not require a CSRF token).

## Requirements

- Java 21+
- Maven 3.6.3+
- Optional: a Google OAuth 2.0 Web client ID, for Google sign-in. Without one, the Google button is hidden and email/password login works as usual.

## Run locally

By default the backend uses an in-memory H2 database that is wiped on every restart. To keep data in the team's Supabase Postgres database instead, follow [database/README.md](../database/README.md).

The easiest way to configure the backend is a `backend/.env` file, which is loaded automatically and is gitignored:

```wsl
cp backend/.env.example backend/.env
```

That is enough to start the backend. Google sign-in is optional: with `GOOGLE_CLIENT_ID` empty, the login page hides the Google button and email/password login works as usual.

### Google sign-in (optional)

To turn Google sign-in on, set `GOOGLE_CLIENT_ID` in `backend/.env` to the team's client ID. It is shared privately in the team chat and never committed. The ID is created in Google Cloud Console under **APIs & Services → Credentials** (type **Web application**).

Never use a placeholder such as `your-client-id.apps.googleusercontent.com` or `local-…`. The backend would start, but Google rejects the made-up ID with **Error 401: invalid_client** (issue #16). Leave the value empty instead.

Every address the frontend is served from (for example `http://localhost:5500`) must be listed under **Authorized JavaScript origins** on that client in Google Cloud Console. Otherwise Google shows `origin_mismatch`. Ask whoever owns the Google Cloud project to add new origins.

To check which ID the running backend is using, open `http://localhost:8080/api/config`. The frontend reads the ID only when the page loads, so reload the login page after restarting the backend.

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
- `GET /api/dishes`, `POST /api/dishes` — lists or creates the signed-in user's dishes
- `GET /api/dishes/{dishId}/sales`, `POST /api/dishes/{dishId}/sales` — lists sales, or adds new days (rejects days that already have a record)
- `PUT /api/dishes/{dishId}/sales/{date}` — sets one day's quantity, replacing any existing record; returns `previousQuantity`
- `POST /api/forecast` — `{dishId, forecastDate}`, from today up to 7 days ahead → expected portions, the likely `low`–`high` range, `method` (`MODEL` or `BASELINE`), `basis` and `note`. The model uses NEA's 24-hour forecast for today and the 4-day outlook for the next 4 days; `weatherSource` and `weather` say which one (both null when no weather was used). Later dates use the baseline. The range is measured on estimates made as far after the last recorded sale as this date, so it widens when the latest sales are old
- `GET /api/dishes/{dishId}/aliases`, `POST /api/dishes/{dishId}/aliases` — other names for a dish (e.g. `鸡饭`); matching ignores case and spacing, and each name points to one dish per user (409 `ALIAS_IN_USE` otherwise)
- `GET /api/assistant/status` — whether the voice assistant and voice input are available
- `POST /api/assistant/transcribe` — multipart `audio` (under a minute) → `{text, languages}`; audio is not stored
- `POST /api/assistant/messages` — `{conversationId?, text, page, inputMode, pageContext?, forecast?}` → `{conversationId, reply, proposal?, guide?}`; a proposal is only a suggestion. `forecast` (`{dishId, date}`) is the forecast on screen, so "why so high?" refers to it
- `POST /api/assistant/proposals/{id}/confirm` — saves a proposal (optionally edited: `{entries: [...]}`); `.../cancel` discards it
- `GET /api/closures` — the stall's rest days, closed days (`source` `OWNER` or `NEA`), trading hours, hawker centre and its upcoming NEA closures
- `GET /api/closures/hawker-centres`, `PUT /api/closures/hawker-centre` — NEA's hawker centres, and `{hawkerCentreId}` (null to unlink) to pick the stall's
- `PUT /api/closures/nea-days` — `{from, to, open}` marks NEA's closed days in a range as open anyway, or closed again
- `GET /api/chat-links` — chat apps set up on this server, each with the account's linked chat; `POST /api/chat-links/{channel}` → `{code, link, expiresAt}`, a one-time code (10 minutes) that links the chat it is sent from; `DELETE /api/chat-links/{id}` unlinks
- `POST /api/channels/telegram/webhook` — Telegram's updates for the bot; only with the `X-Telegram-Bot-Api-Secret-Token` header (see "Telegram bot")
- `GET /api/data-sources` — lists the signed-in user's registered training sources
- `POST /api/data-sources` — accepts a multipart CSV/XLSX upload (maximum 25 MB)
- `/h2-console` — local development database console (H2 only; disabled when using Supabase)

The frontend data-ingestion page is available at `/data.html`. Uploaded files are stored in the
database (`training_data_files`) and are identified by SHA-256 so duplicate uploads are not
registered twice for the same user. Uploads from before V25 were saved under
`../.local-data/training-data`; they're still read from there while the file exists.
Uploads are currently marked `AVAILABLE_FOR_TRAINING`; connecting them to a training job and an
active model version is a separate pipeline step.

NEA's hawker centre cleaning and works dates come from data.gov.sg's "Dates of Hawker Centres
Closure" (`HawkerCentreSyncService`): daily at 04:30, and on startup when the stored copy is more
than 20 hours old. A stall linked to a centre treats those dates as closed days. They aren't
copied into `closed_dates`, so the owner's own days always win and unlinking drops only NEA's.
Set `HAWKER_CENTRE_SYNC_ENABLED=false` to stop the fetch; tests have it off.

### Voice assistant

The assistant turns what a user says or types ("yesterday chicken rice 42, 叉烧饭 30") into proposed
sales, which are only saved when the user confirms them. Set it up in `backend/.env`:

- `OPENAI_API_KEY=...` uses OpenAI (`gpt-6-luna` for understanding, `gpt-transcribe` for speech).
  Model names are in `application.properties`.
- Without a key, add `ASSISTANT_PROVIDER=offline` to try it with a rule-based stand-in that
  understands typed sales like `chicken rice 42, char siew 30` (no voice).

Code is in `src/main/java/app/assistant/`. The prompt is `AssistantPrompt`, the model's tools are
`AssistantTools`, and every message and its outcome is logged in `assistant_interactions`.

It also explains forecasts. The forecast page's "Ask about this forecast" card sends questions like
"why so high?" with the dish and date on screen. The read-only `explain_forecast` tool returns that
forecast and only what it was made from: the basis, the dish's last 4 same-weekday sales and the last
7 days, and whether the weather forecast and a public holiday were used. The prompt tells the model
to quote only those and to say that any other reason the user suggests isn't taken into account.

### Telegram bot

Owners can record sales from Telegram (issues #99 and #100): they link the chat on the **Account** page, then send sales as a message or a voice note and tap **Confirm** in the chat. `/today`, `/prep` and `/forecast` answer from the account's data without the language model. The bot is off unless `TELEGRAM_BOT_TOKEN` is set.

To turn it on:

1. In Telegram, message [@BotFather](https://t.me/BotFather), send `/newbot` and follow the steps. It gives you the bot's token and username.
2. Set `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME` (without the `@`) and `TELEGRAM_WEBHOOK_SECRET` (any long random string of letters, digits, `_` and `-`, e.g. from `openssl rand -hex 32`). Keep the token and the secret out of git.
3. Telegram must be able to reach the backend over HTTPS at `APP_BASE_URL`. Register the webhook once per environment by calling `POST /api/admin/telegram/webhook` as an admin. It also sets the bot's command menu.

Telegram calls `POST /api/channels/telegram/webhook` with each update, and the backend refuses any call without the secret. A bot has one webhook, so each environment needs its own bot. Only private chats are handled; group chats are ignored. Replies are plain text, and a voice note gets a spoken reply too, which counts against the daily spoken replies.

### Demo data

An empty account shows nothing on the dashboard. With the backend running, fill a demo account
with 120 days of fake chicken rice stall sales (safe to run again; it only adds missing days):

```bash
python3 scripts/seed_demo_data.py            # signs in as demo@chicky.test / chicky-demo-password
python3 scripts/seed_demo_data.py --help     # other account, period, or backend URL
```

The script writes to whichever database the backend uses, so check `backend/.env` before running
it against Supabase. `scripts/sample-data/demo_sales.csv` holds the same kind of data
(`date,dish,quantity_sold`) for trying the Data ingestion upload.

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

## Database and tests

- The schema is managed by Flyway migrations in `src/main/resources/db/migration/`, and Hibernate only validates it (`ddl-auto=validate`). **Any entity change needs a new migration.** See [database/README.md](../database/README.md#changing-the-schema).
- Login sessions are stored in the database (`spring_session` tables), so users stay logged in across backend restarts. Changing `AuthenticatedUser` invalidates stored sessions; see [database/README.md → Login sessions](../database/README.md#login-sessions).
- `./mvnw test` runs on H2 and never touches Supabase. `SchemaValidationTest` fails if an entity and the migrations disagree.
- Tests tagged `integration` need external services and are skipped by default. `MlForecastClientIntegrationTest` needs the ML API on `localhost:8000`. Run them with `./mvnw test -DexcludedGroups= -Dgroups=integration`.

This is a development skeleton. Before production, set secure cookie options, remove the H2 console dependency, rate-limit authentication attempts, and add an email-verification and password-reset flow.
