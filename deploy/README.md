# Deployment

The app runs in two places, both using the same Supabase Production database:
- **AWS EC2**, the main site, at `https://chicky-app.duckdns.org`, from `deploy/aws` (#156);
- **Vercel**, the backup, at `https://chicky-zeta.vercel.app`.

[Two hosts](#two-hosts-vercel-and-ec2) explains how they can drift apart. The rest of this file is about Vercel.

On Vercel the app is hosted on the free Hobby plan as one project, `chicky`. `vercel.json` defines three [services](https://vercel.com/docs/services), all running in Singapore (`sin1`), next to the Supabase database.

| Service | Folder | Runs as | Reachable at |
|---|---|---|---|
| `web` | `frontend/` | Static files on Vercel's CDN | Every path except `/api/*` |
| `backend` | `backend/` | Container from `backend/Dockerfile` | `/api/*` |
| `ml` | `ml/` | Container from `ml/Dockerfile` | Nowhere public. Only `backend` can call it, through a service binding that sets `ML_SERVICE_URL` |

The pages and the API share one address. That lets the browser send the login cookie with API calls, and it's why `js/api.js` and `js/auth.js` call `/api` on the page's own address when they aren't on `localhost`.

Contents:

1. [How it behaves](#how-it-behaves)
2. [Environment variables](#environment-variables)
3. [Deploying](#deploying)
4. [Two hosts: Vercel and EC2](#two-hosts-vercel-and-ec2)
5. [Things to know](#things-to-know)
6. [Assistant costs and limits](#assistant-costs-and-limits)

## How it behaves

- **Idle shutdown and cold starts:** both containers stop after 5 minutes without traffic. The next request starts them again, which takes a few seconds for the backend (see the next point) and about 14 seconds for the first forecast, while the ML service starts and loads Chronos-2 (see the forecast model below). Pages load straight away, because they come from the CDN.
- **The forecast model (Chronos-2, #106)** runs in the ML service on CPU. Measured on a preview on 5 November 2026:
  - The image is 914 MB (214 MB before), with CPU-only PyTorch and the 456 MB of weights built in, so nothing is downloaded at startup.
  - From cold, the container took about 9 s to start. It listens straight away, well inside the 25-second limit, and loads the model in about 4.5 s on a background thread.
  - A forecast arriving during the load waits up to 15 s for it (`CHRONOS_LOAD_WAIT_SECONDS`), and the backend waits up to 20 s. So the first forecast after idling took 13.5 s, and later ones about 1 s each.
  - Memory peaked under 1 GB in a local container capped at one CPU and 2 GB.
  - If the ML service is down, slow or still loading after that, the backend uses the baseline and doesn't try the ML service again for a minute.
- **25-second startup limit:** Vercel gives a container about 25 seconds to start listening on `PORT`, or the request fails with `FUNCTION_INVOCATION_FAILED`. The backend makes many small database round trips on startup (Flyway, Hibernate validation), so the database has to be close by. Against a database in Asia it starts in about 8 seconds. Against one in Europe it took over 20, and over 30 when it also had migrations to apply.
- **The backend is tuned to start fast:** the startup limit applies on a fraction of a CPU, where Spring and Hibernate used to take about 21 seconds to start, uncomfortably close to the limit. The Docker image now does two things. It records a class-data-sharing archive while it's being built: a training run of the app on H2 that stops as soon as the context is up. It also runs the JVM with the quick C1 compiler only and the serial garbage collector. With half a CPU in Docker, which matches what Vercel showed, the backend started in 5.9 s instead of 22.3 s. If startup creeps up again as the app grows, check `backend/Dockerfile` first.
- **Migrations run on deploy:** Flyway runs every time the backend starts, so a deployment applies any new migrations to whichever database it's configured for. This is why Preview and Production must use different databases (see below). A fresh database takes about 25 seconds to migrate from scratch, so run the backend against it once from your own machine before the first deployment uses it.
- **Vercel's internal CA:** calls over a service binding use Vercel's own certificate authority. Java doesn't trust it by default, so `backend/docker-start.sh` adds it to a copy of Java's trust store at startup.

## Environment variables

Set these in the Vercel project under **Settings → Environment Variables**, or with `vercel env add`.

| Variable | Preview | Production |
|---|---|---|
| `PORT` | `8080` | `8080` |
| `FORWARD_HEADERS_STRATEGY` | `framework` | `framework` |
| `SESSION_COOKIE_SECURE` | `true` | `true` |
| `GOOGLE_CLIENT_ID` | Real ID. Add the preview address to the OAuth client's authorized JavaScript origins. | Real ID. Add the production address too. |
| `ASSISTANT_PROVIDER` | `offline`, or leave unset to use OpenAI | Unset, to use OpenAI |
| `OPENAI_API_KEY` | Optional, as a sensitive variable | Required, as a sensitive variable |
| `SPRING_PROFILES_ACTIVE` | `supabase,prod` (`supabase` until the variables below are set) | `supabase,prod` (`supabase` until the variables below are set) |
| `APP_BASE_URL` | The preview address, `https://…` | The production address, `https://…` |
| `APP_CORS_ALLOWED_ORIGINS` | The same address | The same address |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | An SMTP account (see below); the password as a sensitive variable | The same |
| `SWAGGER_ENABLED` | Optional: `true` to serve the API docs at `/swagger-ui.html` | Unset (the docs stay private) |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_WEBHOOK_SECRET` | Unset: previews sit behind Vercel's login, so Telegram can't reach them | The bot's settings (see below); the token and secret as sensitive variables |
| `CRON_SECRET` | Unset | A long random string, as a sensitive variable; the same value as the repository secret `CRON_SECRET`, for the evening chat reminders (see below) |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | The **Staging** Supabase project (Seoul, `ap-northeast-2`) | The **Production** Supabase project (Singapore, `ap-southeast-1`) |

Use each project's **Session pooler** address from Supabase's **Connect** panel, as `jdbc:postgresql://<host>:5432/postgres?sslmode=require`. The direct address is IPv6-only, and Vercel can't reach it. Put any new Supabase project in an Asian region, because of the startup limit above.

Only the live site uses the Production project. Don't point a local `backend/.env` at it: running a branch locally applies that branch's migrations, which would change the live site's tables before the code is merged. For local work, use the built-in H2 database, or Staging.

**The `prod` profile checks the settings.** With it, the backend refuses to start, and lists what's wrong, if the H2 console is on, the login cookie isn't HTTPS-only, `FORWARD_HEADERS_STRATEGY` is `none`, the database is H2, `MAIL_HOST` isn't set, or `APP_BASE_URL` or a CORS origin isn't `https://`. It also turns the Swagger UI off unless `SWAGGER_ENABLED=true`.

**Email.** Password reset and email verification links are sent over SMTP. Without a domain of your own, Brevo's free plan (300 emails a day) sends from a single verified address; Resend needs a verified domain to send to anyone but you. Locally, without `MAIL_HOST`, the emails are written to the backend log instead.

**Telegram.** The bot (see `backend/README.md`, "Telegram bot") runs on Production only. Previews need Vercel's login, which Telegram can't get past, and a bot has a single webhook, so a preview would take the bot away from the live site. After setting the three variables and redeploying, sign in as an admin and call `POST /api/admin/telegram/webhook` once. Telegram resends an update when the reply is slow, as it can be on a cold start; the backend handles each update once.

The evening sales reminders and prep lists (#101) are sent by `.github/workflows/chat-reminders.yml`, which calls the backend hourly from 12:05 to 23:05 Singapore time. Make a random value (`openssl rand -hex 32`), set it as `CRON_SECRET` on Vercel Production and redeploy, then add it as a repository secret of the same name (GitHub → Settings → Secrets and variables → Actions). Run the workflow once by hand to check it: it should print `200`.

**Previews share Staging, so migrations can reach it out of order.** Each branch's preview applies its own migrations: one PR's V25 can land before another PR's V23. So Preview, and only Preview, also sets:
- `SPRING_FLYWAY_OUTOFORDER=true`, which applies a lower-numbered migration that arrives later;
- `SPRING_FLYWAY_IGNOREMIGRATIONPATTERNS=*:missing,*:future`, so a branch that lacks another branch's migrations still starts.

Production keeps Flyway's strict defaults, so PRs with migrations must merge in number order.

Without the `SPRING_PROFILES_ACTIVE` and database variables, the backend uses an in-memory H2 database. It's wiped every time the container stops, which is fine for a quick check of a preview and nothing else.

## Deploying

- **From GitHub:** once the repository is connected, every push to a branch gets a preview deployment, and every merge to `main` deploys to production.
- **By hand:** run `vercel deploy` from the repository root for a preview, or `vercel deploy --prod` for production. `.vercelignore` keeps local `.env` files and build output out of the upload.

Previews are protected by Vercel login. To call a protected preview from a script or a terminal, use `vercel curl /api/config --deployment <preview url>`.

## Two hosts: Vercel and EC2

Since 7 November 2026 the app also runs on one AWS EC2 instance. EC2 is the main site, and Vercel is kept as the backup.

`deploy/aws/compose.yaml` runs four containers on the instance:
- **Caddy** handles HTTPS on ports 80 and 443.
- **`web`** is Nginx. It serves the pages and passes `/api/*` to the backend.
- **`backend`** and **`ml`** are the same apps as on Vercel.

The images come from ECR, tagged `RELEASE_TAG`. The settings are in `deploy/aws/.env` on the instance; `deploy/aws/.env.example` is the template.

**Both hosts use the same Supabase Production database.** So the data is the same on both, and every difference below comes from the code or the settings.

### How they can drift apart

**Code.** Vercel deploys every merge to `main`. EC2 runs whatever images `RELEASE_TAG` points at. #156 doesn't publish those automatically, so until a pipeline does, EC2 changes only when someone deploys a new release. Even with a pipeline on each side, the two finish at different times, and one can fail while the other succeeds. Neither tells you about the other.

**Migrations.** Flyway runs when a backend starts, so whichever host deploys a new migration first applies it to the shared database. Until the other host catches up, it runs older code against the newer tables:
- Adding a table, or a column that is nullable or has a default, is safe.
- Renaming or dropping a column, or adding a `NOT NULL` column without a default, breaks the older host.

While both hosts are live, split such a change across two releases. Add the new column in the first. Remove the old one in a later release, once both hosts run the code that no longer uses it.

An older backend that restarts still starts. Flyway ignores applied migrations it doesn't have yet (its default `ignoreMigrationPatterns` is `*:future`).

**Settings.** Vercel keeps its environment variables in the project settings. EC2 takes some from `deploy/aws/.env` and has others written into `compose.yaml`. So a new setting has to be added on both hosts. As of 8 November 2026, these differ:

| Setting | Vercel Production | EC2 |
|---|---|---|
| `prod` profile | Off: `APP_CORS_ALLOWED_ORIGINS` isn't set (#109) | On |
| Assistant | OpenAI | `offline`, unless `ASSISTANT_PROVIDER` is set in `.env` |
| Hawker centre closure sync | On | Off (`HAWKER_CENTRE_SYNC_ENABLED=false` in `compose.yaml`) |
| ML service | Private, through Vercel's service binding | On the Compose network only, and it checks `ML_SHARED_SECRET` |
| Telegram | Token, username and webhook secret set | `compose.yaml` passes only `TELEGRAM_BOT_TOKEN`. Without `TELEGRAM_BOT_USERNAME` and `TELEGRAM_WEBHOOK_SECRET`, the bot refuses every webhook call |
| Security headers | `vercel.json` | `deploy/aws/nginx.conf` |

**Things only one host can have.** Point each of these at the main site:
- **The Telegram webhook.** A bot sends updates to a single URL. `POST /api/admin/telegram/webhook` registers the address of the host it's called on, so the last host to call it gets the bot.
- **The chat reminders.** `.github/workflows/chat-reminders.yml` calls `https://chicky-zeta.vercel.app`. One call reminds every owner, because the database is shared, so don't add a second job for the other host: owners would get every reminder twice. To move it to EC2, change the URL and set the same `CRON_SECRET` in `deploy/aws/.env`.

`prune-container-images.yml` only concerns Vercel's registry, so its health check stays on Vercel.

**Jobs inside the backend.** The public holiday sync (on startup and monthly) and the hawker centre closure sync (daily, and on startup when stale) run on every host where they're on. Both rewrite public data from data.gov.sg, so running them on both hosts is harmless.

**What users notice.**
- Sign-in cookies belong to a domain, so signing in on one host doesn't sign you in on the other. The data is the same on both.
- Google sign-in needs both addresses among the OAuth client's authorized JavaScript origins.
- Password reset and verification emails link to the `APP_BASE_URL` of the host that sent them.
- Login rate limits are counted separately on each host.

### After each merge

Check that both hosts are up:

```bash
curl -s https://chicky-app.duckdns.org/api/health
curl -s https://chicky-zeta.vercel.app/api/health   # up to ~15 s if Vercel was asleep
```

After a merge that adds a migration, make sure both hosts run the new code before merging the next one.

### Checking the setup

`/api/health` only says whether the database and ML service answer. To see whether everything else
works, each backend tries its integrations after starting and logs one line per integration, saying
`OK`, `OFF` (not set up; the app works without it) or `PROBLEM`, with the reason:

```bash
docker compose logs backend | grep "Setup check"      # on EC2
vercel logs -e production --since 1h | grep "Setup check"
```

| Check | What it does |
|---|---|
| `email` | Logs in to the SMTP server without sending anything |
| `ml` | Calls the ML service's `/health` |
| `assistant` | Asks OpenAI about the chat model, which costs nothing |
| `google sign-in` | Checks the client ID's form and names the origin the OAuth client must allow |
| `telegram` | Checks which bot the token is for and whether its webhook points at this host |
| `chat reminders` | Checks that `CRON_SECRET` is set |

Signed in as an admin, run the same checks with `GET /api/admin/setup-checks`. To check that email
really arrives, use `POST /api/admin/setup-checks/email`: it sends a test email to your own address
straight away. Reset emails are sent in the background, so a provider error only shows in the log.
To add a check for a new integration, add a `@Component` that implements `app.diagnostics.SetupCheck`.

### Failing over to Vercel

If EC2 is down:
1. Use `https://chicky-zeta.vercel.app`. It already works against the same data.
2. Move Telegram: sign in on Vercel as an admin and call `POST /api/admin/telegram/webhook` (see [Environment variables](#environment-variables)).
3. Check the reminders job is calling Vercel.

To move back to EC2, do the same steps on EC2.

## Things to know

- **The ML service is private.** On Vercel only the backend can reach it, through the binding. On a host where it would be public (e.g. Render), set the same `ML_SHARED_SECRET` on both services: the backend sends it and the ML service refuses requests without it.
- **HTTPS.** Vercel and Render redirect HTTP to HTTPS before requests reach the backend. On a host that doesn't, set `REQUIRE_HTTPS=true`.
- **Rate limits** on login, signup and password reset are kept in memory, so each running backend counts separately and a restart clears them.
- **Uploaded sales files** are stored in the database (`training_data_files`), so they survive restarts and every backend instance can import them.
- **Request size:** Vercel functions accept at most 4.5 MB per request, so training uploads are effectively limited to that, even though the backend allows 25 MB.
- **Hobby plan limits:** 4 hours of active CPU and 360 GB-hours of memory a month, and non-commercial use only. Going over pauses the project instead of charging.
- **The production model** is committed under `ml/artifacts/production/`, so deployments can serve it. To ship a newly trained model, run `python -m ml.train_production` and commit the three files.
- **Local development is unchanged.** Run `node frontend/server.mjs` and the backend on port 8080, as in `LOCAL_RUN_GUIDE.md`. `.vercelignore` keeps `frontend/server.mjs` out of deployments, because otherwise Vercel treats `frontend/` as a Node app.

## Container registry

Vercel builds the `backend` and `ml` services as container images. It keeps every image in the project's container registry, previews included, and caps each repository at **50 images**. When a repository is full, every build fails at the push with `repository has reached the maximum allowed number of images`. Production deploys fail too, and production keeps serving the previous build. On 3 November that happened after about four days of normal activity.

**`.github/workflows/prune-container-images.yml`** clears old images every day at 03:17 Singapore time. It runs `scripts/prune_container_images.py`, which keeps:
- the newest 20 images in each repository;
- the images of the last 3 production deployments, so a rollback still works;
- the images of every deployment from the last 24 hours, so open previews keep working.

It deletes the rest. An image's tag is the first 12 characters of the commit it was built from, which is how images are matched to deployments. If the deployments can't be listed, it deletes nothing and the run fails. After deleting, it checks that `/api/health` on production still returns 200.

**Running it by hand:** GitHub → Actions → Prune container images → Run workflow. Tick "Only list what would be deleted" to see the plan first. From a terminal signed in to Vercel:

```bash
python3 scripts/prune_container_images.py --dry-run   # then without --dry-run
```

**The token.** The workflow needs the repository secret `VERCEL_TOKEN`:
1. In Vercel: Account settings → Tokens → Create. Scope it to the team that owns `chicky`, and give it an expiry date.
2. In GitHub: the repository's Settings → Secrets and variables → Actions → New repository secret. Name it `VERCEL_TOKEN`.

Without it, the scheduled run fails with an error that points here. When the token expires, create a new one and replace the secret.

**To check the count:** `vercel vcr image ls backend --project chicky --scope team_kjkwGmp3oI9Akf8OGmoFqxDr -n 100` (and the same for `ml`).

## Assistant costs and limits

Every assistant message, voice recording and spoken reply is a paid OpenAI call, and the site is public, so each account has daily allowances (issue #65). Days run from midnight to midnight, Singapore time.

| Plan | Messages a day (voice recordings too) | Spoken replies a day |
|---|---:|---:|
| FREE (every new account) | 50 | 20 |
| STANDARD | 150 | 60 |
| PRO | 500 | 200 |

- **The limits** are set in `application.properties` (`app.assistant.plans.*`). The FREE plan's can also be changed without a release, through `ASSISTANT_FREE_MESSAGES_PER_DAY` and `ASSISTANT_FREE_SPOKEN_REPLIES_PER_DAY`.
- **When an account hits a limit,** the assistant says so in English and Mandarin, and entering sales by hand keeps working. When spoken replies run out, replies carry on as text. Replies that are already cached are still read aloud, because they cost nothing.
- **Failures don't count:** a message or reply that fails at OpenAI is given back.
- **Usage is logged:** each message stores its tokens (`assistant_interactions`), and `assistant_daily_usage` adds up each account's messages, recordings, spoken replies, tokens, seconds of audio and characters spoken per day.

**Admin view.** Make an account an admin in the Supabase SQL editor, then sign out and in again:

```sql
UPDATE app_users SET role = 'ROLE_ADMIN' WHERE email = 'you@example.com';
```

An admin can then call these endpoints, while signed in on the site:
- `GET /api/admin/assistant-usage?days=30` lists the accounts that cost the most, with estimated costs at the list prices in `app.assistant.prices.*`.
- `PUT /api/admin/users/{id}/plan` with `{"plan": "STANDARD"}` moves an account to another plan.

**Spend alert.** The limits cap each account, not the total. In the OpenAI platform, open your organisation's **Settings → Limits** and set:
- a **monthly budget**;
- an **email alert** at about 80% of it.

At 50 messages a day, one FREE account costs at most about $10 a month.

