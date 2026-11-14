# StatusHub public media API v2

One API serves Android and a future website. Only content accessible without an account session is attempted. Public visibility does not guarantee anonymous access: Instagram may require login even for public stories. Such requests return `AUTH_REQUIRED`. No cookies, passwords or FastSaver keys are accepted. Extraction uses a locally installed open-source yt-dlp worker, not a paid resolver service.

## Client flow

1. `GET /v2/capabilities` describes public sources and the file-size limit.
2. `POST /v2/resolve` with `{"url":"https://www.youtube.com/watch?v=..."}`.
3. Display every returned `items[]` entry and its `formats[]`. Preserve `resolutionId`, item `id`, format `id` and the original `sourceUrl`. Audio bitrate is in kbps; null means unknown. No MP3 conversion or quality upscaling is advertised.
4. `POST /v2/jobs` with `{"resolutionId":"...","itemId":"...","formatId":"..."}` returns HTTP 202, `jobId` and `statusPath`.
5. Poll `GET /v2/jobs/{jobId}` every two seconds. `status` is `processing`, `ready` or `failed`. A failed job contains `error.code` and `error.message`.
6. A ready job provides `downloadPath`, `size`, actual `extension`, `mediaType` and `expiresAt`. Join the path to the configured API origin. Navigate to this URL or use a download link on the website; Android saves it locally. The endpoint supports GET, HEAD and single explicit byte ranges, and supplies Content-Disposition.

Example resolve response shape:

```json
{
  "ok": true,
  "apiVersion": 2,
  "resolutionId": "opaque capability",
  "sourceUrl": "https://www.youtube.com/watch?v=example",
  "expiresAt": 1800000000000,
  "items": [{
    "id": "example", "title": "Example", "platform": "youtube",
    "sourceUrl": "https://www.youtube.com/watch?v=example",
    "thumbnailUrl": null,
    "formats": [{"id":"251","extension":"webm","mediaType":"audio","quality":"160 kbps","codec":"opus","bitrateKbps":160,"language":null,"size":null,"note":"Original stream"}]
  }]
}
```

Resolution and completed-file capabilities expire after 30 minutes. Re-resolve the original URL and create a new job if expired. Never combine partial bytes from different prepared jobs. Capabilities grant access: do not log them, expose them in analytics or share them publicly. The server does not log request URLs.

## Errors

Errors use `{ "ok": false, "error": { "code": "...", "message": "..." }, "requestId": "..." }`.

| Code | Meaning / action |
|---|---|
| INVALID_URL / UNSUPPORTED_SOURCE / UNSUPPORTED_URL | Ask for a supported individual HTTPS link |
| AUTH_REQUIRED | Access requires login; this app handles public access only |
| UNAVAILABLE | Removed, expired or inaccessible content |
| NO_FORMATS / UNSUPPORTED_MEDIA | No supported downloadable streams; live/DRM excluded |
| EXTRACTION_FAILED | Source parsing failed; update or investigate backend extractor |
| RESOLUTION_EXPIRED / JOB_EXPIRED | Analyze the original URL again |
| RATE_LIMITED / UPSTREAM_RATE_LIMIT / BUSY | Back off; respect Retry-After if present |
| TOO_LARGE | File exceeds configured limit |
| RESOLVER_UNAVAILABLE | Worker executable missing |
| TIMEOUT / PREPARATION_FAILED | Retry or choose another format |

## Website scope

The website can use the same resolver, format selection and prepared download flow. WhatsApp status discovery is Android-only: a website has no automatic access to WhatsApp's device folders. This change prepares the API; it does not build the website.

## Deployment boundaries

Jobs are bounded and kept in memory with ephemeral files, so use one API instance or sticky routing. Restarting invalidates capabilities. For multiple independent instances, migrate job/resolution state to Redis and files to object storage. Add edge quotas and per-client rate limiting before public exposure; CORS is not authentication. The application deliberately ignores X-Forwarded-For. Configure trusted proxy handling only for the actual deployment topology.

Use a dedicated worker network with outbound private, loopback, link-local and cloud metadata destinations blocked. URL host validation and restricting extractor classes reduce the input surface, but cannot secure all redirects or platform-provided media URLs. Give the cache a filesystem quota: unknown-size streams can briefly exceed application limits before the watchdog stops them. Linux workers kill their process group on timeout, including FFmpeg children. Deploy HTTPS and configure exact allowed website origins.

The old `/v1/resolve` returns an update-required error. Roll out Android v2 API support and the backend together, or retain the old production deployment separately until users upgrade.
