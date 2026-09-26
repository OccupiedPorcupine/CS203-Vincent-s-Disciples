# Sales Forecast frontend

The React and TypeScript interface for tomorrow's forecast, workbook ingestion, source provenance, and training-run history.

```bash
npm install
npm run dev
npm test
```

Set `NEXT_PUBLIC_API_BASE_URL` when the Spring Boot API is not available at `http://localhost:8080`. The interface keeps a representative read-only preview when the API is offline.
