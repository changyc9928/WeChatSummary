# api-dashboard (React + Vite)

Frontend for WeChatSummary. All backend API calls are auto-generated from the
backend's OpenAPI spec (`backend/openapi.yaml`) using
[openapi-generator](https://openapi-generator.tech) with the `typescript-fetch`
template. The generated client lives in `src/api/generated/` and is wired up
through the configured singleton in `src/api/client.js` (sets the API base URL
and enriches error messages from the response body).

## API codegen workflow

When the backend endpoints change:

1. Make sure the backend is running, then refresh the spec:

   ```bash
   ../backend/scripts/generate-openapi.sh
   ```

2. Regenerate the frontend client:

   ```bash
   npm run generate:api
   ```

3. Commit both `backend/openapi.yaml` and the regenerated `src/api/generated/`.

`npm run generate:api` reads `../backend/openapi.yaml` and regenerates
`src/api/generated/` with the pinned generator version from `openapitools.json`.

## Dev

```bash
npm install
npm run dev
```