import http from 'node:http';
import { constants } from 'node:fs';
import { mkdir, open } from 'node:fs/promises';
import path from 'node:path';

const origin = new URL(process.env.PUBLIC_ORIGIN).origin;
const state = process.env.STATE_DIR || '/state';
const feedFile = process.env.PUBLIC_FEED || '/data/public-notices.json';
const port = Number(process.env.PORT || 8080);
const feedbackFile = path.join(state, 'feedback.ndjson');
const maxFeed = 512 * 1024;
const byteLimit = 4096;
const monthFeedbackLimit = 500;
const dayFeedbackLimit = 100;

function fail(status, code) {
    return Object.assign(new Error(code), { status, code });
}

function send(res, status, value) {
    const body = JSON.stringify(value);
    res.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body),
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
    });
    res.end(body);
}

function plainString(value, limit) {
    return typeof value === 'string' && value.trim().length > 0 && value.length <= limit;
}

function feedNotice(item, seen) {
    if (!item || !plainString(item.id, 160) || /[\u0000-\u001f]/.test(item.id) || seen.has(item.id.trim()) ||
        !plainString(item.title, 400) || !plainString(item.body, 60_000) ||
        !plainString(item.publishedAt, 40) ||
        !/^\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2}))?$/.test(item.publishedAt) ||
        !Number.isFinite(Date.parse(item.publishedAt))) {
        throw fail(503, 'invalid_public_feed');
    }
    const id = item.id.trim();
    seen.add(id);
    const result = { id, title: item.title.trim(), body: item.body, publishedAt: item.publishedAt };
    if (item.url) {
        let url;
        try { url = new URL(item.url); } catch { throw fail(503, 'invalid_public_feed'); }
        if (!['https:', 'http:'].includes(url.protocol) || url.hostname !== 'my.bupt.edu.cn' ||
            url.username || url.password || url.port || url.hash ||
            Array.from(url.searchParams.keys()).some(key => /ticket|token|session|auth|key|password/i.test(key))) {
            throw fail(503, 'invalid_public_feed');
        }
        result.url = url.href;
    }
    return result;
}

async function readPublicFeed() {
    let file;
    try {
        file = await open(feedFile, constants.O_RDONLY | (constants.O_NOFOLLOW || 0));
        const stat = await file.stat();
        if (!stat.isFile() || stat.size > maxFeed) throw fail(503, 'invalid_public_feed');
        const buffer = Buffer.alloc(stat.size);
        const { bytesRead } = await file.read(buffer, 0, buffer.length, 0);
        if (bytesRead !== stat.size) throw fail(503, 'invalid_public_feed');
        const feed = JSON.parse(buffer.toString('utf8'));
        if (feed.visibility !== 'public' || !Array.isArray(feed.notices) || feed.notices.length > 200) {
            throw fail(503, 'invalid_public_feed');
        }
        const seen = new Set();
        const notices = feed.notices.map(item => feedNotice(item, seen));
        notices.sort((a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt));
        return { source: 'public', notices };
    } catch (err) {
        if (err.code === 'ENOENT') throw fail(503, 'public_feed_not_configured');
        throw fail(503, 'invalid_public_feed');
    } finally {
        if (file) await file.close();
    }
}

async function parseFeedback(req) {
    if (req.headers.origin !== origin) throw fail(403, 'wrong_origin');
    if (req.headers['content-type']?.split(';')[0].trim().toLowerCase() !== 'application/json') {
        throw fail(415, 'json_required');
    }
    if (Number(req.headers['content-length']) > byteLimit) throw fail(413, 'feedback_too_large');
    let size = 0;
    const chunks = [];
    for await (const chunk of req) {
        size += chunk.length;
        if (size > byteLimit) throw fail(413, 'feedback_too_large');
        chunks.push(chunk);
    }
    let input;
    try { input = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
    catch { throw fail(400, 'invalid_feedback'); }
    if (!input || !plainString(input.message, 600) || !plainString(input.version, 32) ||
        !plainString(input.commit, 40) || !/^[a-f\d]{7,40}$/i.test(input.commit) ||
        Object.keys(input).some(k => !['message', 'version', 'commit'].includes(k))) {
        throw fail(400, 'invalid_feedback');
    }
    return { date: new Date().toISOString(), message: input.message.trim(), version: input.version, commit: input.commit };
}

let writes = Promise.resolve();
let queued = 0;
function saveFeedback(record) {
    if (queued >= 8) throw fail(429, 'feedback_busy');
    queued++;
    const next = writes.then(async () => {
        await mkdir(state, { recursive: true, mode: 0o700 });
        const file = await open(feedbackFile, 'a+', 0o600);
        try {
            const stat = await file.stat();
            if (stat.size >= 4 * 1024 * 1024) throw fail(429, 'feedback_store_full');
            const previous = (await file.readFile('utf8')).split('\n').filter(Boolean);
            const day = record.date.slice(0, 10), month = record.date.slice(0, 7);
            const dates = previous.map(line => JSON.parse(line).date);
            if (dates.filter(d => d.startsWith(day)).length >= dayFeedbackLimit ||
                dates.filter(d => d.startsWith(month)).length >= monthFeedbackLimit) throw fail(429, 'feedback_limit');
            await file.appendFile(JSON.stringify(record) + '\n', 'utf8');
            await file.sync();
        } finally { await file.close(); }
    });
    writes = next.catch(() => {}).finally(() => { queued--; });
    return next;
}

const api = http.createServer({
    requestTimeout: 15_000, headersTimeout: 10_000, maxHeaderSize: 8192,
}, async (req, res) => {
    try {
        if (req.method === 'GET' && req.url === '/api/notices') return send(res, 200, await readPublicFeed());
        if (req.method === 'POST' && req.url === '/api/feedback') {
            await saveFeedback(await parseFeedback(req));
            return send(res, 201, { ok: true });
        }
        throw fail(404, 'not_found');
    } catch (err) {
        send(res, err.status || 500, { error: err.status ? err.code : 'internal_error' });
    }
});
api.maxConnections = 24;
api.listen(port, process.env.BIND_HOST || '0.0.0.0', () => console.log('Reader API listening on', api.address().port));
