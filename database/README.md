# Database

The backend stores its data in Postgres, hosted on Supabase. For local development it can instead use an in-memory H2 database that needs no setup and is wiped on every restart.

| | Local H2 (default) | Supabase |
|---|---|---|
| Setup | None | Values in `backend/.env` |
| Data survives a restart | No | Yes |
| Users stay logged in across a restart | No | Yes |
| Shared with the team | No | Yes: everyone on the same project sees the same data |
| H2 console at `/h2-console` | On | Off |

The schema is the same on both, because it comes from the same Flyway migrations.

Contents:

1. [Connecting to Supabase](#connecting-to-supabase)
2. [Checking that it works](#checking-that-it-works)
3. [How the schema is managed](#how-the-schema-is-managed)
4. [Changing the schema](#changing-the-schema)
5. [Security](#security)
6. [Login sessions](#login-sessions)
7. [Team rules for the shared database](#team-rules-for-the-shared-database)
8. [Troubleshooting](#troubleshooting)

## Connecting to Supabase

### If the project already exists

Ask a project owner to invite you under **Organization settings → Team** in the Supabase dashboard. Get the database password from them through a password manager or another private channel, never through git or group chats. Then skip to [Fill in `backend/.env`](#fill-in-backendenv).

### Creating a new project

1. Go to [supabase.com](https://supabase.com), sign in (GitHub is easiest), and click **New project**.
2. Fill in the form:
   - **Project name**: anything, for example `Main-database`.
   - **Database password**: click **Generate a password** and copy it somewhere private straight away; it cannot be viewed again later. Use letters and numbers only, because symbols such as `$ @ # & %` break the connection settings.
   - **Region**: Southeast Asia (Singapore).
   - **Security**: untick **Enable Data API** and **Automatically expose new tables**; the backend talks to Postgres directly and does not use them. Leave **Enable automatic RLS** ticked. The migrations lock everything down either way.
3. Click **Create new project** and wait one to two minutes.

### Find the three connection values

1. **Project ref.** It is in the browser address bar: in `supabase.com/dashboard/project/abcdefghijklmnop/...`, the ref is `abcdefghijklmnop`.
2. **Pooler host.** Click **Connect** at the top of the project page.
   - The panel opens on the **Framework** tab. Ignore it: that tab is for JavaScript apps.
   - Click the **Direct** tab ("Connection string"), then choose **Session pooler**. Do not use *Direct connection*, which often fails on home networks because it is IPv6-only.
   - You will see a line like `postgresql://postgres.abcdefghijklmnop:[YOUR-PASSWORD]@aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres`.
   - The pooler host is the part between `@` and `:5432`, here `aws-0-ap-southeast-1.pooler.supabase.com`. It may start with `aws-1-`; copy it exactly.
3. **Database password.** The one you saved when creating the project. If you lost it, reset it under **Project Settings → Database**.

### Fill in `backend/.env`

```bash
cp backend/.env.example backend/.env
```

Open `backend/.env`, uncomment the Supabase block, and fill it in:

```
GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com

spring.profiles.active=supabase
DATABASE_URL=jdbc:postgresql://<pooler host>:5432/postgres?sslmode=require
DATABASE_USERNAME=postgres.<project ref>
DATABASE_PASSWORD=<database password>
```

Four rules:

- Write `spring.profiles.active=supabase` exactly like that. `SPRING_PROFILES_ACTIVE=supabase` is silently ignored in this file, and the app quietly uses H2 instead.
- `DATABASE_URL` must use the `jdbc:postgresql://` form above. Do not paste the `postgresql://...[YOUR-PASSWORD]@...` line from the dashboard.
- The username really is `postgres.` followed by the project ref.
- No spaces around `=` and no quotes.

To switch back to H2, comment out the four Supabase lines.

### Start the backend

```bash
cd backend
./mvnw spring-boot:run
```

`.env` is loaded automatically from `backend/` or the repo root, so you no longer need to `export GOOGLE_CLIENT_ID`. Environment variables you do export still take precedence over `.env`.

## Checking that it works

1. **Startup log.** Look for the line naming the database:

   ```
   Database: jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres (PostgreSQL 17.x)
   ```

   If it says `jdbc:h2:mem:appdb`, the Supabase settings were not picked up. See [Troubleshooting](#troubleshooting).
2. **First run only.** You should see `Migrating schema "public"` to versions `"1 - initial schema"`, `"2 - access control"`, and `"3 - login sessions"`. Later runs say `Schema "public" is up to date`.
3. **Tables.** In the dashboard, open **Table Editor**. You should see `app_users`, `dishes`, `daily_sales`, `public_holidays`, `spring_session`, `spring_session_attributes`, and `flyway_schema_history`, each marked as RLS enabled.
4. **Data and logins survive a restart.** Sign in through the frontend, restart the backend, and reload the page: you should still be signed in.

## How the schema is managed

Tables are created by [Flyway](https://documentation.red-gate.com/flyway) migrations. Hibernate does not create them. Flyway runs any new migrations when the backend starts, and records what it ran in `flyway_schema_history`.

```
backend/src/main/resources/db/migration/
├── common/        # runs on H2 and Postgres: the schema itself
│   └── V1__initial_schema.sql
├── postgresql/    # runs on Postgres/Supabase only
│   ├── V2__access_control.sql
│   ├── V3__login_sessions.sql   # session tables, plus RLS
│   └── afterMigrate.sql   # runs after every migrate; secures flyway_schema_history
└── h2/            # H2-only SQL, for when H2 needs different SQL from Postgres
    └── V3__login_sessions.sql   # same tables with H2 column types
```

A version number can appear in both `postgresql/` and `h2/` when the same change needs different SQL on each database, as V3 does. Only one of the two folders runs on any given database.

On Supabase, the history table starts with a `<< Flyway Baseline >>` row at version 0. New projects are not empty: the "Enable automatic RLS" option installs a helper function, `public.rls_auto_enable()`, and Flyway refuses to migrate a non-empty schema unless it records a starting point first. Baselining at version 0 still runs every migration from V1. The helper is harmless: it turns on RLS for new tables, which the migrations do anyway.

`spring.jpa.hibernate.ddl-auto=validate` makes Hibernate check every entity against the tables on startup. The app refuses to start if they differ, for example `Schema validation: missing column [price] in table [dishes]`.

## Changing the schema

Any change to an `@Entity` class (a new field, a renamed column, a new entity) needs a new migration file.

1. Change the entity.
2. Add a file in `common/` with the next free version number, for example `V4__add_dish_price.sql`:

   ```sql
   ALTER TABLE dishes ADD COLUMN price NUMERIC(10, 2);
   ```

   Version numbers are shared across all folders, so check `postgresql/` too before picking one.
3. If the migration creates a table, add a matching file in `postgresql/` that locks it down. Use the next version number after step 2, for example `V5__secure_new_table.sql`:

   ```sql
   ALTER TABLE new_table ENABLE ROW LEVEL SECURITY;
   ```

4. Run `./mvnw test`. `SchemaValidationTest` fails if the entities and migrations disagree.

Rules:

- **Never edit a migration that has already run on Supabase.** Flyway detects the change and refuses to start. Add a new migration instead.
- Keep `common/` SQL portable between H2 and Postgres. `MODE=PostgreSQL` covers most syntax. Put Postgres-only features in `postgresql/`.
- Never reference `flyway_schema_history` inside a migration: Flyway holds a lock on it and the migration hangs. Use `afterMigrate.sql`.

## Security

- **Row-level security (RLS)** is on for every table. Supabase exposes the `public` schema through its Data API as the `anon` and `authenticated` roles. V2 revokes their access to every table, including tables created by future migrations, so the only way in is through the backend.
- The backend connects as the `postgres` owner, which bypasses RLS.
- **Credentials** live only in `backend/.env`, which is gitignored. The Supabase profile has no fallback values, so a missing variable stops startup rather than silently using H2.
- **The H2 console** is disabled on the Supabase profile, so the database console at `/h2-console` never runs next to real user data.
- **API responses** must not return entities directly, because `AppUser` holds the password hash. Return DTOs such as `DishResponse`.

## Login sessions

When someone signs in, with a password or with Google, Spring Session stores their login in the database instead of in the backend's memory:

| Table | Holds |
|---|---|
| `spring_session` | One row per logged-in browser: the session ID, expiry time, and `principal_name` (the user's ID from `app_users`) |
| `spring_session_attributes` | The serialized login for each session: an `AuthenticatedUser` with ID, email, name, picture URL, and role |

As a result, users stay logged in when the backend restarts, and several backends can share the same logins. Sessions expire after 30 minutes of inactivity, and Spring deletes expired rows every minute.

Things to know:

- **Treat these tables like passwords.** Anyone holding a session ID can act as that user. V3 enables RLS and revokes Data API access on both tables.
- **Log everyone out** by running `DELETE FROM spring_session;` in the SQL Editor. Attribute rows are removed with their sessions. To log out one user, run `DELETE FROM spring_session WHERE principal_name = '<user id>';`.
- **Changing `AuthenticatedUser` invalidates existing sessions.** Sessions store it as serialized Java, so adding, removing, or renaming its fields means old sessions can no longer be read. After deploying such a change, run `DELETE FROM spring_session;` so everyone signs in again.
- Each authenticated request reads its session from the database. Over the Singapore pooler this adds a few milliseconds per request.
- On H2 the session tables live in memory like everything else, so logins are still lost on restart there.

## Team rules for the shared database

Everyone whose `.env` points at the project shares one database, so:

1. **Only run merged `main` code against Supabase.** A migration on an unmerged branch changes the database for everyone, and other people's apps may then refuse to start. Use H2 for feature work.
2. **Connection limits.** The free tier allows few connections, and each running backend uses up to 5. About three or four people can run against Supabase at the same time.
3. **Free projects pause** after about a week without activity. If the app suddenly cannot connect, open the dashboard and click **Restore project**.
4. **Logins are shared too.** A session created through one person's backend is valid on everyone's, because they all read the same `spring_session` table.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Startup log shows `jdbc:h2:mem:appdb` | The profile was not activated. Check that `backend/.env` contains `spring.profiles.active=supabase`, not `SPRING_PROFILES_ACTIVE`, and that the line is not commented out |
| `Could not resolve placeholder 'GOOGLE_CLIENT_ID'` | `.env` was not found. Start the app from `backend/` or the repo root, or export the variable |
| `Could not resolve placeholder 'DATABASE_URL'` | The Supabase profile is on but a `DATABASE_*` line is missing or commented out |
| `password authentication failed` | Wrong password, or the username is missing the `.<project ref>` suffix |
| `Connection refused` or a timeout | Wrong pooler host or port. Recopy it from **Connect → Direct → Session pooler** |
| `Schema validation: missing column/table` | An entity changed without a migration. See [Changing the schema](#changing-the-schema) |
| `Validate failed: Migration checksum mismatch` | A migration that already ran on Supabase was edited. Revert the edit and add a new migration |
| `Detected applied migration not resolved locally` | Someone ran a newer branch against Supabase. Pull the latest `main` |
| `Public holiday sync failed; using stored holidays` | data.gov.sg was unreachable, or the app was stopped while the startup sync was still running (the API starts answering before the sync finishes). The app keeps running with the holidays already stored |
| `Found non-empty schema(s) "public" but no schema history table` | Flyway ran without the Supabase profile's baseline settings. Check that `spring.profiles.active=supabase` is set. Do not run `baseline` by hand: it would skip V1 |
| `remaining connection slots are reserved` or `too many clients` | Too many backends running at once. Stop one |
| Errors mentioning `deserialize` or `SerializationFailedException` after a deploy | `AuthenticatedUser` changed and old sessions cannot be read. Run `DELETE FROM spring_session;` in the SQL Editor |
