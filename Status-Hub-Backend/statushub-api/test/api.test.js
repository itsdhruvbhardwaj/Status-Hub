import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { buildApp } from '../src/app.js';
import { publicSource, normalize, extractorError } from '../src/resolver.js';

const metadata = { id: 'demo', title: 'Demo', webpage_url: 'https://www.youtube.com/watch?v=demo', formats: [
    ...[48, 64, 128, 160, 256].map((abr, i) => ({ format_id: `a${i}`, url: 'https://media.example/audio', ext: 'webm', vcodec: 'none', acodec: 'opus', abr })),
    { format_id: 'v1', url: 'https://media.example/video', ext: 'mp4', vcodec: 'avc1', acodec: 'none', height: 1080 },
] };
test('rejects arbitrary hosts, credentials and unsafe schemes', () => {
    for (const url of ['http://127.0.0.1/', 'https://youtube.com.evil.test/watch?v=x', 'https://u:p@youtube.com/watch?v=x', 'https://youtube.com:444/watch?v=x', 'file:///etc/passwd']) assert.throws(() => publicSource(url));
    assert.equal(publicSource('https://fb.watch/example/').platform, 'facebook');
});
test('keeps every audio format and pairs video without fake bitrate upgrades', () => {
    const items = normalize(metadata, publicSource(metadata.webpage_url));
    assert.equal(items[0].formats.filter(f => f.mediaType === 'audio').length, 5);
    const video = items[0].formats.find(f => f.mediaType === 'video');
    assert.equal(video.selector, 'v1+a4');
    assert.equal(video.extension, 'mkv');
    assert.equal(extractorError('Please log in').code, 'AUTH_REQUIRED');
});
test('resolve -> prepare -> poll -> ranged file, with protected capabilities', async () => {
    const storage = await mkdtemp(path.join(os.tmpdir(), 'statushub-test-'));
    const extract = async args => {
        if (args.includes('--dump-single-json')) return JSON.stringify(metadata);
        const template = args[args.indexOf('-o') + 1];
        await writeFile(template.replace('%(ext)s', 'webm'), Buffer.from('test-media-bytes'));
        return '';
    };
    const app = await buildApp({ extract, storage, logger: false });
    try {
        assert.equal((await app.inject({ method: 'POST', url: '/v2/resolve', payload: { url: 'https://localhost/' } })).statusCode, 400);
        assert.equal((await app.inject({ method: 'GET', url: '/health', headers: { origin: 'https://evil.test' } })).statusCode, 403);
        const resolved = await app.inject({ method: 'POST', url: '/v2/resolve', payload: { url: metadata.webpage_url } });
        assert.equal(resolved.statusCode, 200);
        const r = resolved.json();
        assert.ok(!('selector' in r.items[0].formats[0]));
        assert.equal((await app.inject({ method: 'POST', url: '/v2/jobs', payload: { resolutionId: r.resolutionId, itemId: 'demo', formatId: 'evil' } })).statusCode, 400);
        const prepared = await app.inject({ method: 'POST', url: '/v2/jobs', payload: { resolutionId: r.resolutionId, itemId: 'demo', formatId: 'a4' } });
        assert.equal(prepared.statusCode, 202);
        let job;
        for (let i = 0; i < 100; i++) {
            job = (await app.inject({ method: 'GET', url: prepared.json().statusPath })).json();
            if (job.status !== 'processing') break;
            await new Promise(resolve => setTimeout(resolve, 5));
        }
        assert.equal(job.status, 'ready');
        const file = await app.inject({ method: 'GET', url: job.downloadPath, headers: { range: 'bytes=5-9' } });
        assert.equal(file.statusCode, 206);
        assert.equal(file.body, 'media');
        assert.equal(file.headers['content-range'], 'bytes 5-9/16');
        assert.equal((await app.inject({ method: 'GET', url: job.downloadPath, headers: { range: 'bytes=999-' } })).statusCode, 416);
        assert.equal((await app.inject({ method: 'GET', url: '/v2/files/missing' })).statusCode, 410);
    } finally { await app.close(); await rm(storage, { recursive: true, force: true }); }
});
