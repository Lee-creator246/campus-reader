import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { PushRegistry, endpointURL, vapidAuthorization } from '../server/push.mjs';

const origin = 'https://reader.example';
const input = {endpoint:'https://web.push.apple.com/example_123', token:'a'.repeat(64)};
const wrong = {...input, token:'b'.repeat(64)};
const fails = (code) => (err) => err.code === code;

test('outgoing destinations and VAPID assertions are scoped to the push service', () => {
    assert.equal(endpointURL(input.endpoint).origin, 'https://web.push.apple.com');
    for (const target of ['http://web.push.apple.com/example_123', input.endpoint + '?to=localhost',
        'https://web.push.apple.com:444/example_123', 'https://user@web.push.apple.com/example_123',
        'https://web.push.apple.com.evil.example/example_123', 'https://127.0.0.1/example_123',
        'https://fcm.googleapis.com/other/example_123']) {
        assert.throws(() => endpointURL(target), fails('invalid_push_endpoint'));
    }
    const {privateKey, publicKey} = generateKeyPairSync('ec', {namedCurve:'prime256v1'});
    const now = Date.now();
    const header = vapidAuthorization(privateKey.export({format:'jwk'}), 'https://web.push.apple.com', origin, now);
    const [,jwt,point] = /^vapid t=([^,]+), k=(\S+)$/.exec(header);
    const [h,p,s] = jwt.split('.');
    assert.equal(Buffer.from(point,'base64url').length, 65);
    assert.equal(JSON.parse(Buffer.from(h,'base64url')).alg,'ES256');
    assert.deepEqual(JSON.parse(Buffer.from(p,'base64url')), {aud:'https://web.push.apple.com', sub:origin, exp:Math.floor(now/1000)+3600});
    assert.ok(verify('sha256',Buffer.from(h+'.'+p),{key:publicKey,dsaEncoding:'ieee-p1363'},Buffer.from(s,'base64url')));
});

test('device management is private and only gateway-accepted tests return success', async t => {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'reader-push-'));
    t.after(() => rm(dir, {recursive:true,force:true}));
    let now = Date.now(), status = 503, calls = 0;
    const registry = await PushRegistry.open(dir, origin, async () => { calls++; return {status}; }, () => now);
    assert.equal(await registry.subscribe(input), true);
    assert.equal(await registry.subscribe(input), false);
    await assert.rejects(registry.subscribe(wrong), fails('endpoint_already_managed'));
    await assert.rejects(registry.unsubscribe(wrong), fails('invalid_management_token'));
    await assert.rejects(registry.test(wrong), fails('invalid_management_token'));
    assert.equal(calls, 0);
    await assert.rejects(registry.test(input), fails('push_service_rejected'));
    await assert.rejects(registry.test(input), fails('push_rate_limit'));
    now+=60_001; status=201;
    assert.deepEqual(await registry.test(input), {accepted:true});
    now+=60_001; status=410;
    await assert.rejects(registry.test(input), fails('subscription_expired'));
    await assert.rejects(registry.test(input), fails('subscription_expired'));
    assert.equal(calls, 3);
    assert.equal(await registry.subscribe(wrong), true);
    await registry.unsubscribe(wrong);
    await registry.unsubscribe(wrong);
});

test('public pending retries only failed devices after restart and honors Retry-After', async t => {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'reader-push-'));
    t.after(() => rm(dir, {recursive:true,force:true}));
    let now = Date.now(), status = 503;
    const calls = [];
    const deliver = async (endpoint, _key, _origin, _time, topic) => {
        calls.push(endpoint);
        assert.equal(topic,'campus-public-updates');
        return {status: endpoint===input.endpoint ? status : 201, retryAfter:status===429 ? '120' : null};
    };
    const second = {endpoint:'https://web.push.apple.com/example_456',token:'c'.repeat(64)};
    const late = {endpoint:'https://web.push.apple.com/example_late',token:'f'.repeat(64)};
    const registry = await PushRegistry.open(dir, origin, deliver, () => now);
    const key = registry.config.publicKey;
    await registry.subscribe(input);
    await registry.subscribe(second);
    await registry.scan([{id:'baseline'}]);
    assert.equal(calls.length,0);
    const notices = [{id:'baseline'},{id:'new-1'},{id:'new-2'}];
    const batches = await Promise.all([registry.scan(notices), registry.scan(notices)]);
    assert.equal(calls.length,2);
    assert.equal(batches[0].temporary+batches[1].temporary,1);
    const reopen = await PushRegistry.open(dir, origin, deliver, () => now);
    assert.equal(reopen.config.publicKey, key);
    await reopen.subscribe(late);
    await reopen.scan(notices); // Still in backoff.
    assert.equal(calls.length,2);
    now+=61_000; status=429;
    await reopen.scan(notices);
    assert.deepEqual(calls,[input.endpoint,second.endpoint,input.endpoint]);
    now+=121_000; status=201;
    await reopen.scan(notices); // Second backoff is 5 minutes.
    assert.equal(calls.length,3);
    now+=180_000;
    const recovered=await reopen.scan(notices);
    assert.equal(recovered.accepted,1);
    await reopen.scan(notices);
    assert.deepEqual(calls,[input.endpoint,second.endpoint,input.endpoint,input.endpoint]);
    now+=120_001;
    await reopen.scan([{id:'next'}]);
    assert.equal(calls.length,7);
});

test('unsubscribing, expiry and gateway throttling discard or defer pending attempts', async t => {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'reader-push-'));
    t.after(() => rm(dir, {recursive:true,force:true}));
    let now = Date.now(), calls = 0;
    const registry = await PushRegistry.open(dir, origin, async () => { calls++;return {status:429,retryAfter:'3600'}; }, () => now);
    await registry.subscribe(input);
    await registry.scan([]);
    await registry.scan([{id:'first'}]);
    now+=180_000;
    await registry.scan([{id:'first'},{id:'second'}]); // Multiple unread pushes coalesce while throttled.
    assert.equal(calls,1);
    now+=3_600_001;
    await registry.scan([{id:'first'},{id:'second'}]);
    assert.equal(calls,2);
    await registry.unsubscribe(input);
    await registry.subscribe(wrong);
    now+=3_600_001;
    await registry.scan([{id:'first'},{id:'second'}]);
    assert.equal(calls,2);
    await registry.scan([{id:'third'}]);
    assert.equal(calls,3);
    now+=DAY_FOR_TEST;
    await registry.scan([{id:'third'}]);
    assert.equal(calls,3);
});
const DAY_FOR_TEST=24*3_600_000;
