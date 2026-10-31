# Deployment

The app is hosted on Vercel's free Hobby plan as one project, `chicky`. `vercel.json` defines three [services](https://vercel.com/docs/services), all running in Singapore (`sin1`), next to the Supabase database.

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
4. [Things to know](#things-to-know)

## How it behaves

- **Idle shutdown and cold starts:** both containers stop after 5 minutes without traffic. The next request starts them again, which takes about 15–20 seconds for the backend and about 15 seconds for the first forecast, while the ML service loads. Pages load straight away, because they come from the CDN.
- **25-second startup limit:** Vercel gives a container about 25 seconds to start listening on `PORT`, or the request fails with `FUNCTION_INVOCATION_FAILED`. The backend makes many small database round trips on startup (Flyway, Hibernate validation), so the database has to be close by. Against a database in Asia it starts in about 8 seconds. Against one in Europe it took over 20, and over 30 when it also had migrations to apply.
- **Migrations run on deploy:** Flyway runs every time the backend starts, so a deployment applies any new migrations to whichever database it's configured for. This is why Preview and Production must use different databases (see below). A fresh database takes about 25 seconds to migrate from scratch, so run the backend against it once from your own machine before the first deployment uses it.
- **Vercel's internal CA:** calls over a service binding use Vercel's own certificate authority. Java doesn't trust it by default, so `backend/docker-start.sh` adds it to a copy of Java's trust store at startup.

## Environment variables

Set these in the Vercel project under **Settings → Environment Variables**, or with `vercel env add`.

| Variable | Preview | Production |
|---|---|---|
| `PORT` | `8080` | `8080` |
| `FORWARD_HEADERS_STRATEGY` | `framework` | `framework` |
| `SESSION_COOKIE_SECURE` | `true` | `true` |
| `TRAINING_DATA_STORAGE_PATH` | `/tmp/training-data` | `/tmp/training-data` |
| `GOOGLE_CLIENT_ID` | Real ID. Add the preview address to the OAuth client's authorized JavaScript origins. | Real ID. Add the production address too. |
| `ASSISTANT_PROVIDER` | `offline`, or leave unset to use OpenAI | Unset, to use OpenAI |
| `OPENAI_API_KEY` | Optional, as a sensitive variable | Required, as a sensitive variable |
| `SPRING_PROFILES_ACTIVE` | `supabase` | `supabase` |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | The **Staging** Supabase project (Seoul, `ap-northeast-2`) | The **Production** Supabase project (Singapore, `ap-southeast-1`) |

Use each project's **Session pooler** address from Supabase's **Connect** panel, as `jdbc:postgresql://<host>:5432/postgres?sslmode=require`. The direct address is IPv6-only, and Vercel can't reach it. Put any new Supabase project in an Asian region, because of the startup limit above.

Only the live site uses the Production project. Don't point a local `backend/.env` at it: running a branch locally applies that branch's migrations, which would change the live site's tables before the code is merged. For local work, use the built-in H2 database, or Staging.

Without the `SPRING_PROFILES_ACTIVE` and database variables, the backend uses an in-memory H2 database. It's wiped every time the container stops, which is fine for a quick check of a preview and nothing else.

## Deploying

- **From GitHub:** once the repository is connected, every push to a branch gets a preview deployment, and every merge to `main` deploys to production.
- **By hand:** run `vercel deploy` from the repository root for a preview, or `vercel deploy --prod` for production. `.vercelignore` keeps local `.env` files and build output out of the upload.

Previews are protected by Vercel login. To call a protected preview from a script or a terminal, use `vercel curl /api/config --deployment <preview url>`.

## Things to know

- **Uploaded training files** are saved to `/tmp` inside the backend container. They disappear when it stops; only their metadata stays in the database.
- **Request size:** Vercel functions accept at most 4.5 MB per request, so training uploads are effectively limited to that, even though the backend allows 25 MB.
- **Hobby plan limits:** 4 hours of active CPU and 360 GB-hours of memory a month, and non-commercial use only. Going over pauses the project instead of charging.
- **The production model** is committed under `ml/artifacts/production/`, so deployments can serve it. To ship a newly trained model, run `python -m ml.train_production` and commit the three files.
- **Local development is unchanged.** Run `node frontend/server.mjs` and the backend on port 8080, as in `LOCAL_RUN_GUIDE.md`. `.vercelignore` keeps `frontend/server.mjs` out of deployments, because otherwise Vercel treats `frontend/` as a Node app.
