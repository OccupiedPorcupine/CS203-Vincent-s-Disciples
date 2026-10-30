## What this does


## How it was tested


## Checklist
- [ ] Tests pass (`cd backend && ./mvnw test`)
- [ ] If this adds or changes UI, `backend/src/main/resources/assistant/app-guide.json` and the `data-guide-id` attributes are updated, so the voice assistant can explain and point at it
- [ ] If this adds a table, the migration enables row level security (see `V2__access_control.sql`)
