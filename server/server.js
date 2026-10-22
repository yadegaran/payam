'use strict';
// Relay server. On disk: public identity keys, one-time keys and public profiles.
// Messages and blobs are client-encrypted ciphertext held in RAM only, for at
// most 24h, and dropped as soon as the recipient acks them. Nothing is logged.
const http = require('node:http');
const crypto = require('node:crypto');
const path = require('node:path');
const fs = require('node:fs');
const { DatabaseSync } = require('node:sqlite');
const { WebSocketServer } = require('ws');

const DAY = 24 * 3600 * 1000;
const MAX_BLOB = 100 * 1024 * 1024;
const MAX_PHOTO = 200 * 1024;
const MAX_PHOTOS = 5;
const MAX_OTKS = 200;
const MAX_QUEUE_ITEMS = 5000; // ponytail: per-recipient item cap, add byte quota per sender if abused
const ED_SPKI = Buffer.from('302a300506032b6570032100', 'hex');
const USER_RE = /^[0-9a-z]{8}$/;

// Username = first 8 base36 chars of SHA-256(ed25519 ‖ curve25519). The client
// recomputes this from the keys the server hands out, so a swapped key is caught.
function usernameOf(ed, curve) {
  const h = crypto.createHash('sha256').update(ed).update(curve).digest();
  return (h.readBigUInt64BE(0) % 36n ** 8n).toString(36).padStart(8, '0');
}

// Android only opens https links in an app after domain verification, which a
// self-hosted server can't have for a shared app. So the invite page hands off to
// the app with its own scheme (payam://invite?u=&s=&fp=); on Android an intent://
// URL also falls back to downloading the app when it isn't installed.
// `user` is already validated against USER_RE; the fingerprint stays client-side.
function invitePage(user) {
  return `<!doctype html><html dir="rtl" lang="fa"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer">
<title>دعوت به پیام</title><style>
body{font-family:system-ui,Tahoma,sans-serif;margin:0;padding:48px 16px;text-align:center;background:#f4f6f8;color:#17212b}
.card{max-width:420px;margin:auto;background:#fff;border-radius:16px;padding:28px 20px;box-shadow:0 2px 12px #0001}
.u{font:700 28px monospace;letter-spacing:2px;margin:6px 0 22px}
a.btn{display:block;padding:14px;border-radius:12px;background:#2aabee;color:#fff;text-decoration:none;font-weight:700;margin:10px 0}
a.alt{background:#e8edf2;color:#17212b}p.s{color:#66727f;font-size:14px;line-height:1.7}
@media (prefers-color-scheme:dark){body{background:#0e1621;color:#e9eef3}.card{background:#17212b}a.alt{background:#242f3d;color:#e9eef3}}
</style></head><body><div class="card">
<p>دعوت به گفتگوی امن با</p><div class="u">${user}</div>
<a class="btn" id="open" href="payam://invite?u=${user}">باز کردن در برنامه پیام</a>
<a class="btn alt" href="/app.apk">دانلود برنامه</a>
<p class="s">اگر برنامه را ندارید، اول آن را نصب کنید و بعد دوباره روی لینک دعوت بزنید.</p>
</div><script>
(function () {
  var fp = location.hash.slice(1); if (!/^[0-9a-f]{64}$/.test(fp)) fp = '';
  var q = 'u=${user}&s=' + encodeURIComponent(location.origin) + (fp ? '&fp=' + fp : '');
  var a = document.getElementById('open');
  a.href = /Android/i.test(navigator.userAgent)
    ? 'intent://invite?' + q + '#Intent;scheme=payam;package=ir.payam.messenger;S.browser_fallback_url=' +
      encodeURIComponent(location.origin + '/app.apk') + ';end'
    : 'payam://invite?' + q;
})();
</script></body></html>`;
}

function verifySig(edRaw, msg, sig) {
  try {
    const key = crypto.createPublicKey({ key: Buffer.concat([ED_SPKI, edRaw]), format: 'der', type: 'spki' });
    return crypto.verify(null, Buffer.from(msg), key, sig);
  } catch {
    return false;
  }
}

function b64(s, len) {
  if (typeof s !== 'string') return null;
  const b = Buffer.from(s, 'base64');
  return b.length === len ? b : null;
}

const str = (s, max) => typeof s === 'string' && s.length > 0 && s.length <= max;
const keyObj = (k) => k && str(k.id, 64) && str(k.key, 64) && str(k.sig, 128);

// Fixed-window counter: true when `key` exceeded `max` hits in the window.
function limited(map, key, max, windowMs, t) {
  let e = map.get(key);
  if (!e || e.reset <= t) map.set(key, (e = { n: 0, reset: t + windowMs }));
  return ++e.n > max;
}

function start({ port = 8080, host = '127.0.0.1', dbPath = 'data/users.db', maxRamMb = 1024, now = Date.now } = {}) {
  if (dbPath !== ':memory:') fs.mkdirSync(path.dirname(dbPath), { recursive: true });
  const db = new DatabaseSync(dbPath);
  db.exec(`
    PRAGMA journal_mode = WAL;
    CREATE TABLE IF NOT EXISTS users(
      user TEXT PRIMARY KEY, ed BLOB NOT NULL, curve BLOB NOT NULL, fallback TEXT,
      profile TEXT, profileSig TEXT, hideSeen INTEGER NOT NULL DEFAULT 0, hidePhoto INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS otks(user TEXT NOT NULL, id TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(user, id));
    CREATE TABLE IF NOT EXISTS photos(user TEXT NOT NULL, n INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(user, n));
  `);
  const q = {
    user: db.prepare('SELECT * FROM users WHERE user = ?'),
    addUser: db.prepare('INSERT INTO users(user, ed, curve) VALUES (?, ?, ?)'),
    delUser: db.prepare('DELETE FROM users WHERE user = ?'),
    setFallback: db.prepare('UPDATE users SET fallback = ? WHERE user = ?'),
    setProfile: db.prepare('UPDATE users SET profile = ?, profileSig = ?, hideSeen = ?, hidePhoto = ? WHERE user = ?'),
    addOtk: db.prepare('INSERT OR IGNORE INTO otks(user, id, json) VALUES (?, ?, ?)'),
    popOtk: db.prepare('DELETE FROM otks WHERE rowid = (SELECT rowid FROM otks WHERE user = ? LIMIT 1) RETURNING json'),
    countOtk: db.prepare('SELECT COUNT(*) AS n FROM otks WHERE user = ?'),
    delOtks: db.prepare('DELETE FROM otks WHERE user = ?'),
    photo: db.prepare('SELECT data FROM photos WHERE user = ? AND n = ?'),
    setPhoto: db.prepare('INSERT OR REPLACE INTO photos(user, n, data) VALUES (?, ?, ?)'),
    delPhoto: db.prepare('DELETE FROM photos WHERE user = ? AND n = ?'),
    delPhotos: db.prepare('DELETE FROM photos WHERE user = ?'),
  };

  const maxRam = maxRamMb * 1024 * 1024;
  const st = {
    ram: 0,
    online: new Map(), // user -> ws
    tokens: new Map(), // http bearer token -> user (lives as long as the socket)
    queues: new Map(), // user -> Map(qid -> item)   ciphertext, RAM only
    blobs: new Map(), // id -> { data, ts }          ciphertext, RAM only
    lastSeen: new Map(), // user -> ms               RAM only
    watchers: new Map(), // user -> Set(watching users)
    regByIp: new Map(),
  };
  let seq = 0;

  const send = (ws, obj) => ws && ws.readyState === 1 && ws.send(JSON.stringify(obj));

  function enqueue(to, item) {
    const size = (item.body ? item.body.length : 0) + 200;
    let qu = st.queues.get(to);
    if (!qu) st.queues.set(to, (qu = new Map()));
    if (st.ram + size > maxRam || qu.size >= MAX_QUEUE_ITEMS) return false;
    item.q = (++seq).toString(36);
    item.ts = now();
    item.size = size;
    qu.set(item.q, item);
    st.ram += size;
    send(st.online.get(to), wire(item));
    return true;
  }
  const wire = ({ size, ...rest }) => rest;

  function presenceOf(u, viewer) {
    const row = q.user.get(u);
    const vrow = q.user.get(viewer);
    // Hiding your own last seen also hides everyone else's from you (as in Telegram).
    if (!row || row.hideSeen || (vrow && vrow.hideSeen)) return { t: 'presence', user: u, hidden: true };
    return { t: 'presence', user: u, online: st.online.has(u), lastSeen: st.lastSeen.get(u) ?? null };
  }
  function broadcastPresence(u) {
    for (const w of st.watchers.get(u) || []) send(st.online.get(w), presenceOf(u, w));
  }

  function sweep() {
    const cutoff = now() - DAY;
    for (const [u, qu] of st.queues) {
      for (const [k, it] of qu) if (it.ts < cutoff) { qu.delete(k); st.ram -= it.size; }
      if (!qu.size) st.queues.delete(u);
    }
    for (const [id, b] of st.blobs) if (b.ts < cutoff) { st.blobs.delete(id); st.ram -= b.data.length; }
    for (const [ip, e] of st.regByIp) if (e.reset <= now()) st.regByIp.delete(ip);
  }
  const sweeper = setInterval(sweep, 60_000);

  function deleteAccount(u) {
    q.delUser.run(u); q.delOtks.run(u); q.delPhotos.run(u);
    const qu = st.queues.get(u);
    if (qu) for (const it of qu.values()) st.ram -= it.size;
    st.queues.delete(u);
  }

  // ---------------- HTTP: blobs, profile photos, invite landing ----------------
  function authUser(req) {
    const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
    return m ? st.tokens.get(m[1]) : undefined;
  }
  function readBody(req, max, cb) {
    const chunks = [];
    let n = 0;
    req.on('data', (c) => {
      n += c.length;
      if (n > max) { req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => cb(Buffer.concat(chunks)));
  }
  const end = (res, code, body = '', type = 'application/json') => {
    res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' });
    res.end(body);
  };

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    const parts = url.pathname.split('/').filter(Boolean);

    if (parts[0] === 'blob') {
      if (req.method === 'POST' && parts.length === 1) {
        if (!authUser(req)) return end(res, 401);
        const len = Number(req.headers['content-length'] || 0);
        if (!len || len > MAX_BLOB) return end(res, 413);
        if (st.ram + len > maxRam) return end(res, 507);
        return readBody(req, MAX_BLOB, (data) => {
          if (st.ram + data.length > maxRam) return end(res, 507);
          const id = crypto.randomBytes(16).toString('hex');
          st.blobs.set(id, { data, ts: now() });
          st.ram += data.length;
          end(res, 200, JSON.stringify({ id }));
        });
      }
      const b = parts.length === 2 && st.blobs.get(parts[1]);
      if (!b) return end(res, 404);
      if (req.method === 'GET') return end(res, 200, b.data, 'application/octet-stream');
      if (req.method === 'DELETE') {
        st.blobs.delete(parts[1]);
        st.ram -= b.data.length;
        return end(res, 204);
      }
      return end(res, 405);
    }

    if (parts[0] === 'photo') {
      if (parts.length === 3 && req.method === 'GET') {
        const row = USER_RE.test(parts[1]) && q.user.get(parts[1]);
        const ph = row && !row.hidePhoto && q.photo.get(parts[1], Number(parts[2]));
        return ph ? end(res, 200, Buffer.from(ph.data), 'image/jpeg') : end(res, 404);
      }
      const me = authUser(req);
      const n = Number(parts[1]);
      if (!me) return end(res, 401);
      if (parts.length !== 2 || !Number.isInteger(n) || n < 0 || n >= MAX_PHOTOS) return end(res, 400);
      if (req.method === 'DELETE') { q.delPhoto.run(me, n); return end(res, 204); }
      if (req.method === 'PUT') {
        return readBody(req, MAX_PHOTO, (data) => {
          // JPEG only (clients re-encode, which also strips EXIF).
          if (data.length < 4 || data[0] !== 0xff || data[1] !== 0xd8 || data[2] !== 0xff) return end(res, 415);
          q.setPhoto.run(me, n, data);
          end(res, 204);
        });
      }
      return end(res, 405);
    }

    // Invite landing page. The #fingerprint part of the link never reaches us.
    if (parts[0] === 'u' && parts.length === 2 && USER_RE.test(parts[1]) && req.method === 'GET') {
      return end(res, 200, invitePage(parts[1]), 'text/html; charset=utf-8');
    }
    end(res, 404);
  });

  // ---------------- WebSocket: auth, keys, messages, presence ----------------
  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 300 * 1024 });

  wss.on('connection', (ws, req) => {
    const ip = req.headers['x-real-ip'] || req.socket.remoteAddress; // rate limiting only, never stored
    const nonce = crypto.randomBytes(32).toString('base64');
    const limits = new Map();
    let me = null;
    let token = null;
    let watching = new Set();
    ws.isAlive = true;
    ws.on('pong', () => (ws.isAlive = true));
    ws.on('error', () => ws.terminate());
    send(ws, { t: 'challenge', nonce });

    const err = (code, ref) => send(ws, { t: 'error', code, ref });

    ws.on('message', (raw, isBinary) => {
      if (isBinary) return;
      let m;
      try { m = JSON.parse(raw); } catch { return err('json'); }
      if (!m || typeof m.t !== 'string') return err('json');
      if (!me && m.t !== 'auth') return err('auth');
      if (limited(limits, 'all', 200, 10_000, now())) return err('rate');
      try {
        handle(m);
      } catch {
        err('server');
      }
    });

    function handle(m) {
      switch (m.t) {
        case 'auth': {
          const ed = b64(m.ed, 32), curve = b64(m.curve, 32), sig = b64(m.sig, 64);
          if (me || !ed || !curve || !sig || !verifySig(ed, 'auth:' + nonce, sig)) return err('auth');
          const user = usernameOf(ed, curve);
          const row = q.user.get(user);
          let isNew = false;
          if (row) {
            if (!ed.equals(Buffer.from(row.ed)) || !curve.equals(Buffer.from(row.curve))) return err('taken');
          } else {
            if (limited(st.regByIp, ip, 10, 3600_000, now())) return err('rate');
            q.addUser.run(user, ed, curve);
            isNew = true;
          }
          send(st.online.get(user), { t: 'kicked' });
          st.online.get(user)?.close();
          me = user;
          token = crypto.randomBytes(24).toString('base64url');
          st.online.set(me, ws);
          st.tokens.set(token, me);
          st.lastSeen.delete(me);
          send(ws, { t: 'ok', user, token, isNew, otks: q.countOtk.get(me).n });
          for (const it of st.queues.get(me)?.values() || []) send(ws, wire(it));
          broadcastPresence(me);
          return;
        }

        case 'keys': {
          const otks = Array.isArray(m.otks) ? m.otks : [];
          if (otks.length > MAX_OTKS || !otks.every(keyObj) || (m.fallback && !keyObj(m.fallback))) return err('keys');
          if (q.countOtk.get(me).n + otks.length > MAX_OTKS) return err('keys');
          for (const k of otks) q.addOtk.run(me, k.id, JSON.stringify({ id: k.id, key: k.key, sig: k.sig }));
          if (m.fallback) q.setFallback.run(JSON.stringify(m.fallback), me);
          return send(ws, { t: 'keysOk', otks: q.countOtk.get(me).n });
        }

        case 'bundle':
        case 'getProfile': {
          if (!USER_RE.test(m.user)) return err('nouser', m.user);
          if (limited(limits, 'lookup', 30, 60_000, now())) return err('rate', m.user);
          const row = q.user.get(m.user);
          if (!row) return err('nouser', m.user);
          const base = { user: m.user, ed: Buffer.from(row.ed).toString('base64'), curve: Buffer.from(row.curve).toString('base64') };
          if (m.t === 'getProfile') {
            return send(ws, { t: 'profile', ...base, profile: row.profile, sig: row.profileSig, hidePhoto: !!row.hidePhoto });
          }
          const popped = q.popOtk.get(m.user);
          const otk = popped ? JSON.parse(popped.json) : row.fallback ? JSON.parse(row.fallback) : null;
          send(ws, { t: 'bundle', ...base, otk, fallback: !popped });
          const left = q.countOtk.get(m.user).n;
          if (left < 10) send(st.online.get(m.user), { t: 'lowKeys', otks: left });
          return;
        }

        case 'setProfile': {
          const sig = b64(m.sig, 64);
          if (!str(m.profile, 8192) || !sig) return err('profile');
          if (!verifySig(Buffer.from(q.user.get(me).ed), m.profile, sig)) return err('profile');
          q.setProfile.run(m.profile, m.sig, m.hideSeen ? 1 : 0, m.hidePhoto ? 1 : 0, me);
          send(ws, { t: 'profileOk' });
          broadcastPresence(me);
          return;
        }

        case 'send': {
          if (!USER_RE.test(m.to) || !str(m.id, 64) || !str(m.body, 256 * 1024)) return err('send', m.id);
          if (!q.user.get(m.to)) return err('nouser', m.id);
          const item = { t: 'msg', from: me, id: m.id, body: m.body, kind: m.kind | 0 };
          if (m.eph) return send(st.online.get(m.to), { ...item, ts: now() }); // typing etc.: never queued
          if (!enqueue(m.to, item)) return err('full', m.id);
          return send(ws, { t: 'sent', id: m.id });
        }

        case 'ack': {
          const qu = st.queues.get(me);
          const it = qu && qu.get(m.q);
          if (!it) return;
          qu.delete(m.q);
          st.ram -= it.size;
          if (it.t === 'msg') enqueue(it.from, { t: 'delivered', from: me, id: it.id });
          return;
        }

        case 'sub': {
          const users = Array.isArray(m.users) ? m.users.filter((u) => USER_RE.test(u)).slice(0, 500) : [];
          for (const u of watching) st.watchers.get(u)?.delete(me);
          watching = new Set(users);
          for (const u of watching) {
            if (!st.watchers.has(u)) st.watchers.set(u, new Set());
            st.watchers.get(u).add(me);
            send(ws, presenceOf(u, me));
          }
          return;
        }

        case 'deleteAccount': {
          deleteAccount(me);
          return ws.close();
        }

        default:
          return err('unknown');
      }
    }

    ws.on('close', () => {
      if (token) st.tokens.delete(token);
      for (const u of watching) st.watchers.get(u)?.delete(me);
      if (me && st.online.get(me) === ws) {
        st.online.delete(me);
        st.lastSeen.set(me, now());
        broadcastPresence(me);
      }
    });
  });

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.isAlive) { ws.terminate(); continue; }
      ws.isAlive = false;
      ws.ping();
    }
  }, 30_000);

  server.listen(port, host);
  return {
    server,
    state: st,
    sweep,
    close() {
      clearInterval(sweeper);
      clearInterval(heartbeat);
      for (const ws of wss.clients) ws.terminate();
      wss.close();
      server.close();
      db.close();
    },
  };
}

module.exports = { start, usernameOf, verifySig };

if (require.main === module) {
  start({
    port: Number(process.env.PORT || 8080),
    host: process.env.HOST || '127.0.0.1',
    dbPath: process.env.DB_PATH || path.join(__dirname, 'data', 'users.db'),
    maxRamMb: Number(process.env.MAX_RAM_MB || 1024),
  });
}
