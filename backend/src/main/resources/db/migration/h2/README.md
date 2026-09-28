H2-only migrations go here. Flyway picks this folder when the app runs on H2
(`{vendor}` in `spring.flyway.locations`). Shared schema belongs in `../common/`.
Use this folder only when H2 needs different SQL from Postgres; the matching
Postgres file then goes in `../postgresql/` with the same version number.
