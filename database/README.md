# Database

The backend stores its data in Postgres, hosted on Supabase. For local development it can instead use an in-memory H2 database that needs no setup and is wiped on every restart.

| | Local H2 (default) | Supabase |
|---|---|---|
| Setup | None | Values in `backend/.env` |
| Data survives a restart | No | Yes |
| Shared with the team | No | Yes: everyone on the same project sees the same data |
| H2 console at `/h2-console` | On | Off |

The schema is the same on both, because it comes from the same Flyway migrations.

Contents:

1. [Connecting to Supabase](#connecting-to-supabase)
2. [Checking that it works](#checking-that-it-works)
3. [How the schema is managed](#how-the-schema-is-managed)
4. [Changing the schema](#changing-the-schema)
5. [Security](#security)
6. [Team rules for the shared database](#team-rules-for-the-shared-database)
7. [Troubleshooting](#troubleshooting)

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
2. **First run only.** You should see `Migrating schema "public" to version "1 - initial schema"` and `"2 - access control"`. Later runs say `Schema "public" is up to date`.
3. **Tables.** In the dashboard, open **Table Editor**. You should see `app_users`, `dishes`, `daily_sales`, `public_holidays`, and `flyway_schema_history`, each marked as RLS enabled.
4. **Data survives a restart.** Sign up through the frontend, restart the backend, and log in again with the same account.

## How the schema is managed

Tables are created by [Flyway](https://documentation.red-gate.com/flyway) migrations. Hibernate does not create them. Flyway runs any new migrations when the backend starts, and records what it ran in `flyway_schema_history`.

```
backend/src/main/resources/db/migration/
├── common/        # runs on H2 and Postgres: the schema itself
│   └── V1__initial_schema.sql
├── postgresql/    # runs on Postgres/Supabase only
│   ├── V2__access_control.sql
│   └── afterMigrate.sql   # runs after every migrate; secures flyway_schema_history
└── h2/            # H2-only SQL, normally empty
```

`spring.jpa.hibernate.ddl-auto=validate` makes Hibernate check every entity against the tables on startup. The app refuses to start if they differ, for example `Schema validation: missing column [price] in table [dishes]`.

## Changing the schema

Any change to an `@Entity` class (a new field, a renamed column, a new entity) needs a new migration file.

1. Change the entity.
2. Add a file in `common/` with the next free version number, for example `V3__add_dish_price.sql`:

   ```sql
   ALTER TABLE dishes ADD COLUMN price NUMERIC(10, 2);
   ```

   Version numbers are shared across all folders, so check `postgresql/` too before picking one.
3. If the migration creates a table, add a matching file in `postgresql/` that locks it down. Use the next version number after step 2, for example `V4__secure_new_table.sql`:

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

## Team rules for the shared database

Everyone whose `.env` points at the project shares one database, so:

1. **Only run merged `main` code against Supabase.** A migration on an unmerged branch changes the database for everyone, and other people's apps may then refuse to start. Use H2 for feature work.
2. **Connection limits.** The free tier allows few connections, and each running backend uses up to 5. About three or four people can run against Supabase at the same time.
3. **Free projects pause** after about a week without activity. If the app suddenly cannot connect, open the dashboard and click **Restore project**.
4. Login sessions are still held in the backend's memory, so users are logged out whenever it restarts. Their accounts and data remain.

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
| `Public holiday sync failed; using stored holidays` | data.gov.sg was unreachable. The app keeps running with the holidays already stored |
| `remaining connection slots are reserved` or `too many clients` | Too many backends running at once. Stop one |
