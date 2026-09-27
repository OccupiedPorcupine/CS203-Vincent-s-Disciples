H2-only migrations go here. Flyway picks this folder when the app runs on H2
(`{vendor}` in `spring.flyway.locations`). It is normally empty: shared schema
belongs in `../common/`, and Postgres/Supabase-only SQL in `../postgresql/`.
