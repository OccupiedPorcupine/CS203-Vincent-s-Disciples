# Running Chicky on AWS EC2

This folder runs the whole app on one EC2 instance with Docker Compose, instead of Vercel. The database stays on Supabase Production; only the app moves.

| Container | Does what Vercel did | Reachable from |
|---|---|---|
| `caddy` | Serves `frontend/`, sends `/api/*` to the backend, adds the security headers from `vercel.json`, and gets and renews the HTTPS certificate | The internet (ports 80 and 443) |
| `backend` | The Spring Boot API (`backend/Dockerfile`) | Caddy only |
| `ml` | The forecasting service with Chronos-2 (`ml/Dockerfile`) | The backend only |

Every setting is in `deploy/ec2/.env` on the server, starting from [`env.example`](env.example). After the first setup, each merge to main can deploy itself (step 8).

## 1. Launch the instance

- **AMI:** Ubuntu Server 24.04 LTS (64-bit x86).
- **Type:** t3.medium (2 vCPU, 4 GB) or larger. The backend uses up to 1.5 GB and the forecasting model about 1 GB, so 2 GB isn't enough.
- **Region:** ap-southeast-1 (Singapore), next to the Supabase database.
- **Disk:** 30 GB gp3. The ML image alone is about 1 GB, and builds need room.
- **Security group inbound rules:**
  - 22 (SSH) from your own IP only;
  - 80 and 443 (HTTP, HTTPS) from anywhere;
  - 443 UDP from anywhere (optional; enables HTTP/3).
- **An Elastic IP**, attached to the instance, so the address survives a stop and start.

## 2. Point the domain at it

At your DNS provider, add an A record for the site's name (e.g. `app.yourdomain.com`) with the Elastic IP as its value. Check it before going on. Caddy can only get a certificate once the name resolves to this server.

```sh
dig +short app.yourdomain.com     # should print the Elastic IP
```

## 3. Install Docker

```sh
sudo apt-get update && sudo apt-get install -y ca-certificates curl git
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER     # then log out and back in
```

**Optional:** add 2 GB of swap as a safety net for the first build.

```sh
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## 4. Get the code and the settings

```sh
git clone https://github.com/OccupiedPorcupine/CS203-Vincent-s-Disciples.git ~/chicky
cd ~/chicky/deploy/ec2
cp env.example .env && chmod 600 .env
nano .env
```

Fill in every value. Each one is explained in the file. The secrets come from where they were made:

| Setting | Where to get it |
|---|---|
| `DATABASE_*` | Supabase → Project Settings → Database → Connection string → Session pooler |
| `MAIL_PASSWORD` | The Gmail app password |
| `OPENAI_API_KEY` | The OpenAI key |
| `TELEGRAM_*` | @BotFather and the webhook secret |
| `CRON_SECRET` | The same value as the GitHub secret `CRON_SECRET` |

Vercel won't show a sensitive value again once it's saved.

`SPRING_PROFILES_ACTIVE=supabase,prod` turns on the production checks. The backend won't start if:
- `APP_BASE_URL` or `APP_CORS_ALLOWED_ORIGINS` isn't `https://`;
- `MAIL_HOST` is empty;
- the database isn't Supabase.

It logs what's wrong under "Insecure settings for the prod profile".

## 5. Start it

```sh
cd ~/chicky/deploy/ec2
docker compose up -d --build      # the first build takes 10–20 minutes
docker compose ps                 # all three should be "running"
docker compose logs -f backend    # wait for "Started Application"; Ctrl-C to stop watching
```

## 6. Check it

From your own computer:

```sh
SITE=https://app.yourdomain.com
curl -s $SITE/api/health                                         # {"status":"UP", ... "ml":{"status":"UP"}}
curl -s -o /dev/null -w '%{http_code}\n' $SITE/api/profile       # 401
curl -sI $SITE/index.html | grep -iE 'x-frame|content-security|strict-transport'
curl -s -o /dev/null -w '%{http_code}\n' $SITE/swagger-ui.html   # 404
```

Then in a browser:
- Sign in with email and password.
- Open a dish's forecast. Its basis should say "Forecast model (Chronos-2)". The first forecast after a start can take about 15 s while the model loads.
- Use **Forgot password?** and check that the email arrives with an `https://app.yourdomain.com` link.

**Google sign-in:**
1. In Google Cloud Console → APIs & Services → Credentials, open the OAuth client.
2. Add `https://app.yourdomain.com` to **Authorised JavaScript origins**.
3. Allow a few minutes for it to take effect.

Until then, the button says the origin isn't allowed.

## 7. Switching over

Until you switch, Vercel stays the live site. Both sites use the same database, so data is shared, and both should run the same version of main (step 8 keeps EC2 up to date).

Send people to the EC2 address once step 6 passes. Then move the parts that can only point at one site:

1. **Chat reminders.** In GitHub → Settings → Secrets and variables → Actions → Variables, add `SITE_URL` = `https://app.yourdomain.com`. `.github/workflows/chat-reminders.yml` then calls EC2 instead of Vercel.
2. **Telegram.** A bot has a single webhook. Sign in to the EC2 site as an admin, open the browser's developer console and run the snippet below. It points the bot at EC2's `APP_BASE_URL` and should print `200`.

   ```js
   const csrf = await (await fetch("/api/csrf")).json();
   (await fetch("/api/admin/telegram/webhook", { method: "POST", headers: { [csrf.headerName]: csrf.token } })).status
   ```
3. **Vercel.** Keep it as a fallback until after the presentation. To stop it deploying, go to Vercel → chicky → Settings → Git and disconnect the repository. To go back to Vercel, reverse items 1 and 2.

## 8. Deploying updates

**By hand, on the server:**

```sh
~/chicky/deploy/ec2/deploy.sh
```

It pulls the latest main, rebuilds what changed and restarts it. The old containers keep serving until the new ones are built.

**Automatically after each merge.** `.github/workflows/deploy-ec2.yml` runs the script over SSH. Set these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `EC2_HOST` | `app.yourdomain.com` |
| `EC2_USER` | `ubuntu` |
| `EC2_APP_DIR` | `/home/ubuntu/chicky` |
| `EC2_SSH_KEY` | A private key made for this purpose, `ssh-keygen -t ed25519 -f chicky-deploy -N ""`. Add `chicky-deploy.pub` to `~/.ssh/authorized_keys` on the server. |
| `EC2_KNOWN_HOSTS` | The output of `ssh-keyscan -t ed25519 app.yourdomain.com` |

Also set the variable `SITE_URL` (step 7), so the workflow checks the site after deploying.

Until `EC2_HOST` is set, the workflow does nothing. Migrations run when the new backend starts, as on Vercel, so the merge-in-order rule for migration PRs still applies.

## When something goes wrong

| Symptom | Look at |
|---|---|
| The site doesn't load, or there's a certificate error | `docker compose logs caddy`. Usually DNS isn't pointing here yet, or port 80 or 443 is closed in the security group. |
| `/api/...` returns 502 | `docker compose logs backend`. The backend is still starting, or refused to start because of a setting. |
| Forecasts say "past sales only" | `docker compose logs ml`. Check it reports Chronos-2 as ready. |
| The server runs out of memory | `free -h` and `docker stats`. Add swap (step 3) or use a larger instance. |
