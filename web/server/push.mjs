import { createHash, createPrivateKey, generateKeyPairSync, randomUUID, sign, timingSafeEqual } from 'node:crypto';
import { mkdir, open, readFile, rename, unlink } from 'node:fs/promises';
import path from 'node:path';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const backoff = [60_000, 5 * 60_000, 30 * 60_000, 2 * HOUR];
const error = (status, code) => Object.assign(new Error(code), {status, code});
const digest = text => createHash('sha256').update(text).digest('hex');

const services = new Map([
    ['web.push.apple.com', /^\/[A-Za-z0-9_:/+=.-]{8,}$/],
    ['fcm.googleapis.com', /^\/(?:fcm\/send|wp)\/[A-Za-z0-9_:=.-]{8,}$/],
    ['updates.push.services.mozilla.com', /^\/wpush\/v[12]\/[A-Za-z0-9_:=.-]{8,}$/],
]);

export function endpointURL(value) {
    if (typeof value !== 'string' || value.length > 2048 || /[\s\\]/.test(value)) throw error(400, 'invalid_push_endpoint');
    let url;
    try { url = new URL(value); } catch { throw error(400, 'invalid_push_endpoint'); }
    if (url.protocol !== 'https:' || url.port || url.username || url.password || url.search || url.hash ||
        !services.get(url.hostname)?.test(url.pathname)) throw error(400, 'invalid_push_endpoint');
    return url;
}

function proof(input) {
    if (!input || Object.keys(input).length !== 2 || !/^[a-f0-9]{64}$/.test(input.token || '') ||
        typeof input.token !== 'string') throw error(400, 'invalid_push_request');
    return {endpoint: endpointURL(input.endpoint).href, tokenHash: digest(input.token)};
}

function matches(subscription, tokenHash) {
    return timingSafeEqual(Buffer.from(subscription.tokenHash, 'hex'), Buffer.from(tokenHash, 'hex'));
}

function makeStore() {
    const {privateKey} = generateKeyPairSync('ec', {namedCurve:'prime256v1'});
    return {schema:1, key:privateKey.export({format:'jwk'}), devices:[], registered:[], tests:[], seen:null, lastBroadcast:0};
}

async function save(file, db) {
    const temp = file + '.' + randomUUID();
    try {
        const f = await open(temp, 'wx', 0o600);
        try { await f.writeFile(JSON.stringify(db)); await f.sync(); } finally { await f.close(); }
        await rename(temp, file);
    } finally {
        await unlink(temp).catch(e => { if (e.code !== 'ENOENT') throw e; });
    }
}

export function publicKey(key) {
    return Buffer.concat([Buffer.from([4]), Buffer.from(key.x, 'base64url'), Buffer.from(key.y, 'base64url')]).toString('base64url');
}

export function vapidAuthorization(key, audience, subject, now) {
    const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
    const message = encode({typ:'JWT', alg:'ES256'}) + '.' +
        encode({aud:audience, sub:subject, exp:Math.floor(now/1000) + 3600});
    const signature = sign('sha256', Buffer.from(message), {
        key:createPrivateKey({key, format:'jwk'}), dsaEncoding:'ieee-p1363',
    }).toString('base64url');
    return 'vapid t=' + message + '.' + signature + ', k=' + publicKey(key);
}

async function deliver(endpoint, key, origin, now, topic) {
    const url = endpointURL(endpoint); // Validate again when reading persisted records.
    const response = await fetch(url, {
        method:'POST', redirect:'manual', signal:AbortSignal.timeout(8000),
        headers:{
            TTL:'3600',
            Authorization:vapidAuthorization(key, url.origin, origin, now),
            'Content-Length':'0',
            ...(topic ? {Topic:topic} : {}),
        },
    });
    await response.body?.cancel(); // Provider replies may contain private identifiers.
    return {status:response.status, retryAfter:response.headers.get('Retry-After')};
}

function retryAfterMs(header, now) {
    if (!header) return 0;
    const delay = /^\d+$/.test(header) ? Number(header)*1000 : Date.parse(header) - now;
    return Number.isFinite(delay) ? Math.max(0, Math.min(delay, DAY)) : 0;
}

// One API process owns this private state directory. Reserve attempts on disk before sending.
export class PushRegistry {
    static async open(directory, origin, transport = deliver, clock = Date.now) {
        await mkdir(directory, {recursive:true, mode:0o700});
        const file = path.join(directory, 'push-state.json');
        let db;
        try { db = JSON.parse(await readFile(file, 'utf8')); }
        catch (err) {
            if (err.code !== 'ENOENT') throw err;
            db = makeStore();
            await save(file, db);
        }
        if (db.schema !== 1 || !db.key?.d || !Array.isArray(db.devices) || !Array.isArray(db.registered) ||
            !Array.isArray(db.tests) || !(db.seen === null || Array.isArray(db.seen))) throw error(503, 'push_unavailable');
        return new PushRegistry(file, db, origin, transport, clock);
    }

    constructor(file, db, origin, transport, clock) {
        Object.assign(this, {file, db, origin, transport, clock});
        this.queue = Promise.resolve();
        this.pending = 0;
    }

    get config() { return {enabled:true, publicKey:publicKey(this.db.key)}; }

    change(action) {
        if (this.pending >= 32) throw error(429, 'push_rate_limit');
        this.pending++;
        const operation = this.queue.then(async () => {
            const draft = structuredClone(this.db);
            const {value, changed} = action(draft, this.clock());
            if (changed) {
                await save(this.file, draft);
                this.db = draft;
            }
            return value;
        });
        this.queue = operation.catch(() => {}).finally(() => this.pending--);
        return operation;
    }

    subscribe(input) {
        const {endpoint, tokenHash} = proof(input);
        return this.change((db, now) => {
            const found = db.devices.find(d => d.endpoint === endpoint);
            if (found) {
                if (!matches(found, tokenHash)) throw error(409, 'endpoint_already_managed');
                return {value:false};
            }
            if (db.devices.length >= 64) throw error(503, 'subscription_capacity');
            db.registered = db.registered.filter(t => now - t < DAY);
            if (db.registered.length >= 32 || db.registered.filter(t => now - t < HOUR).length >= 16) throw error(429, 'push_rate_limit');
            db.devices.push({endpoint, tokenHash, tests:[]});
            db.registered.push(now);
            return {changed:true, value:true};
        });
    }

    unsubscribe(input) {
        const {endpoint, tokenHash} = proof(input);
        return this.change(db => {
            const found = db.devices.find(d => d.endpoint === endpoint);
            if (!found) return {};
            if (!matches(found, tokenHash)) throw error(403, 'invalid_management_token');
            db.devices = db.devices.filter(d => d !== found);
            return {changed:true};
        });
    }

    async send(device, topic) {
        const current = this.db.devices.find(d => d.endpoint === device.endpoint && matches(d, device.tokenHash));
        if (!current) return {kind:'expired'};
        let reply;
        try { reply = await this.transport(current.endpoint, this.db.key, this.origin, this.clock(), topic); }
        catch { return {kind:'temporary', retryAfter:0}; }
        const status = reply.status;
        if (status === 404 || status === 410) {
            await this.change(db => {
                const previous = db.devices.length;
                db.devices = db.devices.filter(d => d.endpoint !== device.endpoint || !matches(d, device.tokenHash));
                return {changed:db.devices.length !== previous};
            });
            return {kind:'expired'};
        }
        if (status >= 200 && status < 300) return {kind:'accepted'};
        if ([408, 425, 429].includes(status) || status >= 500) {
            return {kind:'temporary', retryAfter:retryAfterMs(reply.retryAfter, this.clock())};
        }
        return {kind:'rejected'};
    }

    async test(input) {
        const {endpoint, tokenHash} = proof(input);
        const device = await this.change((db, now) => {
            const found = db.devices.find(d => d.endpoint === endpoint);
            if (!found) throw error(410, 'subscription_expired');
            if (!matches(found, tokenHash)) throw error(403, 'invalid_management_token');
            db.tests = db.tests.filter(t => now - t < HOUR);
            found.tests = found.tests.filter(t => now - t < DAY);
            if (db.tests.length >= 32 || found.tests.length >= 4 || found.tests.some(t => now-t < 60_000)) throw error(429, 'push_rate_limit');
            db.tests.push(now);
            found.tests.push(now);
            return {changed:true, value:{endpoint, tokenHash}};
        });
        const result = await this.send(device);
        if (result.kind === 'expired') throw error(410, 'subscription_expired');
        if (result.kind !== 'accepted') throw error(502, 'push_service_rejected');
        return {accepted:true};
    }

    claim(ids) {
        return this.change((db, now) => {
            if (db.seen === null) {
                db.seen = [...new Set(ids)]; // Existing feed is the baseline on first activation.
                return {changed:true, value:[]};
            }
            const seen = new Set(db.seen);
            const added = ids.filter(id => !seen.has(id));
            let changed = false;
            if (added.length && now - db.lastBroadcast >= 120_000) {
                if (seen.size + added.length > 20_000) throw error(503, 'push_ledger_full');
                db.seen.push(...added);
                db.lastBroadcast = now;
                for (const device of db.devices) {
                    // A new batch supersedes any older pending generic reminder. Respect prior throttling.
                    device.pending = {id:randomUUID(), attempts:0, until:now+DAY, nextAt:Math.max(now, device.pending?.nextAt || 0)};
                }
                changed = true;
            }
            const targets = [];
            for (const device of db.devices) {
                const job = device.pending;
                if (!job) continue;
                if (job.until <= now || (job.attempts > backoff.length && job.nextAt <= now)) {
                    delete device.pending;
                    changed = true;
                } else if (job.nextAt <= now) {
                    job.attempts++;
                    job.nextAt = now + (backoff[job.attempts-1] ?? backoff.at(-1));
                    targets.push({endpoint:device.endpoint, tokenHash:device.tokenHash, job:job.id, attempt:job.attempts});
                    changed = true;
                }
            }
            return {changed, value:targets};
        });
    }

    async finish(target, result) {
        return this.change((db, now) => {
            const device = db.devices.find(d => d.endpoint === target.endpoint && matches(d, target.tokenHash));
            const job = device?.pending;
            if (!job || job.id !== target.job || job.attempts !== target.attempt) return {};
            if (result.kind === 'temporary' && job.attempts <= backoff.length && job.nextAt < job.until) {
                if (result.retryAfter) job.nextAt = Math.max(job.nextAt, now+result.retryAfter);
            } else {
                delete device.pending;
            }
            return {changed:true};
        });
    }

    async scan(notices) {
        const targets = await this.claim([...new Set(notices.map(n => digest(n.id)))]);
        const counts = {accepted:0, rejected:0, expired:0, temporary:0};
        for (let i = 0; i < targets.length; i += 4) {
            await Promise.all(targets.slice(i, i + 4).map(async target => {
                const result = await this.send(target, 'campus-public-updates');
                await this.finish(target, result);
                counts[result.kind]++;
            }));
        }
        return counts;
    }
}
