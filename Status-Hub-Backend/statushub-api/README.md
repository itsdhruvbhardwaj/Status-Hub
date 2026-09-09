# Status Hub Backend

This backend is a minimal, production-safe proxy for the FastSaver media resolver. It keeps the production FastSaver API key on the server and returns the signed media URLs to the Android app without exposing the key in the APK.

## What this backend does

- Receives a media URL from the Android app
- Validates the URL
- Calls FastSaver at `https://api.fastsaver.io/v1/fetch`
- Forwards the request using the normal FastSaver API format
- Returns the useful FastSaver JSON response to the Android app
- Never downloads the media file itself
- Keeps the FastSaver API key only on the backend

## Architecture

```text
Android App
   |
   v
Status Hub Backend
   |
   v
FastSaver API
   |
   v
Signed download_url
   |
   v
Android App downloads directly from CDN
```

## Installation

```bash
cd statushub-api
npm install
```

## Environment variables

Create a `.env` file in the project root based on `.env.example`:

```env
FASTSAVER_API_KEY=your_real_key_here
PORT=3000
ALLOWED_ORIGINS=
```

Notes:
- `FASTSAVER_API_KEY` is required.
- `PORT` defaults to `3000` if not set.
- `ALLOWED_ORIGINS` is optional. Leave empty for the default restrictive setup.
- `.env` must never be committed.

## Local development

```bash
cd statushub-api
cp .env.example .env
# edit .env and set your FastSaver key
npm start
```

The server listens on `0.0.0.0` and uses `process.env.PORT` with `3000` as the local fallback.

## API examples

### Health check

```bash
curl http://localhost:3000/health
```

Response:

```json
{
  "ok": true
}
```

### Resolve media URL

```bash
curl -X POST http://localhost:3000/v1/resolve \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.instagram.com/stories/example/123456789/"}'
```

The backend forwards the `url` to FastSaver and returns the useful FastSaver JSON response, including the signed `download_url` when FastSaver provides one.

## Error behavior

The API returns safe, JSON responses without exposing internal server data or the FastSaver API key.

Examples:

- Invalid or missing URL: HTTP 400
- Malformed JSON: HTTP 400
- FastSaver authentication failure: HTTP 401 or 403 with
  ```json
  { "ok": false, "detail": "Media resolver authentication failed." }
  ```
- FastSaver rate limit: HTTP 429 with
  ```json
  { "ok": false, "detail": "Media resolver rate limit reached." }
  ```
- FastSaver temporary outage: HTTP 502 or 5xx with
  ```json
  { "ok": false, "detail": "Media resolver temporarily unavailable." }
  ```
- Request rate limit exceeded for the backend: HTTP 429 with a `Retry-After` header and a safe error message

## Deployment

These steps work for services such as Render, Railway, or Fly.io.

1. Create a new service or app in your hosting provider.
2. Connect the Git repository for this project.
3. Add the required environment variable:
   ```env
   FASTSAVER_API_KEY=your_real_key_here
   ```
4. Optionally configure `PORT` if your platform provides it automatically.
5. Deploy the service.
6. Verify the service is running:
   ```bash
   curl https://your-backend-domain/health
   ```
7. Test the resolver endpoint:
   ```bash
   curl -X POST https://your-backend-domain/v1/resolve \
     -H "Content-Type: application/json" \
     -d '{"url":"https://www.instagram.com/stories/example/123456789/"}'
   ```

## Security notes

- The FastSaver API key exists only on the backend.
- The Android app never receives the key.
- The backend never logs the FastSaver API key or `X-Api-Key` headers.
- The backend does not persist user URLs or create a database.
- The backend does not proxy large media files through the server.
- The backend returns signed CDN URLs for the Android app to download directly.
- The app should replace the FastSaver API endpoint with the backend resolver URL, without needing to know the secret key.

> Important: the FastSaver key belongs only on the server. It must never be embedded in the Android app or distributed to clients.

## Important warning

This server is a secure proxy for production use. It is intentionally small and does not add user tracking, analytics, or media storage.
