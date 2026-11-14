import { spawn } from 'node:child_process';
import { readdir, stat } from 'node:fs/promises';
import path from 'node:path';

export class ApiError extends Error {
    constructor(code, message, status = 422) { super(message); this.code = code; this.status = status; }
}

export function publicSource(value) {
    let url;
    try { url = new URL(value); } catch { throw new ApiError('INVALID_URL', 'Enter a valid media link.', 400); }
    if (url.protocol !== 'https:' || url.username || url.password || url.port) {
        throw new ApiError('INVALID_URL', 'Use a public HTTPS media link without credentials or a custom port.', 400);
    }
    const hosts = {
        'youtube.com': 'youtube', 'www.youtube.com': 'youtube', 'm.youtube.com': 'youtube', 'youtu.be': 'youtube',
        'instagram.com': 'instagram', 'www.instagram.com': 'instagram',
        'facebook.com': 'facebook', 'www.facebook.com': 'facebook', 'm.facebook.com': 'facebook',
        'web.facebook.com': 'facebook', 'fb.watch': 'facebook', 'fb.com': 'facebook', 'www.fb.com': 'facebook',
    };
    const platform = hosts[url.hostname];
    if (!platform) throw new ApiError('UNSUPPORTED_SOURCE', 'Supported sources: YouTube, Instagram and Facebook.', 400);
    if (platform === 'youtube' && !(url.hostname === 'youtu.be' || /^\/(watch$|shorts\/|live\/)/.test(url.pathname))) {
        throw new ApiError('UNSUPPORTED_URL', 'Paste an individual video link.', 400);
    }
    if (platform === 'instagram' && !/^\/(p|reel|reels|stories)\//.test(url.pathname)) {
        throw new ApiError('UNSUPPORTED_URL', 'Paste a post, reel or story link.', 400);
    }
    url.hash = '';
    for (const key of [...url.searchParams.keys()]) {
        if (!['v', 'story_fbid', 'id'].includes(key)) url.searchParams.delete(key);
    }
    return { url: url.href, platform };
}

export function extractorError(stderr) {
    // Bot challenges can contain both "sign in" and cookie advice for public videos.
    if (/not a bot|confirm you.re|automated requests|unusual traffic|captcha/i.test(stderr)) return new ApiError('UPSTREAM_BOT_CHECK', 'The source is blocking requests from our media server. This does not necessarily mean the video is private. Try again later.', 503);
    if (/429|rate.limit|too many/i.test(stderr)) return new ApiError('UPSTREAM_RATE_LIMIT', 'The source is limiting requests. Try again later.', 503);
    if (/sign in to confirm your age|age.restricted/i.test(stderr)) return new ApiError('AGE_RESTRICTED', 'This media requires age verification and is not supported.');
    if (/login|log in|sign in|private|authentication/i.test(stderr)) return new ApiError('AUTH_REQUIRED', 'This content requires login. Only publicly accessible media is supported.');
    if (/unavailable|removed|not available|expired|404/i.test(stderr)) return new ApiError('UNAVAILABLE', 'This media is unavailable, removed or expired.');
    return new ApiError('EXTRACTION_FAILED', 'The source could not be read. Try again later or use another public link.', 502);
}

export function runExtractor(args, { timeout = 60000, maxOutput = 8 * 1024 * 1024, directory, maxDiskBytes } = {}) {
    return new Promise((resolve, reject) => {
        const child = spawn(process.env.YTDLP_PATH || 'yt-dlp', [
            '--ignore-config', '--use-extractors', 'youtube.*,instagram.*,facebook.*', '--no-cookies', '--no-cache-dir', '--no-playlist',
            '--socket-timeout', '20', '--retries', '2', '--no-warnings', '--js-runtimes', 'node', ...args,
        ], { shell: false, windowsHide: true, detached: process.platform !== 'win32' });
        const stop = () => { try { if (process.platform !== 'win32') process.kill(-child.pid, 'SIGKILL'); else child.kill(); } catch {} };
        let stdout = '', stderr = '', size = 0, failure;
        const timer = setTimeout(() => { failure = new ApiError('TIMEOUT', 'Media preparation timed out.', 504); stop(); }, timeout);
        const diskTimer = directory ? setInterval(() => {
            void (async () => {
                let bytes = 0;
                for (const file of await readdir(directory)) bytes += (await stat(path.join(directory, file))).size;
                if (bytes > maxDiskBytes) { failure = new ApiError('TOO_LARGE', 'Download exceeds the storage limit.', 413); stop(); }
            })().catch(() => {});
        }, 500) : null;
        const cleanup = () => { clearTimeout(timer); if (diskTimer) clearInterval(diskTimer); };
        child.stdout.on('data', chunk => {
            size += chunk.length;
            if (size > maxOutput) { failure = new ApiError('TOO_LARGE', 'The source returned too much metadata.'); stop(); }
            else stdout += chunk;
        });
        child.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-8192); });
        child.on('error', () => { cleanup(); reject(new ApiError('RESOLVER_UNAVAILABLE', 'The media worker is not configured.', 503)); });
        child.on('close', code => { cleanup(); if (failure) reject(failure); else if (code !== 0) reject(extractorError(stderr)); else resolve(stdout); });
    });
}

export function normalize(info, source) {
    const entries = info.entries ? info.entries.filter(Boolean) : [info];
    if (entries.length > 30) throw new ApiError('TOO_MANY_ITEMS', 'Use a link to an individual media item.');
    const items = entries.map((entry, index) => {
        if (entry.is_live || entry.has_drm) throw new ApiError('UNSUPPORTED_MEDIA', 'Live or protected media is not supported.');
        const candidates = (entry.formats || (entry.url ? [entry] : [])).filter(f => f.url && !f.has_drm && /^[\w.-]+$/.test(String(f.format_id || 'original')));
        const audio = candidates.filter(f => f.vcodec === 'none' && f.acodec && f.acodec !== 'none');
        const bestAudio = audio.slice().sort((a, b) => (b.abr || b.tbr || 0) - (a.abr || a.tbr || 0))[0];
        const formats = candidates.flatMap(f => {
            const image = /^(jpg|jpeg|png|webp)$/.test(f.ext || '');
            const isAudio = f.vcodec === 'none' && f.acodec && f.acodec !== 'none';
            if (!image && f.vcodec === 'none' && !isAudio) return [];
            const separate = !image && !isAudio && f.acodec === 'none';
            if (separate && !bestAudio) return [];
            const id = String(f.format_id || 'original');
            const ext = separate ? ((f.ext === 'mp4' && bestAudio.ext === 'm4a') ? 'mp4' : 'mkv') : f.ext;
            if (!/^[a-z0-9]{2,5}$/.test(ext || '')) return [];
            const bitrate = f.abr || (isAudio ? f.tbr : null);
            return [{ id, selector: separate ? `${id}+${bestAudio.format_id}` : (id === 'original' ? 'best' : id),
                extension: ext, mediaType: image ? 'image' : isAudio ? 'audio' : 'video',
                quality: image ? 'Original image' : isAudio ? (bitrate ? `${Math.round(bitrate)} kbps` : 'Original audio') : `${f.height || '?'}p${f.fps ? ` · ${f.fps} fps` : ''}`,
                codec: isAudio ? f.acodec : f.vcodec, bitrateKbps: bitrate || null,
                language: f.language || null, size: separate ? null : (f.filesize || null),
                note: separate ? 'Video and audio merged without re-encoding' : 'Original stream',
            }];
        });
        formats.sort((a, b) => a.mediaType.localeCompare(b.mediaType) || (b.bitrateKbps || 0) - (a.bitrateKbps || 0));
        return { id: String(entry.id || index), title: entry.title || info.title || 'Media',
            sourceUrl: entry.webpage_url || source.url, platform: source.platform,
            playlistIndex: (!entry.webpage_url || entry.webpage_url === source.url) ? index + 1 : 1,
            thumbnailUrl: entry.thumbnail || null, formats };
    }).filter(item => item.formats.length);
    if (!items.length) throw new ApiError('NO_FORMATS', 'No downloadable public media was found.');
    return items;
}
