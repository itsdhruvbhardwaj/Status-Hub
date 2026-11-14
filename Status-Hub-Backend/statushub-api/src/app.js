import Fastify from 'fastify';
import { randomBytes } from 'node:crypto';
import { mkdir, readdir, stat, rm } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import path from 'node:path';
import { ApiError, publicSource, normalize, runExtractor } from './resolver.js';

const token = () => randomBytes(24).toString('hex');
const mime = (ext, type) => ({ mp4: type === 'audio' ? 'audio/mp4' : 'video/mp4', m4a: 'audio/mp4', webm: `${type}/webm`, mkv: 'video/x-matroska', mp3: 'audio/mpeg', opus: 'audio/ogg', ogg: 'audio/ogg', jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp' }[ext] || 'application/octet-stream');

export async function buildApp({ extract = runExtractor, storage = process.env.MEDIA_DIR || './media-cache', logger = true } = {}) {
    const app = Fastify({ logger, disableRequestLogging: true, bodyLimit: 4096, trustProxy: false });
    const root = path.resolve(storage);
    await mkdir(root, { recursive: true });
    const resolutions = new Map(), jobs = new Map(), limits = new Map();
    const allowed = (process.env.ALLOWED_ORIGINS || '').split(',').map(x => x.trim()).filter(Boolean);
    const ttl = 30 * 60 * 1000;
    const maxBytes = Number(process.env.MAX_MEDIA_BYTES) || 256 * 1024 * 1024;
    let workers = 0;
    const maxWorkers = Number(process.env.MAX_WORKERS) || 2;
    const pending = new Set();
    const prune = setInterval(() => { void (async () => {
        const now = Date.now();
        for (const [id, r] of resolutions) if (r.expiresAt < now) resolutions.delete(id);
        for (const [id, j] of jobs) if (j.expiresAt < now && j.status !== 'processing') { jobs.delete(id); await rm(path.join(root, id), { recursive: true, force: true }); }
        for (const [ip, b] of limits) if (b.until < now) limits.delete(ip);
        for (const name of await readdir(root)) {
            if (!/^[a-f0-9]{48}$/.test(name) || jobs.has(name)) continue;
            const dir = path.join(root, name);
            if ((await stat(dir)).mtimeMs + ttl < now) await rm(dir, { recursive: true, force: true });
        }
    })().catch(() => {}); }, 60000);
    prune.unref();
    app.addHook('onClose', async () => { clearInterval(prune); await Promise.allSettled(pending); });
    app.addHook('onRequest', async (req, reply) => {
        reply.header('Cache-Control', 'no-store').header('X-Content-Type-Options', 'nosniff');
        const origin = req.headers.origin;
        if (origin && !allowed.includes(origin)) throw new ApiError('ORIGIN_DENIED', 'Website origin is not allowed.', 403);
        if (origin) reply.header('Access-Control-Allow-Origin', origin).header('Vary', 'Origin').header('Access-Control-Expose-Headers', 'Content-Length, Content-Range, Content-Disposition, Retry-After');
        if (req.method === 'OPTIONS') return reply.header('Access-Control-Allow-Methods', 'GET, HEAD, POST, OPTIONS').header('Access-Control-Allow-Headers', 'Content-Type, Range').code(204).send();
        if (req.method === 'POST') {
            const now = Date.now(), key = req.ip;
            let b = limits.get(key);
            if (!b || b.until < now) { b = { count: 0, until: now + 60000 }; limits.set(key, b); }
            if (++b.count > 20) { reply.header('Retry-After', '60'); throw new ApiError('RATE_LIMITED', 'Too many requests. Try again shortly.', 429); }
            if (limits.size > 10000) throw new ApiError('BUSY', 'Service is busy.', 503);
        }
    });
    app.setErrorHandler((e, req, reply) => {
        const status = e.status || (e.statusCode >= 400 && e.statusCode < 500 ? e.statusCode : 500);
        reply.code(status).send({ ok: false, error: { code: e instanceof ApiError ? e.code : status < 500 ? 'INVALID_REQUEST' : 'INTERNAL_ERROR', message: e instanceof ApiError ? e.message : status < 500 ? 'Invalid request.' : 'The request could not be completed.' }, requestId: req.id });
    });
    const slot = () => { if (workers >= maxWorkers) throw new ApiError('BUSY', 'All media workers are busy. Try again shortly.', 503); workers++; };
    app.post('/v1/resolve', async (req, reply) => reply.code(410).send({ ok: false, detail: 'Update StatusHub to use the new public media service.' }));
    app.get('/health', async () => ({ ok: true, apiVersion: 2 }));
    app.get('/v2/capabilities', async () => ({ ok: true, publicOnly: true, platforms: ['youtube', 'instagram', 'facebook'], maxMediaBytes: maxBytes, delivery: 'prepare-job-then-download', privateMedia: false }));
    app.post('/v2/resolve', async req => {
        if (typeof req.body?.url !== 'string' || req.body.url.length > 2048) throw new ApiError('INVALID_URL', 'Enter a media URL.', 400);
        const source = publicSource(req.body.url);
        if (resolutions.size >= 500) throw new ApiError('BUSY', 'Service is busy.', 503);
        slot();
        try {
            const raw = await extract(['--dump-single-json', '--skip-download', '--playlist-end', '30', '--', source.url]);
            const items = normalize(JSON.parse(raw), source);
            for (const item of items) item.sourceUrl = publicSource(item.sourceUrl).url;
            const id = token(), expiresAt = Date.now() + ttl;
            resolutions.set(id, { source, items, expiresAt });
            return { ok: true, apiVersion: 2, resolutionId: id, sourceUrl: source.url, expiresAt,
                items: items.map(item => ({ ...item, formats: item.formats.map(({ selector, ...format }) => format) })) };
        } finally { workers--; }
    });
    app.post('/v2/jobs', async (req, reply) => {
        const r = resolutions.get(req.body?.resolutionId);
        if (!r || r.expiresAt < Date.now()) throw new ApiError('RESOLUTION_EXPIRED', 'Analyze the original link again.', 410);
        const item = r.items.find(x => x.id === req.body.itemId), format = item?.formats.find(x => x.id === req.body.formatId);
        if (!format) throw new ApiError('INVALID_FORMAT', 'Choose an available format.', 400);
        if (format.size > maxBytes) throw new ApiError('TOO_LARGE', 'This file exceeds the download limit.', 413);
        if (jobs.size >= 50) throw new ApiError('BUSY', 'Download storage is busy. Try again later.', 503);
        slot();
        const id = token(), dir = path.join(root, id);
        const job = { status: 'processing', expiresAt: Date.now() + ttl, format, title: item.title };
        jobs.set(id, job);
        const work = (async () => {
            try {
                await mkdir(dir);
                await extract(['--no-progress', '--no-simulate', '--max-filesize', String(maxBytes), '--playlist-items', String(item.playlistIndex),
                    '-f', format.selector, ...(format.selector.includes('+') ? ['--merge-output-format', format.extension] : []),
                    '-o', path.join(dir, 'media.%(ext)s'), '--', item.sourceUrl], { timeout: 180000, directory: dir, maxDiskBytes: maxBytes * 2 });
                const names = (await readdir(dir)).filter(x => /^media\.[a-z0-9]+$/.test(x) && !x.endsWith('.part'));
                if (names.length !== 1) throw new ApiError('PREPARATION_FAILED', 'The media file could not be prepared.');
                const file = path.join(dir, names[0]), size = (await stat(file)).size;
                if (!size || size > maxBytes) throw new ApiError('TOO_LARGE', 'The media exceeds the download limit.', 413);
                Object.assign(job, { status: 'ready', file, size, extension: names[0].split('.').pop(), expiresAt: Date.now() + ttl });
            } catch (e) {
                Object.assign(job, { status: 'failed', error: { code: e instanceof ApiError ? e.code : 'PREPARATION_FAILED', message: e instanceof ApiError ? e.message : 'Media preparation failed.' } });
                await rm(dir, { recursive: true, force: true }).catch(() => {});
            } finally { workers--; }
        })();
        pending.add(work); void work.finally(() => pending.delete(work));
        return reply.code(202).send({ ok: true, jobId: id, status: job.status, statusPath: `/v2/jobs/${id}` });
    });
    function getJob(id) {
        const j = jobs.get(id);
        if (!j || j.expiresAt < Date.now()) throw new ApiError('JOB_EXPIRED', 'Download expired. Analyze the link again.', 410);
        return j;
    }
    app.get('/v2/jobs/:id', async req => {
        const j = getJob(req.params.id);
        return { ok: true, status: j.status, error: j.error, expiresAt: j.expiresAt,
            ...(j.status === 'ready' ? { downloadPath: `/v2/files/${req.params.id}`, size: j.size, extension: j.extension, mediaType: j.format.mediaType } : {}) };
    });
    app.get('/v2/files/:id', async (req, reply) => {
        const j = getJob(req.params.id);
        if (j.status !== 'ready') throw new ApiError('NOT_READY', 'Media is not ready yet.', 409);
        let start = 0, end = j.size - 1;
        reply.header('Accept-Ranges', 'bytes').type(mime(j.extension, j.format.mediaType));
        const name = j.title.replace(/[^a-zA-Z0-9 _-]/g, '').slice(0, 90) || 'StatusHub';
        reply.header('Content-Disposition', `attachment; filename="${name}.${j.extension}"`);
        if (req.headers.range) {
            const match = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range);
            if (!match) return reply.header('Content-Range', `bytes */${j.size}`).code(416).send();
            start = Number(match[1]); end = match[2] ? Math.min(Number(match[2]), end) : end;
            if (!Number.isSafeInteger(start) || start > end) return reply.header('Content-Range', `bytes */${j.size}`).code(416).send();
            reply.code(206).header('Content-Range', `bytes ${start}-${end}/${j.size}`);
        }
        reply.header('Content-Length', end - start + 1);
        return reply.send(createReadStream(j.file, { start, end }));
    });
    return app;
}
