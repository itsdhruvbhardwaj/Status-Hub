import dotenv from 'dotenv';
import Fastify from 'fastify';

dotenv.config();

const FASTSAVER_API_KEY = (process.env.FASTSAVER_API_KEY || '').trim();
const PORT = Number(process.env.PORT) || 3000;
const FASTSAVER_TIMEOUT_MS = Number(process.env.FASTSAVER_TIMEOUT_MS) || 30000;
const RESOLVE_RATE_LIMIT_PER_MINUTE = Number(process.env.RESOLVE_RATE_LIMIT_PER_MINUTE) || 30;
const MAX_BODY_SIZE = Number(process.env.MAX_BODY_SIZE) || 16384;
const allowedOrigins = (process.env.ALLOWED_ORIGINS || '')
    .split(',')
    .map((origin) => origin.trim())
    .filter(Boolean);

if (!FASTSAVER_API_KEY) {
    console.error('FASTSAVER_API_KEY is required. Set it in your environment or .env file.');
    process.exit(1);
}

const rateLimitStore = new Map();

function getClientIdentifier(request) {
    const forwardedFor = request.headers['x-forwarded-for'];

    if (typeof forwardedFor === 'string' && forwardedFor.trim()) {
        return forwardedFor.split(',')[0].trim();
    }

    return request.ip || 'unknown';
}

function isAllowedOrigin(origin) {
    return !origin || allowedOrigins.length === 0 || allowedOrigins.includes(origin);
}

function getRetryAfterSeconds(resetAt, now) {
    const deltaMs = Math.max(resetAt - now, 0);
    return Math.ceil(deltaMs / 1000);
}

const app = Fastify({
    logger: { level: 'info' },
    trustProxy: true,
    bodyLimit: MAX_BODY_SIZE,
    routerOptions: {
        ignoreTrailingSlash: true,
    },
});

app.addHook('onRequest', async (request, reply) => {
    request.startTime = Date.now();

    const origin = request.headers.origin;
    if (origin && !isAllowedOrigin(origin)) {
        return reply.code(403).send({
            ok: false,
            detail: 'Origin not allowed.',
        });
    }

    if (allowedOrigins.length > 0 && origin) {
        reply.header('Access-Control-Allow-Origin', origin);
        reply.header('Vary', 'Origin');
    }

    if (request.method === 'OPTIONS') {
        reply.header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
        reply.header('Access-Control-Allow-Headers', 'Content-Type, Authorization');
        return reply.code(204).send();
    }

    if (request.url !== '/v1/resolve') {
        return;
    }

    const clientId = getClientIdentifier(request);
    const now = Date.now();
    const bucket = rateLimitStore.get(clientId);

    if (!bucket || bucket.resetAt <= now) {
        rateLimitStore.set(clientId, {
            count: 1,
            resetAt: now + 60000,
        });
        return;
    }

    if (bucket.count >= RESOLVE_RATE_LIMIT_PER_MINUTE) {
        const retryAfterSeconds = getRetryAfterSeconds(bucket.resetAt, now);
        reply.header('Retry-After', String(retryAfterSeconds));
        return reply.code(429).send({
            ok: false,
            detail: 'Media resolver rate limit reached.',
        });
    }

    bucket.count += 1;
});

app.addHook('onResponse', async (request, reply) => {
    const durationMs = Date.now() - request.startTime;
    const statusCode = reply.statusCode;

    request.log.info(
        {
            requestId: request.id,
            method: request.method,
            endpoint: request.url,
            host: request.headers.host,
            status: statusCode,
            durationMs,
        },
        'request completed',
    );
});

app.setErrorHandler((error, request, reply) => {
    if (error.statusCode === 400 && error.type === 'entity.parse.failed') {
        return reply.code(400).send({
            ok: false,
            detail: 'Request body must be valid JSON.',
        });
    }

    if (error.validation) {
        return reply.code(400).send({
            ok: false,
            detail: 'Invalid request payload.',
        });
    }

    if (error.statusCode === 400) {
        return reply.code(400).send({
            ok: false,
            detail: 'Invalid request.',
        });
    }

    request.log.error({ err: error, requestId: request.id }, 'Unhandled request error');

    return reply.code(500).send({
        ok: false,
        detail: 'Request failed.',
    });
});

app.get('/health', async () => ({ ok: true }));

app.options('/v1/resolve', async (request, reply) => {
    const origin = request.headers.origin;

    if (allowedOrigins.length > 0 && origin) {
        reply.header('Access-Control-Allow-Origin', origin);
        reply.header('Vary', 'Origin');
    }

    reply.header('Access-Control-Allow-Methods', 'POST, OPTIONS');
    reply.header('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    return reply.code(204).send();
});

app.post('/v1/resolve', async (request, reply) => {
    const body = request.body;

    if (!body || typeof body !== 'object' || Array.isArray(body)) {
        return reply.code(400).send({
            ok: false,
            detail: 'Request body must be a JSON object.',
        });
    }

    const rawUrl = typeof body.url === 'string' ? body.url.trim() : '';

    if (!rawUrl) {
        return reply.code(400).send({
            ok: false,
            detail: 'URL is required.',
        });
    }

    let parsedUrl;

    try {
        parsedUrl = new URL(rawUrl);
    } catch {
        return reply.code(400).send({
            ok: false,
            detail: 'URL is invalid.',
        });
    }

    if (!['http:', 'https:'].includes(parsedUrl.protocol)) {
        return reply.code(400).send({
            ok: false,
            detail: 'URL must use http or https.',
        });
    }

    try {
        const fastSaverEndpoint = new URL(
            'https://api.fastsaver.io/v1/fetch',
        );

        fastSaverEndpoint.searchParams.set('url', rawUrl);

        const fastSaverResponse = await fetch(fastSaverEndpoint, {
            method: 'GET',
            headers: {
                Accept: 'application/json',
                'X-Api-Key': FASTSAVER_API_KEY,
            },
            signal: AbortSignal.timeout(FASTSAVER_TIMEOUT_MS),
        });

        const retryAfterHeader = fastSaverResponse.headers.get('retry-after');
        const responseText = await fastSaverResponse.text();
        let payload = null;

        if (responseText) {
            try {
                payload = JSON.parse(responseText);
            } catch {
                payload = null;
            }
        }

        if (fastSaverResponse.status === 401 || fastSaverResponse.status === 403) {
            if (retryAfterHeader) {
                reply.header('Retry-After', retryAfterHeader);
            }

            return reply.code(fastSaverResponse.status).send({
                ok: false,
                detail: 'Media resolver authentication failed.',
            });
        }

        if (fastSaverResponse.status === 429) {
            if (retryAfterHeader) {
                reply.header('Retry-After', retryAfterHeader);
            }

            return reply.code(429).send({
                ok: false,
                detail: 'Media resolver rate limit reached.',
            });
        }

        if (fastSaverResponse.status >= 500) {
            if (retryAfterHeader) {
                reply.header('Retry-After', retryAfterHeader);
            }

            return reply.code(502).send({
                ok: false,
                detail: 'Media resolver temporarily unavailable.',
            });
        }

        if (!fastSaverResponse.ok) {
            request.log.warn(
                {
                    requestId: request.id,
                    fastSaverStatus: fastSaverResponse.status,
                    fastSaverResponse: payload,
                },
                'FastSaver returned an error',
            );

            if (retryAfterHeader) {
                reply.header('Retry-After', retryAfterHeader);
            }

            return reply
                .code(
                    fastSaverResponse.status >= 400
                        ? fastSaverResponse.status
                        : 502,
                )
                .send({
                    ok: false,
                    detail:
                        typeof payload?.detail === 'string'
                            ? payload.detail
                            : typeof payload?.message === 'string'
                                ? payload.message
                                : 'Media resolver request failed.',
                });
        }

        if (!payload || typeof payload !== 'object') {
            return reply.code(502).send({
                ok: false,
                detail: 'Media resolver temporarily unavailable.',
            });
        }

        return reply.code(200).send(payload);
    } catch (error) {
        request.log.warn({ err: error, requestId: request.id }, 'FastSaver request failed');
        return reply.code(502).send({
            ok: false,
            detail: 'Media resolver temporarily unavailable.',
        });
    }
});

try {
    await app.listen({ port: PORT, host: '0.0.0.0' });
    app.log.info(`Status Hub API listening on 0.0.0.0:${PORT}`);
} catch (error) {
    app.log.error(error, 'Failed to start server');
    process.exit(1);
}
