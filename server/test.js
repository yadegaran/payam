'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const WebSocket = require('ws');
const { start, usernameOf } = require('./server');

let clock = 1_000_000;
const srv = start({ port: 0, dbPath: ':memory:', maxRamMb: 1, now: () => clock });
const base = () => `127.0.0.1:${srv.server.address().port}`;
test.after(() => srv.close());

function identity() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
  const ed = Buffer.from(publicKey.export({ format: 'jwk' }).x, 'base64url');
  const curve = crypto.randomBytes(32); // server never inspects curve25519 keys
  return { ed, curve, privateKey, user: usernameOf(ed, curve) };
}

// Minimal client: collects frames, lets tests await the next one of a type.
async function connect(id, { badSig = false } = {}) {
  await new Promise((r) => (srv.server.listening ? r() : srv.server.once('listening', r)));
  const ws = new WebSocket(`ws://${base()}/ws`);
  const inbox = [];
  const waiters = [];
  ws.on('message', (d) => {
    const m = JSON.parse(d);
    const i = waiters.findIndex((w) => w.types.includes(m.t));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(m);
    else inbox.push(m);
  });
  const next = (...types) => {
    const i = inbox.findIndex((m) => types.includes(m.t));
    if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
    return new Promise((resolve) => waiters.push({ types, resolve }));
  };
  const c = { ws, next, inbox, send: (o) => ws.send(JSON.stringify(o)) };
  const { nonce } = await next('challenge');
  const sig = crypto.sign(null, Buffer.from('auth:' + (badSig ? 'x' : nonce)), id.privateKey);
  c.send({ t: 'auth', ed: id.ed.toString('base64'), curve: id.curve.toString('base64'), sig: sig.toString('base64') });
  return c;
}
const close = (c) => new Promise((r) => { c.ws.once('close', r); c.ws.close(); });

test('username is 8 base36 chars derived from keys', () => {
  const id = identity();
  assert.match(id.user, /^[0-9a-z]{8}$/);
  assert.equal(usernameOf(id.ed, id.curve), id.user);
});

test('bad signature is rejected', async () => {
  const c = await connect(identity(), { badSig: true });
  assert.equal((await c.next('error')).code, 'auth');
  await close(c);
});

test('offline message is queued in RAM, delivered, acked, receipt returned', async () => {
  const a = identity(), b = identity();
  const cb = await connect(b);
  await cb.next('ok');
  await close(cb);

  const ca = await connect(a);
  await ca.next('ok');
  ca.send({ t: 'send', to: b.user, id: 'm1', body: 'CIPHERTEXT' });
  await ca.next('sent');
  ca.send({ t: 'send', to: b.user, id: 't1', body: 'typing', eph: true });

  const cb2 = await connect(b);
  await cb2.next('ok');
  const msg = await cb2.next('msg');
  assert.equal(msg.from, a.user);
  assert.equal(msg.body, 'CIPHERTEXT');
  cb2.send({ t: 'ack', q: msg.q });
  const rcpt = await ca.next('delivered');
  assert.equal(rcpt.id, 'm1');
  ca.send({ t: 'ack', q: rcpt.q });
  await new Promise((r) => setTimeout(r, 50));
  assert.ok(!cb2.inbox.some((m) => m.id === 't1'), 'ephemeral message must not be queued');
  assert.equal(srv.state.queues.get(b.user)?.size ?? 0, 0);
  await close(ca);
  await close(cb2);
});

test('queued items expire after 24h and RAM cap rejects overflow', async () => {
  const a = identity(), b = identity();
  const cb = await connect(b);
  await cb.next('ok');
  await close(cb);
  const ca = await connect(a);
  await ca.next('ok');
  ca.send({ t: 'send', to: b.user, id: 'old', body: 'x' });
  await ca.next('sent');
  clock += 24 * 3600 * 1000 + 1;
  srv.sweep();
  assert.equal(srv.state.queues.get(b.user)?.size ?? 0, 0);

  const big = 'x'.repeat(250 * 1024); // 1 MB cap → 5th one does not fit
  let full = false;
  for (let i = 0; i < 5 && !full; i++) {
    ca.send({ t: 'send', to: b.user, id: 'big' + i, body: big });
    const r = await ca.next('sent', 'error');
    full = r.t === 'error' && r.code === 'full';
  }
  assert.ok(full);
  clock += 24 * 3600 * 1000 + 1;
  srv.sweep();
  assert.equal(srv.state.ram, 0);
  await close(ca);
});

test('blob lives in RAM until deleted', async () => {
  const a = identity();
  const ca = await connect(a);
  const { token } = await ca.next('ok');
  const up = await fetch(`http://${base()}/blob`, { method: 'POST', headers: { authorization: 'Bearer ' + token }, body: Buffer.from('enc') });
  const { id } = await up.json();
  assert.equal(await (await fetch(`http://${base()}/blob/${id}`)).text(), 'enc');
  assert.equal((await fetch(`http://${base()}/blob/${id}`, { method: 'DELETE' })).status, 204);
  assert.equal((await fetch(`http://${base()}/blob/${id}`)).status, 404);
  assert.equal((await fetch(`http://${base()}/blob`, { method: 'POST', body: 'x' })).status, 401);
  await close(ca);
});

test('hidden last seen is not revealed', async () => {
  const a = identity(), b = identity();
  const ca = await connect(a);
  await ca.next('ok');
  const cb = await connect(b);
  await cb.next('ok');
  const profile = JSON.stringify({ name: 'b' });
  const sig = crypto.sign(null, Buffer.from(profile), b.privateKey).toString('base64');
  cb.send({ t: 'setProfile', profile, sig, hideSeen: true });
  await cb.next('profileOk');
  ca.send({ t: 'sub', users: [b.user] });
  const p = await ca.next('presence');
  assert.equal(p.hidden, true);
  assert.equal(p.online, undefined);
  ca.send({ t: 'setProfile', profile, sig, hideSeen: false }); // sig is b's → must be rejected for a
  assert.equal((await ca.next('error')).code, 'profile');
  await close(ca);
  await close(cb);
});

test('bundle hands out one-time keys once, then the fallback', async () => {
  const a = identity(), b = identity();
  const cb = await connect(b);
  await cb.next('ok');
  cb.send({ t: 'keys', otks: [{ id: 'k1', key: 'K1', sig: 'S1' }], fallback: { id: 'f', key: 'F', sig: 'SF' } });
  await cb.next('keysOk');
  const ca = await connect(a);
  await ca.next('ok');
  ca.send({ t: 'bundle', user: b.user });
  assert.equal((await ca.next('bundle')).otk.id, 'k1');
  ca.send({ t: 'bundle', user: b.user });
  const second = await ca.next('bundle');
  assert.equal(second.otk.id, 'f');
  assert.equal(second.fallback, true);
  await close(ca);
  await close(cb);
});

test('signatures made by vodozemac on the phone verify here', () => {
  // From android/crypto-rs: cargo run --example vector
  const { verifySig } = require('./server');
  const ed = Buffer.from('H/SYWDsZ0HIgWo1fz43dREeH+l7JHR758alKgy4ilsk=', 'base64');
  const sig = Buffer.from('IXPdj91zyXBYvOj3/gsGClmQRdXetnOOmLnfZsdXoCSkZIzL8x771CZsznhtox6KrK5hqzwMgXbJHJMhQ46zBA==', 'base64');
  assert.equal(verifySig(ed, 'auth:nonce123', sig), true);
  assert.equal(verifySig(ed, 'auth:nonce124', sig), false);
  assert.equal(usernameOf(ed, Buffer.from('2nhEbF7O7STMmeMoCHh/JwnQI9q82Gx1MlfRRvOVc3o=', 'base64')), 'j2g1433s');
});

test('invite page hands off to the app (payam:// / intent://) and never sees the fingerprint', async () => {
  await new Promise((r) => (srv.server.listening ? r() : srv.server.once('listening', r)));
  const res = await fetch(`http://${base()}/u/ab12cd34`);
  assert.equal(res.status, 200);
  const html = await res.text();
  assert.match(html, /payam:\/\/invite\?u=ab12cd34/);
  assert.match(html, /intent:\/\/invite\?/);
  assert.match(html, /package=ir\.payam\.messenger/);
  assert.equal((await fetch(`http://${base()}/u/AB12CD34`)).status, 404); // only valid usernames are echoed
});