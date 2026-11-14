# StatusHub public media backend

Shared API for Android and the planned website. YouTube, Instagram and Facebook extraction runs here; WhatsApp local statuses and direct file links remain on Android. No FastSaver service or account cookies are used.

## Run with Docker

```sh
docker build -t statushub-api .
docker run --rm -p 3000:3000 --env-file .env --memory=1g --cpus=2 statushub-api
```

Copy `.env.example` to `.env` first. Remove any obsolete FastSaver settings. Never commit `.env`. Set `ALLOWED_ORIGINS` to the exact website origin(s); an empty list allows native clients but rejects browser Origin headers. Put HTTPS and per-client quotas in front of this service.

The image installs Node 22, Python, FFmpeg and yt-dlp with its default dependencies (including YouTube JavaScript support). Rebuild the image to update extraction. For reproducible releases supply `--build-arg YTDLP_VERSION=<tested-version>` and record that version in deployment configuration. Test public fixtures before promoting an extractor update.

## Local setup

Install Node 22+, Python 3.10+, FFmpeg and `yt-dlp[default]`. Ensure `yt-dlp` and `ffmpeg` are on PATH, or set `YTDLP_PATH` to the executable. Then:

```sh
npm ci
npm test
npm start
```

Configure `STATUS_HUB_API_BASE_URL=https://your-api-host` in Android's local.properties or build environment. Both debug and release use this URL. Debug can use HTTP for emulator development; release requires HTTPS.

See [API.md](API.md) for the complete web/Android flow, error codes, expiry, compatibility and production resource/network requirements.

## Limits

Publicly visible content may still require login. In that case the API returns AUTH_REQUIRED; it cannot promise public-story coverage. Extraction supports only the downloadable media returned by the worker, including multi-item results where available. No fabricated audio quality options, private-media access or DRM bypass. Server preparation uses bandwidth and temporary storage, unlike the previous URL-only proxy. Default final file limit is 256 MiB, two concurrent workers, and 30-minute capabilities. Use a filesystem quota as well.

Tests use deterministic extractor fixtures and exercise actual API jobs/files; they do not establish current success against live social platforms.
