// Сервер чата поддержки PharmShop — Cloudflare Workers + D1.
// Настройки (Variables and Secrets): ADMIN_CODE (обязательно), BOT_TOKEN и CHAT_ID (по желанию, для уведомлений в Telegram),
// FCM_SERVICE_ACCOUNT (по желанию, JSON ключа сервисного аккаунта Firebase — включает мгновенные push).
// Привязка базы: переменная DB -> ваша D1-база.

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS threads (
     device TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, nick TEXT DEFAULT '', last_order TEXT DEFAULT '',
     last_text TEXT DEFAULT '', last_ts INTEGER DEFAULT 0, last_user_ts INTEGER DEFAULT 0,
     unread INTEGER DEFAULT 0, created INTEGER DEFAULT 0)`,
  `CREATE TABLE IF NOT EXISTS messages (
     id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_messages_device ON messages (device, id)`,
  // токены FCM: у пользователя один на устройство (с проверкой секрета), у админа — по числу его устройств
  `CREATE TABLE IF NOT EXISTS push_user (device TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, token TEXT NOT NULL, ts INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS push_admin (token TEXT PRIMARY KEY, code_fp TEXT NOT NULL, ts INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS fails (ip TEXT PRIMARY KEY, n INTEGER, ts INTEGER)`,
  // блокировки спамеров: kind = 'device' | 'ip'
  `CREATE TABLE IF NOT EXISTS blocks (
     kind TEXT NOT NULL, value TEXT NOT NULL, reason TEXT DEFAULT '', ts INTEGER NOT NULL, PRIMARY KEY (kind, value))`,
  // удалённые настройки приложения (одна запись k='app' с JSON)
  `CREATE TABLE IF NOT EXISTS settings (k TEXT PRIMARY KEY, v TEXT NOT NULL)`,
  // сессии админа: хранится только хэш токена; code_fp — отпечаток ADMIN_CODE (смена кода гасит все сессии)
  `CREATE TABLE IF NOT EXISTS sessions (
     hash TEXT PRIMARY KEY, code_fp TEXT NOT NULL, created INTEGER NOT NULL, exp INTEGER NOT NULL)`,
  // ---- общий чат и личные сообщения ----
  // users: один пользователь = одно устройство. nick_key — ник в «свёрнутом» виде (без регистра, с заменой похожих букв),
  // UNIQUE по нему не даёт занять один ник дважды. badge = 1 — «галочка» (модератор чата).
  `CREATE TABLE IF NOT EXISTS users (
     id INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL UNIQUE, secret_hash TEXT NOT NULL,
     nick TEXT NOT NULL, nick_key TEXT NOT NULL UNIQUE, badge INTEGER NOT NULL DEFAULT 0,
     muted_until INTEGER NOT NULL DEFAULT 0, nick_changed INTEGER NOT NULL DEFAULT 0,
     created INTEGER NOT NULL, last_seen INTEGER NOT NULL DEFAULT 0, last_ip TEXT NOT NULL DEFAULT '')`,
  `CREATE TABLE IF NOT EXISTS room_messages (
     id INTEGER PRIMARY KEY AUTOINCREMENT, uid INTEGER NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_room_uid ON room_messages (uid, ts)`,
  // личные сообщения: пара (a < b) — номера пользователей, sender — кто написал
  `CREATE TABLE IF NOT EXISTS dm_messages (
     id INTEGER PRIMARY KEY AUTOINCREMENT, a INTEGER NOT NULL, b INTEGER NOT NULL, sender INTEGER NOT NULL,
     text TEXT NOT NULL, ts INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_dm_pair ON dm_messages (a, b, id)`,
  `CREATE INDEX IF NOT EXISTS idx_dm_sender ON dm_messages (sender, ts)`,
  `CREATE INDEX IF NOT EXISTS idx_dm_b ON dm_messages (b, id)`,
  // до какого сообщения uid прочитал переписку с peer
  `CREATE TABLE IF NOT EXISTS dm_reads (uid INTEGER NOT NULL, peer INTEGER NOT NULL, last_id INTEGER NOT NULL, PRIMARY KEY (uid, peer))`,
  // реакции на сообщения общего чата: у одного человека на одном сообщении одна реакция
  `CREATE TABLE IF NOT EXISTS room_reactions (
     msg INTEGER NOT NULL, uid INTEGER NOT NULL, emoji TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (msg, uid))`,
  // заказы, оформленные через приложение (приложение присылает их само: /api/orders/report). id — номер заказа на сайте.
  // kind: 2 — выполнен, 3 — отменён / ошибка, остальное — в работе. created — время создания заказа (мс).
  `CREATE TABLE IF NOT EXISTS orders (
     id TEXT PRIMARY KEY, device TEXT NOT NULL, order_number TEXT NOT NULL DEFAULT '', username TEXT NOT NULL DEFAULT '',
     product TEXT NOT NULL DEFAULT 'stars', quantity INTEGER NOT NULL DEFAULT 0, total TEXT NOT NULL DEFAULT '',
     currency TEXT NOT NULL DEFAULT '', method TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT '',
     kind INTEGER NOT NULL DEFAULT 4, created INTEGER NOT NULL, updated INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_orders_created ON orders (created)`,
  `CREATE INDEX IF NOT EXISTS idx_orders_device ON orders (device)`,
  // устройства, которые купили в приложении, но ещё не писали в чат: секрет запоминается при первом заказе
  `CREATE TABLE IF NOT EXISTS devices (device TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, ts INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS idx_react_uid ON room_reactions (uid, ts)`,
  // оценки поддержки: round = номер «Решено» (threads.solved) на момент оценки, на каждое закрытие одна оценка
  `CREATE TABLE IF NOT EXISTS support_ratings (
     device TEXT NOT NULL, round INTEGER NOT NULL, stars INTEGER NOT NULL, comment TEXT NOT NULL DEFAULT '', ts INTEGER NOT NULL,
     PRIMARY KEY (device, round))`,
  `CREATE INDEX IF NOT EXISTS idx_ratings_ts ON support_ratings (ts)`,
];
// Новые колонки для уже существующей базы (ошибка «колонка уже есть» игнорируется).
const MIGRATIONS = [
  `ALTER TABLE messages ADD COLUMN image TEXT DEFAULT ''`,
  `ALTER TABLE messages ADD COLUMN ip TEXT DEFAULT ''`,
  `ALTER TABLE messages ADD COLUMN dev TEXT DEFAULT ''`,
  `ALTER TABLE messages ADD COLUMN geo TEXT DEFAULT ''`,
  `ALTER TABLE threads ADD COLUMN last_ip TEXT DEFAULT ''`,
  `ALTER TABLE threads ADD COLUMN last_dev TEXT DEFAULT ''`,
  `ALTER TABLE threads ADD COLUMN status TEXT DEFAULT 'new'`,          // new | work | done
  `ALTER TABLE threads ADD COLUMN admin_read_ts INTEGER DEFAULT 0`,    // админ прочитал сообщения пользователя до этого времени
  `ALTER TABLE threads ADD COLUMN user_read_ts INTEGER DEFAULT 0`,     // пользователь прочитал ответы админа до этого времени
  `ALTER TABLE threads ADD COLUMN diag TEXT DEFAULT ''`,               // диагностика и аналитика устройства (JSON из приложения)
  `ALTER TABLE threads ADD COLUMN diag_ts INTEGER DEFAULT 0`,          // когда блок получен в последний раз
  `ALTER TABLE threads ADD COLUMN solved INTEGER DEFAULT 0`,           // сколько раз обращение отмечали «Решено» (для достижений в приложении)
  `UPDATE threads SET solved = 1 WHERE status = 'done' AND COALESCE(solved, 0) = 0`,   // уже закрытые до этого обновления
  `CREATE INDEX IF NOT EXISTS idx_messages_ip ON messages (ip)`,
  `CREATE INDEX IF NOT EXISTS idx_threads_ip ON threads (last_ip, created)`,
  `ALTER TABLE users ADD COLUMN avatar TEXT DEFAULT ''`,              // маленькая аватарка (JPEG в base64), та же, что в профиле приложения
  `ALTER TABLE users ADD COLUMN avatar_v INTEGER DEFAULT 0`,          // версия аватарки (время загрузки); 0 — аватарки нет
  `ALTER TABLE users ADD COLUMN crown INTEGER DEFAULT 0`,             // 1 — «зелёная корона»: собраны все достижения (сообщает приложение)
  `ALTER TABLE room_messages ADD COLUMN reply_to INTEGER DEFAULT 0`,  // номер сообщения, на которое это — ответ (0 — не ответ)
];
const MAX_AVATAR = 60_000;       // символов base64 (~45 КБ): приложение присылает 128×128
const MAX_DIAG = 4000;            // символов JSON с диагностикой устройства
const MAX_IMAGE = 1_400_000; // символов base64 (~1 МБ картинки); лимит строки в D1 — 2 МБ
const B64 = /^[A-Za-z0-9+/=]+$/;
let ready = false;

const json = (o, s = 200) =>
  new Response(JSON.stringify(o), {
    status: s,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
const err = (m, s = 400) => json({ error: m }, s);
const UUID = /^[0-9a-f-]{36}$/;
const LOCK_MS = 15 * 60 * 1000;
const STATUSES = ['new', 'work', 'done'];
const IP_RE = /^[0-9a-fA-F:.]{3,64}$/;
const DAY = 24 * 60 * 60 * 1000;
const SESSION_MS = 30 * DAY;

// ---------- кэш в памяти воркера: опросы «ничего нового» без обращения к D1 ----------
// У воркера может быть несколько экземпляров, и у каждого своя память, поэтому кэш короткоживущий: запись, сделанная
// в другом экземпляре, доходит сюда не позже чем через TTL. Свои записи экземпляр видит сразу (кэш сбрасывается).
// Кэшируются только опросы (чтение). Любая запись (отправка, реакция, удаление, мут) всегда проверяется по базе.
const AUTH_TTL = 20_000;    // пользователь чата в опросах: бан, мут и «галочка» вступают в силу в опросах не позже чем через это время
const ROOM_TTL = 3_000;     // номер последнего сообщения комнаты, ревизия ленты и версия реакций
const DM_TTL = 3_000;       // номер последнего личного сообщения
const ADMIN_TTL = 60_000;   // проверенная сессия админа
const AUTH_MEM = new Map();
const ADMIN_MEM = new Map();
const ROOM_MEM = { id: 0, rev: 0, rx: 0, at: 0 };
const DM_MEM = { id: 0, at: 0 };
const authDirty = () => AUTH_MEM.clear();
const roomDirty = () => { ROOM_MEM.at = 0; };
const dmDirty = () => { DM_MEM.at = 0; };
const MAX_BODY = 2_000_000;           // байт; фото ≤ ~1,4 МБ в base64
// Лимиты чата: окно 10 минут. IP может быть общим (мобильный интернет, Wi-Fi), поэтому для него лимит щедрее.
const RATE_WINDOW = 10 * 60 * 1000;
const RATE_DEVICE_MSGS = 20;
const RATE_DEVICE_IMAGES = 5;
const RATE_IP_MSGS = 60;
const RATE_IP_NEW_THREADS = 5;        // новых чатов с одного IP за час
// Общий чат и лички
const RATE_ROOM_PER_MIN = 8;           // сообщений в общий чат с одного пользователя за минуту (у модераторов лимита нет)
const RATE_DM_PER_MIN = 15;            // личных сообщений за минуту
const RATE_NEW_USERS_PER_IP = 5;       // новых ников с одного IP за сутки
const NICK_CHANGE_MS = 7 * 24 * 60 * 60 * 1000;   // сменить ник можно раз в 7 дней
const ROOM_COOLDOWN_MS = 10_000;       // не чаще одного сообщения в общий чат раз в 10 секунд (у модераторов лимита нет)
const CHAT_TEXT_MAX = 1000;
// hCaptcha при первом входе в чат. Ключ сайта публичный, секрет — в переменной HCAPTCHA_SECRET (Variables and Secrets).
const HCAPTCHA_SITEKEY = '3d88c4d4-c8f4-4e0a-8db6-21e62e840604';
const MUTE_FOREVER = 4102444800000;    // 1 января 2100
// Реакции в общем чате. Список должен совпадать с REACTIONS в приложении (Community.kt).
const REACTIONS = ['👍', '❤️', '😂', '🔥', '😮', '😢'];
const RATE_REACT_PER_MIN = 30;
const RATING_COMMENT_MAX = 300;
const noVS = (v) => String(v || '').replace(/\uFE0F/g, '');   // «❤» и «❤️» считаем одной реакцией

async function sha256(s) {
  const b = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
}

async function ensureSchema(db) {
  if (ready) return;
  await db.batch(SCHEMA.map((q) => db.prepare(q)));
  for (const q of MIGRATIONS) {
    try { await db.prepare(q).run(); } catch (e) { /* колонка уже есть */ }
  }
  ready = true;
}

// Проверка доступа админа. Приложение входит по коду один раз (/api/admin/login), получает токен сессии
// и дальше шлёт только его (X-Admin-Token). Старые версии приложения шлют код в X-Admin-Code — это пока работает
// (чтобы отключить, задайте переменную LEGACY_ADMIN_CODE = off).
// После 5 неверных попыток с одного адреса — блокировка на 15 минут.
async function adminAuth(request, env, path) {
  const ip = request.headers.get('cf-connecting-ip') || 'unknown';
  const now = Date.now();
  // сессия, уже проверенная по базе меньше минуты назад: частые опросы админки не ходят в D1 ради проверки токена
  const tok0 = request.headers.get('x-admin-token') || '';
  if (path !== '/api/admin/login' && tok0.length >= 32) {
    const c = ADMIN_MEM.get(tok0);
    if (c && c > now) return true;
  }
  const row = await env.DB.prepare('SELECT n, ts FROM fails WHERE ip = ?').bind(ip).first();
  if (row && row.n >= 5 && now - row.ts < LOCK_MS) return 'locked';

  const fp = env.ADMIN_CODE ? await sha256('fp:' + env.ADMIN_CODE) : '';
  const code = request.headers.get('x-admin-code') || '';
  const token = request.headers.get('x-admin-token') || '';
  let ok = false;
  if (path !== '/api/admin/login' && token.length >= 32 && fp) {
    const s = await env.DB.prepare('SELECT code_fp, exp FROM sessions WHERE hash = ?').bind(await sha256(token)).first();
    ok = !!s && s.exp > now && s.code_fp === fp;
    if (ok) {
      if (ADMIN_MEM.size > 50) ADMIN_MEM.clear();
      ADMIN_MEM.set(token, Math.min(s.exp, now + ADMIN_TTL));
    }
  } else if (code.length > 0 && !!env.ADMIN_CODE && (path === '/api/admin/login' || env.LEGACY_ADMIN_CODE !== 'off')) {
    ok = (await sha256(code)) === (await sha256(env.ADMIN_CODE));
  }
  if (!ok) {
    const n = row && now - row.ts < LOCK_MS ? row.n + 1 : 1;
    await env.DB.prepare(
      'INSERT INTO fails (ip, n, ts) VALUES (?, ?, ?) ON CONFLICT(ip) DO UPDATE SET n = excluded.n, ts = excluded.ts'
    ).bind(ip, n, now).run();
    return false;
  }
  if (row) await env.DB.prepare('DELETE FROM fails WHERE ip = ?').bind(ip).run();
  return true;
}

// Вход по коду: выдаёт токен сессии на 30 дней. Токен — 32 случайных байта, в базе только его SHA-256.
async function adminLogin(env) {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const token = [...bytes].map((x) => x.toString(16).padStart(2, '0')).join('');
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare('DELETE FROM sessions WHERE exp < ?').bind(now),
    env.DB.prepare('INSERT INTO sessions (hash, code_fp, created, exp) VALUES (?, ?, ?, ?)')
      .bind(await sha256(token), await sha256('fp:' + env.ADMIN_CODE), now, now + SESSION_MS),
  ]);
  return json({ token, expires: now + SESSION_MS });
}

async function adminLogout(request, env) {
  const token = request.headers.get('x-admin-token') || '';
  if (token) ADMIN_MEM.delete(token);
  if (token) await env.DB.prepare('DELETE FROM sessions WHERE hash = ?').bind(await sha256(token)).run();
  return json({ ok: true });
}

// Лимиты на отправку в чат. Возвращает текст ошибки или '' (можно писать).
async function chatRateLimit(env, device, ip, hasImage, isNewThread) {
  const since = Date.now() - RATE_WINDOW;
  if (isNewThread && ip && ip !== 'unknown') {
    const r = await env.DB.prepare('SELECT COUNT(*) AS n FROM threads WHERE last_ip = ? AND created > ?')
      .bind(ip, Date.now() - 60 * 60 * 1000).first();
    if (r && r.n >= RATE_IP_NEW_THREADS) return 'Слишком много новых обращений с вашего адреса. Попробуйте позже';
  }
  const d = await env.DB.prepare(
    `SELECT COUNT(*) AS n, COALESCE(SUM(image != ''), 0) AS imgs FROM messages WHERE device = ? AND sender = 'user' AND ts > ?`
  ).bind(device, since).first();
  if (d && d.n >= RATE_DEVICE_MSGS) return 'Слишком много сообщений. Подождите несколько минут';
  if (hasImage && d && d.imgs >= RATE_DEVICE_IMAGES) return 'Слишком много фото. Подождите несколько минут';
  if (ip && ip !== 'unknown') {
    const i = await env.DB.prepare(`SELECT COUNT(*) AS n FROM messages WHERE ip = ? AND sender = 'user' AND ts > ?`)
      .bind(ip, since).first();
    if (i && i.n >= RATE_IP_MSGS) return 'Слишком много сообщений с вашего адреса. Подождите несколько минут';
  }
  return '';
}

// Пользователь видит только сообщения; админ — ещё IP, устройство и страну отправителя.
const MSG_SQL = `SELECT id, sender AS "from", text, ts, (image != '') AS has_image FROM messages WHERE device = ? AND id > ? ORDER BY id LIMIT 200`;
const MSG_SQL_ADMIN = `SELECT id, sender AS "from", text, ts, (image != '') AS has_image, ip, dev, geo FROM messages WHERE device = ? AND id > ? ORDER BY id LIMIT 200`;

function cleanImage(v) {
  const img = String(v || '');
  if (!img) return '';
  if (img.length > MAX_IMAGE || !B64.test(img)) return null;
  return img;
}

async function telegramNote(env, note, image) {
  const base = `https://api.telegram.org/bot${env.BOT_TOKEN}`;
  if (image) {
    const bin = Uint8Array.from(atob(image), (c) => c.charCodeAt(0));
    const fd = new FormData();
    fd.append('chat_id', env.CHAT_ID);
    fd.append('caption', note.slice(0, 1000));
    fd.append('photo', new Blob([bin], { type: 'image/jpeg' }), 'photo.jpg');
    return fetch(`${base}/sendPhoto`, { method: 'POST', body: fd });
  }
  return fetch(`${base}/sendMessage`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ chat_id: env.CHAT_ID, text: note.slice(0, 4000) }),
  });
}

// ---------- push через Firebase Cloud Messaging (HTTP v1) ----------
// Включается секретом FCM_SERVICE_ACCOUNT (JSON ключа сервисного аккаунта Firebase). Без него push просто не отправляются,
// а приложение продолжает получать уведомления опросом.
const b64url = (buf) =>
  btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const b64urlStr = (str) => b64url(new TextEncoder().encode(str));
let fcmCache = null;   // { token, exp } — токен доступа живёт час, переиспользуем

async function fcmAccessToken(sa) {
  if (fcmCache && fcmCache.exp > Date.now() + 60_000) return fcmCache.token;
  const now = Math.floor(Date.now() / 1000);
  const unsigned =
    b64urlStr(JSON.stringify({ alg: 'RS256', typ: 'JWT' })) + '.' +
    b64urlStr(JSON.stringify({
      iss: sa.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging',
      aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600,
    }));
  const der = Uint8Array.from(atob(sa.private_key.replace(/-----[^-]+-----|\s/g, '')), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned));
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=' + encodeURIComponent('urn:ietf:params:oauth:grant-type:jwt-bearer') + '&assertion=' + unsigned + '.' + b64url(sig),
  });
  const j = await r.json();
  if (!j.access_token) throw new Error('FCM auth failed');
  fcmCache = { token: j.access_token, exp: Date.now() + (j.expires_in || 3600) * 1000 };
  return fcmCache.token;
}

// Отправляет data-сообщение на один токен. Возвращает 'ok' | 'dead' (токен недействителен) | 'fail'.
async function fcmSend(env, token, data) {
  if (!env.FCM_SERVICE_ACCOUNT) return 'fail';
  try {
    const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT);
    const at = await fcmAccessToken(sa);
    const strData = {};
    for (const k of Object.keys(data)) strData[k] = String(data[k]);   // FCM принимает только строки
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
      method: 'POST',
      headers: { authorization: 'Bearer ' + at, 'content-type': 'application/json' },
      body: JSON.stringify({ message: { token, data: strData, android: { priority: 'HIGH' } } }),
    });
    if (r.ok) return 'ok';
    if (r.status === 401) fcmCache = null;
    if (r.status === 404) return 'dead';
    const j = await r.json().catch(() => ({}));
    return JSON.stringify(j).includes('UNREGISTERED') ? 'dead' : 'fail';
  } catch (e) {
    return 'fail';
  }
}

async function pushToAdmins(env, data) {
  if (!env.FCM_SERVICE_ACCOUNT || !env.ADMIN_CODE) return;
  const fp = await sha256('fp:' + env.ADMIN_CODE);
  const { results } = await env.DB.prepare('SELECT token FROM push_admin WHERE code_fp = ? LIMIT 20').bind(fp).all();
  for (const row of results) {
    if ((await fcmSend(env, row.token, data)) === 'dead') {
      await env.DB.prepare('DELETE FROM push_admin WHERE token = ?').bind(row.token).run();
    }
  }
}

async function pushToUser(env, device, data) {
  if (!env.FCM_SERVICE_ACCOUNT) return;
  const row = await env.DB.prepare('SELECT token FROM push_user WHERE device = ?').bind(device).first();
  if (!row) return;
  if ((await fcmSend(env, row.token, data)) === 'dead') {
    await env.DB.prepare('DELETE FROM push_user WHERE device = ?').bind(device).run();
  }
}

// Приложение сообщает свой токен FCM. Пользователь подтверждает секретом устройства — чужой токен подставить нельзя.
async function pushRegister(body, env) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  const token = String(body.token || '');
  if (!UUID.test(device) || secret.length < 16 || token.length < 20 || token.length > 4096) return err('Некорректный запрос');
  const hash = await sha256(secret);
  const row = await env.DB.prepare('SELECT secret_hash FROM push_user WHERE device = ?').bind(device).first();
  const th = await env.DB.prepare('SELECT secret_hash FROM threads WHERE device = ?').bind(device).first();
  if ((row && row.secret_hash !== hash) || (th && th.secret_hash !== hash)) return err('Нет доступа', 403);
  await env.DB.prepare(
    'INSERT INTO push_user (device, secret_hash, token, ts) VALUES (?, ?, ?, ?) ON CONFLICT(device) DO UPDATE SET token = excluded.token, ts = excluded.ts'
  ).bind(device, hash, token, Date.now()).run();
  return json({ ok: true });
}

async function adminPushRegister(body, env) {
  const token = String(body.token || '');
  if (token.length < 20 || token.length > 4096) return err('Некорректный запрос');
  const fp = await sha256('fp:' + env.ADMIN_CODE);
  await env.DB.prepare(
    'INSERT INTO push_admin (token, code_fp, ts) VALUES (?, ?, ?) ON CONFLICT(token) DO UPDATE SET code_fp = excluded.code_fp, ts = excluded.ts'
  ).bind(token, fp, Date.now()).run();
  return json({ ok: true });
}

async function adminPushUnregister(body, env) {
  await env.DB.prepare('DELETE FROM push_admin WHERE token = ?').bind(String(body.token || '')).run();
  return json({ ok: true });
}

// ---------- клиент ----------
// Диагностика и аналитика из приложения: принимаем только JSON-объект разумного размера, иначе игнорируем (сообщение всё равно уходит)
function cleanDiag(v) {
  const s = typeof v === 'string' ? v : '';
  if (!s || s.length > MAX_DIAG) return '';
  try {
    const o = JSON.parse(s);
    return o && typeof o === 'object' && !Array.isArray(o) ? JSON.stringify(o) : '';
  } catch (e) { return ''; }
}

async function userSend(body, env, ctx, request) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  const text = String(body.text || '').trim().slice(0, 1000);
  const image = cleanImage(body.image);
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  if (image === null) return err('Фото слишком большое или повреждено');
  if (!text && !image) return err('Пустое сообщение');
  const hash = await sha256(secret);
  const blockedBy = await isBlocked(env, device, (request.headers.get('cf-connecting-ip') || '').slice(0, 64));
  if (blockedBy) return err('Доступ к поддержке ограничен', 403);
  const nick = String(body.nick || '').replace(/^@/, '').trim().slice(0, 40);
  const order = String(body.order || '').trim().slice(0, 40);
  const diag = cleanDiag(body.diag);
  const now = Date.now();

  // что известно об отправителе: IP и страна/город — от Cloudflare, модель и версии — от приложения
  const ip = (request.headers.get('cf-connecting-ip') || 'unknown').slice(0, 64);
  const cf = request.cf || {};
  const geo = [cf.country, cf.city].filter(Boolean).join(', ').slice(0, 80);
  const dev = String(body.info || '').replace(/[\r\n]+/g, ' ').trim().slice(0, 300) ||
    (request.headers.get('user-agent') || '').slice(0, 300);
  const shown = text || '📷 Фото';

  let t = await env.DB.prepare('SELECT secret_hash, last_user_ts FROM threads WHERE device = ?').bind(device).first();
  // чужому устройству отказываем до подсчёта лимитов; новый чат и частота сообщений проверяются здесь
  if (t && t.secret_hash !== hash) return err('Нет доступа', 403);
  const limited = await chatRateLimit(env, device, ip, !!image, !t);
  if (limited) return err(limited, 429);
  if (!t) {
    await env.DB.prepare(
      `INSERT INTO threads (device, secret_hash, nick, last_order, last_text, last_ts, last_user_ts, unread, created)
       VALUES (?, ?, ?, ?, '', ?, 0, 0, ?)`
    ).bind(device, hash, nick, order, now, now).run();
    t = { secret_hash: hash, last_user_ts: 0 };
  }
  if (t.secret_hash !== hash) return err('Нет доступа', 403);
  if (now - (t.last_user_ts || 0) < 1500) return err('Слишком часто, подождите секунду', 429);

  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO messages (device, sender, text, ts, image, ip, dev, geo) VALUES (?, 'user', ?, ?, ?, ?, ?, ?)`
    ).bind(device, text, now, image, ip, dev, geo),
    env.DB.prepare(
      `UPDATE threads SET nick = CASE WHEN ? != '' THEN ? ELSE nick END,
         last_order = CASE WHEN ? != '' THEN ? ELSE last_order END,
         last_text = ?, last_ts = ?, last_user_ts = ?, unread = unread + 1, last_ip = ?, last_dev = ?,
         status = CASE WHEN status = 'done' THEN 'new' ELSE status END,
         diag = CASE WHEN ? != '' THEN ? ELSE diag END, diag_ts = CASE WHEN ? != '' THEN ? ELSE diag_ts END WHERE device = ?`
    ).bind(nick, nick, order, order, shown, now, now, ip, dev, diag, diag, diag, now, device),
  ]);

  // уведомление в Telegram (по желанию)
  if (env.BOT_TOKEN && env.CHAT_ID) {
    const note =
      `💬 Новое сообщение в поддержку\nОт: ${nick ? '@' + nick : 'гость'}` +
      `${order ? '\nЗаказ: ' + order : ''}\nIP: ${ip}${geo ? ' (' + geo + ')' : ''}\nУстройство: ${dev || '—'}` +
      `\n\n${text || '📷 Фото'}\n\nОтветьте в приложении (админ-панель).`;
    ctx.waitUntil(telegramNote(env, note, image).catch(() => {}));
  }
  // push админу (если настроен FCM)
  ctx.waitUntil(pushToAdmins(env, { type: 'admin', nick, text: shown.slice(0, 160), ts: now }).catch(() => {}));
  return json({ ok: true });
}

async function userMessages(body, env) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  const t = await env.DB.prepare('SELECT secret_hash, admin_read_ts, solved, status FROM threads WHERE device = ?').bind(device).first();
  if (!t) return json({ messages: [], read_ts: 0, solved: 0, status: 'new', rating: 0 });
  if (t.secret_hash !== (await sha256(secret))) return err('Нет доступа', 403);
  const { results } = await env.DB.prepare(MSG_SQL).bind(device, Number(body.after) || 0).all();
  // пользователь открыл чат — ответы админа прочитаны (пишем в базу, только если есть что отметить)
  await env.DB.prepare(
    `UPDATE threads SET user_read_ts = ? WHERE device = ?
       AND user_read_ts < (SELECT COALESCE(MAX(ts), 0) FROM messages WHERE device = ? AND sender = 'admin')`
  ).bind(Date.now(), device, device).run();
  // оценка текущего «Решено»: 0 — ещё не оценивали (приложение тогда показывает «Оцените помощь»)
  const rt = (t.solved || 0) > 0
    ? await env.DB.prepare('SELECT stars FROM support_ratings WHERE device = ? AND round = ?').bind(device, t.solved).first()
    : null;
  return json({
    messages: results, read_ts: t.admin_read_ts || 0, solved: t.solved || 0,
    status: STATUSES.includes(t.status) ? t.status : 'new', rating: rt ? rt.stars : 0,
  });
}

// Пользователь оценивает решённое обращение (1–5 и короткий отзыв). Одна оценка на каждое «Решено»; её можно изменить.
async function userRate(body, env, ctx) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  const t = await env.DB.prepare('SELECT secret_hash, status, solved, nick FROM threads WHERE device = ?').bind(device).first();
  if (!t) return err('Чат не найден', 404);
  if (t.secret_hash !== (await sha256(secret))) return err('Нет доступа', 403);
  if (t.status !== 'done' || !(t.solved > 0)) return err('Оценить можно после того, как обращение решено');
  const stars = Math.round(Number(body.stars));
  if (!(stars >= 1 && stars <= 5)) return err('Поставьте оценку от 1 до 5');
  const comment = String(body.comment || '').replace(/\s+/g, ' ').trim().slice(0, RATING_COMMENT_MAX);
  await env.DB.prepare(
    `INSERT INTO support_ratings (device, round, stars, comment, ts) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(device, round) DO UPDATE SET stars = excluded.stars, comment = excluded.comment, ts = excluded.ts`
  ).bind(device, t.solved, stars, comment, Date.now()).run();
  // низкая оценка — сразу в Telegram админу (если настроен)
  if (stars <= 2 && env.BOT_TOKEN && env.CHAT_ID) {
    const note = `⭐ Низкая оценка поддержки: ${stars}/5\nОт: ${t.nick ? '@' + t.nick : 'гость'}${comment ? '\n\n' + comment : ''}`;
    ctx.waitUntil(telegramNote(env, note, '').catch(() => {}));
  }
  return json({ ok: true, stars });
}

// Лёгкий опрос для фоновых уведомлений: сколько ответов админа пользователь ещё не прочитал.
// Ничего не помечает прочитанным (в отличие от /api/chat/messages).
async function userPoll(body, env) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  const t = await env.DB.prepare('SELECT secret_hash, user_read_ts, solved FROM threads WHERE device = ?').bind(device).first();
  if (!t) return json({ unread: 0, last_id: 0, text: '', solved: 0 });
  if (t.secret_hash !== (await sha256(secret))) return err('Нет доступа', 403);
  const n = await env.DB.prepare(
    `SELECT COUNT(*) AS n, COALESCE(MAX(id), 0) AS last_id FROM messages WHERE device = ? AND sender = 'admin' AND ts > ?`
  ).bind(device, t.user_read_ts || 0).first();
  let text = '';
  if (n && n.n > 0) {
    const m = await env.DB.prepare(`SELECT text, (image != '') AS has_image FROM messages WHERE id = ?`).bind(n.last_id).first();
    text = m ? (m.text || (m.has_image ? '📷 Фото' : '')).slice(0, 160) : '';
  }
  return json({ unread: n ? n.n : 0, last_id: n ? n.last_id : 0, text, solved: t.solved || 0 });
}

async function userImage(body, env) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  const t = await env.DB.prepare('SELECT secret_hash FROM threads WHERE device = ?').bind(device).first();
  if (!t || t.secret_hash !== (await sha256(secret))) return err('Нет доступа', 403);
  const m = await env.DB.prepare('SELECT image FROM messages WHERE id = ? AND device = ?').bind(Number(body.id) || 0, device).first();
  if (!m || !m.image) return err('Фото не найдено', 404);
  return json({ image: m.image });
}

// ---------- админ ----------
// LIKE-шаблон без спецсимволов пользователя
const likeOf = (q) => '%' + q.replace(/[\\%_]/g, (m) => '\\' + m) + '%';

async function isBlocked(env, device, ip) {
  const r = await env.DB.prepare(
    `SELECT kind FROM blocks WHERE (kind = 'device' AND value = ?) OR (kind = 'ip' AND value = ? AND ? != '' AND ? != 'unknown') LIMIT 1`
  ).bind(device, ip, ip, ip).first();
  return r ? r.kind : '';
}

async function adminThreads(body, env) {
  const where = [`last_text != ''`];
  const args = [];
  const st = String(body.status || '');
  if (STATUSES.includes(st)) { where.push('status = ?'); args.push(st); }
  if (body.unread) where.push('unread > 0');
  const q = String(body.q || '').trim().replace(/^@/, '').slice(0, 60);
  if (q) {
    // по нику, по номеру заказа (в карточке и в тексте сообщений)
    where.push(`(nick LIKE ? ESCAPE '\\' OR last_order LIKE ? ESCAPE '\\' OR device IN (SELECT device FROM messages WHERE text LIKE ? ESCAPE '\\'))`);
    const like = likeOf(q);
    args.push(like, like, like);
  }
  const { results } = await env.DB.prepare(
    `SELECT device, nick, last_order, last_text, last_ts, unread, last_ip, last_dev, status,
            (SELECT stars FROM support_ratings s WHERE s.device = threads.device ORDER BY ts DESC LIMIT 1) AS rating
       FROM threads WHERE ${where.join(' AND ')} ORDER BY last_ts DESC LIMIT 100`
  ).bind(...args).all();

  // подозрительные совпадения: сколько разных устройств писали с того же IP за 30 дней
  const ips = [...new Set(results.map((r) => r.last_ip).filter((x) => x && x !== 'unknown'))];
  const shared = {};
  for (let i = 0; i < ips.length; i += 80) {       // у D1 лимит ~100 параметров на запрос
    const part = ips.slice(i, i + 80);
    const { results: rows } = await env.DB.prepare(
      `SELECT ip, COUNT(DISTINCT device) AS n FROM messages WHERE ip IN (${part.map(() => '?').join(',')}) AND ts > ? GROUP BY ip`
    ).bind(...part, Date.now() - 30 * DAY).all();
    for (const r of rows) shared[r.ip] = r.n;
  }
  const { results: bl } = await env.DB.prepare('SELECT kind, value FROM blocks').all();
  const blDev = new Set(bl.filter((b) => b.kind === 'device').map((b) => b.value));
  const blIp = new Set(bl.filter((b) => b.kind === 'ip').map((b) => b.value));
  const threads = results.map((r) => ({
    ...r,
    status: STATUSES.includes(r.status) ? r.status : 'new',
    shared_ip: (shared[r.last_ip] || 0) > 1 ? shared[r.last_ip] : 0,
    blocked_device: blDev.has(r.device),
    blocked_ip: blIp.has(r.last_ip),
  }));

  const counts = { new: 0, work: 0, done: 0, unread: 0 };
  const { results: c1 } = await env.DB.prepare(`SELECT status, COUNT(*) AS n FROM threads WHERE last_text != '' GROUP BY status`).all();
  for (const r of c1) counts[STATUSES.includes(r.status) ? r.status : 'new'] += r.n;
  const c2 = await env.DB.prepare(`SELECT COUNT(*) AS n FROM threads WHERE last_text != '' AND unread > 0`).first();
  counts.unread = c2 ? c2.n : 0;
  return json({ threads, counts, rating: await ratingStats(env) });
}

// Сводка оценок поддержки: средняя, количество и сколько раз поставили каждое число звёзд
async function ratingStats(env) {
  const { results } = await env.DB.prepare('SELECT stars, COUNT(*) AS n FROM support_ratings GROUP BY stars').all();
  const dist = [0, 0, 0, 0, 0];
  let n = 0, sum = 0;
  for (const r of results) {
    if (r.stars >= 1 && r.stars <= 5) { dist[r.stars - 1] = r.n; n += r.n; sum += r.n * r.stars; }
  }
  return { n, avg: n ? Math.round((sum / n) * 100) / 100 : 0, dist };
}

// Последние оценки с отзывами (для админ-панели)
async function adminRatings(body, env) {
  const { results } = await env.DB.prepare(
    `SELECT s.device, s.stars, s.comment, s.ts, COALESCE(t.nick, '') AS nick
       FROM support_ratings s LEFT JOIN threads t ON t.device = s.device
      ORDER BY s.ts DESC LIMIT 50`
  ).all();
  return json({ ratings: results, stats: await ratingStats(env) });
}

// Что известно о пользователе: диагностика и аналитика из приложения + сводка по чату
async function adminProfile(body, env) {
  const device = String(body.device || '');
  if (!UUID.test(device)) return err('Некорректный запрос');
  const t = await env.DB.prepare('SELECT diag, diag_ts, created, last_ip, last_dev FROM threads WHERE device = ?').bind(device).first();
  if (!t) return err('Чат не найден', 404);
  let diag = null;
  try { diag = t.diag ? JSON.parse(t.diag) : null; } catch (e) { /* повреждённый блок не показываем */ }
  const m = await env.DB.prepare(`SELECT COUNT(*) AS n, MIN(ts) AS first_ts FROM messages WHERE device = ? AND sender = 'user'`).bind(device).first();
  const geo = await env.DB.prepare(`SELECT geo FROM messages WHERE device = ? AND sender = 'user' AND geo != '' ORDER BY id DESC LIMIT 1`).bind(device).first();
  return json({
    diag, diag_ts: t.diag_ts || 0, created: t.created || 0,
    messages: m ? m.n : 0, first_message: m ? m.first_ts || 0 : 0,
    last_ip: t.last_ip || '', geo: geo ? geo.geo : '',
    ratings: (await env.DB.prepare('SELECT stars, comment, ts FROM support_ratings WHERE device = ? ORDER BY ts DESC LIMIT 10').bind(device).all()).results,
  });
}

// Другие устройства, писавшие с тех же IP, что и этот чат (за 30 дней)
async function adminRelated(body, env) {
  const device = String(body.device || '');
  if (!UUID.test(device)) return err('Некорректный запрос');
  const since = Date.now() - 30 * DAY;
  const { results: mine } = await env.DB.prepare(
    `SELECT DISTINCT ip FROM messages WHERE device = ? AND ip != '' AND ip != 'unknown' AND ts > ? LIMIT 20`
  ).bind(device, since).all();
  if (!mine.length) return json({ related: [] });
  const ips = mine.map((r) => r.ip);
  const { results } = await env.DB.prepare(
    `SELECT m.device AS device, t.nick AS nick, m.ip AS ip, MAX(m.ts) AS ts
       FROM messages m JOIN threads t ON t.device = m.device
      WHERE m.ip IN (${ips.map(() => '?').join(',')}) AND m.device != ? AND m.ts > ?
      GROUP BY m.device, m.ip ORDER BY ts DESC LIMIT 20`
  ).bind(...ips, device, since).all();
  return json({ related: results });
}

// Для уведомлений админу: сколько чатов с непрочитанными сообщениями и какой писал последним.
async function adminPoll(env) {
  const c = await env.DB.prepare('SELECT COUNT(*) AS n FROM threads WHERE unread > 0').first();
  const last = await env.DB.prepare(
    'SELECT nick, last_text, last_ts FROM threads WHERE unread > 0 ORDER BY last_ts DESC LIMIT 1'
  ).first();
  return json({
    unread: c ? c.n : 0,
    last_ts: last ? last.last_ts : 0,
    nick: last ? last.nick || '' : '',
    text: last ? String(last.last_text || '').slice(0, 160) : '',
  });
}

async function adminSetStatus(body, env) {
  const device = String(body.device || '');
  const status = String(body.status || '');
  if (!UUID.test(device) || !STATUSES.includes(status)) return err('Некорректный запрос');
  // «Решено» считаем, только когда статус меняется на done с другого: повторные нажатия счётчик не накручивают
  await env.DB.prepare(
    `UPDATE threads SET solved = COALESCE(solved, 0) + CASE WHEN ? = 'done' AND COALESCE(status, 'new') != 'done' THEN 1 ELSE 0 END,
       status = ? WHERE device = ?`
  ).bind(status, status, device).run();
  return json({ ok: true });
}

// ---------- блокировки ----------
function blockTarget(body) {
  const kind = String(body.kind || '');
  const value = String(body.value || '').trim();
  if (kind === 'device' && UUID.test(value)) return { kind, value };
  if (kind === 'ip' && IP_RE.test(value) && value !== 'unknown') return { kind, value };
  return null;
}
async function adminBlock(body, env) {
  const b = blockTarget(body);
  if (!b) return err('Некорректный запрос');
  const reason = String(body.reason || '').trim().slice(0, 100);
  await env.DB.prepare(
    'INSERT INTO blocks (kind, value, reason, ts) VALUES (?, ?, ?, ?) ON CONFLICT(kind, value) DO UPDATE SET reason = excluded.reason, ts = excluded.ts'
  ).bind(b.kind, b.value, reason, Date.now()).run();
  return json({ ok: true });
}
async function adminUnblock(body, env) {
  const b = blockTarget(body);
  if (!b) return err('Некорректный запрос');
  await env.DB.prepare('DELETE FROM blocks WHERE kind = ? AND value = ?').bind(b.kind, b.value).run();
  return json({ ok: true });
}
async function adminBlocks(env) {
  const { results } = await env.DB.prepare(
    `SELECT b.kind AS kind, b.value AS value, b.reason AS reason, b.ts AS ts,
            (SELECT nick FROM threads t WHERE (b.kind = 'device' AND t.device = b.value) OR (b.kind = 'ip' AND t.last_ip = b.value) LIMIT 1) AS nick
       FROM blocks b ORDER BY b.ts DESC LIMIT 200`
  ).all();
  return json({ blocks: results });
}

// ---------- удалённые настройки: объявление, разделы, обязательное обновление ----------
const SECTION_KEYS = ['stars', 'premium', 'vpn', 'gifts', 'nft_buy', 'nft_rent', 'stars_sell', 'nft_sell'];
const DEFAULT_APP = {
  announcement: { active: false, text: '', level: 'info', id: 0 },
  sections: {},
  chat_maint: { on: false, reason: '' },   // технические работы вкладки «Чат» (общий чат и личные сообщения)
  min_version_code: 0,
  update_message: '',
};

async function loadApp(env) {
  const r = await env.DB.prepare(`SELECT v FROM settings WHERE k = 'app'`).first();
  if (!r) return structuredClone(DEFAULT_APP);
  try { return { ...structuredClone(DEFAULT_APP), ...JSON.parse(r.v) }; } catch (e) { return structuredClone(DEFAULT_APP); }
}

const cut = (v, n) => String(v == null ? '' : v).trim().slice(0, n);

function applyPatch(cur, patch) {
  const out = { ...cur };
  if (patch.announcement && typeof patch.announcement === 'object') {
    const a = patch.announcement;
    const text = cut(a.text, 300);
    out.announcement = { active: !!a.active && text.length > 0, text, level: a.level === 'warn' ? 'warn' : 'info', id: Date.now() };
  }
  if (patch.sections && typeof patch.sections === 'object') {
    const secs = {};
    for (const key of SECTION_KEYS) {
      const v = patch.sections[key];
      if (!v || typeof v !== 'object') continue;
      const s = {};
      if (typeof v.enabled === 'boolean') s.enabled = v.enabled;
      for (const [f, n] of [['title', 40], ['sub', 60], ['message', 100]]) { const t = cut(v[f], n); if (t) s[f] = t; }
      const url = cut(v.url, 200);
      if (/^https:\/\//.test(url)) s.url = url;   // только https-ссылки
      secs[key] = s;
    }
    out.sections = secs;
  }
  if (patch.chat_maint && typeof patch.chat_maint === 'object') {
    out.chat_maint = { on: !!patch.chat_maint.on, reason: cut(patch.chat_maint.reason, 300) };
  }
  if (patch.min_version_code !== undefined) {
    const n = Math.floor(Number(patch.min_version_code));
    out.min_version_code = Number.isFinite(n) ? Math.min(Math.max(n, 0), 1_000_000) : 0;
  }
  if (patch.update_message !== undefined) out.update_message = cut(patch.update_message, 200);
  return out;
}

async function adminSettings(env) { return json(await loadApp(env)); }
async function adminSaveSettings(body, env) {
  const next = applyPatch(await loadApp(env), body || {});
  await env.DB.prepare(`INSERT INTO settings (k, v) VALUES ('app', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v`)
    .bind(JSON.stringify(next)).run();
  MAINT_MEM.at = 0;   // новые настройки (в том числе тех. работы чата) подхватываются сразу
  return json(next);
}

// публично: приложение спрашивает при запуске и при открытии главного экрана
async function appConfig(env) {
  const a = await loadApp(env);
  return json({
    announcement: a.announcement.active ? { active: true, text: a.announcement.text, level: a.announcement.level, id: a.announcement.id } : { active: false },
    sections: a.sections,
    chat_maint: a.chat_maint && a.chat_maint.on ? { on: true, reason: a.chat_maint.reason || '' } : { on: false, reason: '' },
    min_version_code: a.min_version_code,
    update_message: a.update_message,
  });
}

// Тех. работы чата: пока включены, общий чат и личные сообщения не работают (поддержка и заказы не затронуты).
const CHAT_MAINT_PATHS = new Set(['/api/chat/register', '/api/chat/avatar_set', '/api/chat/avatar_get']);
// Флаг хранится в памяти воркера на MAINT_TTL: проверка стоит на каждом запросе чата, и без кэша каждый опрос читал бы настройки из D1.
const MAINT_TTL = 10_000;
const MAINT_MEM = { on: false, reason: '', at: 0 };
async function chatMaintenance(env, path) {
  if (!(path.startsWith('/api/room/') || path.startsWith('/api/dm/') || CHAT_MAINT_PATHS.has(path))) return null;
  if (Date.now() - MAINT_MEM.at > MAINT_TTL) {
    const a = await loadApp(env);
    MAINT_MEM.on = !!(a.chat_maint && a.chat_maint.on);
    MAINT_MEM.reason = (a.chat_maint && a.chat_maint.reason) || '';
    MAINT_MEM.at = Date.now();
  }
  if (!MAINT_MEM.on) return null;
  const reason = MAINT_MEM.reason;
  return json({ error: 'Чат на технических работах' + (reason ? ': ' + reason : ''), maintenance: true, reason }, 503);
}

// ---------- заказы приложения ----------
const MAX_ORDERS_PER_DEVICE = 500;
const ORDER_ID = /^[^\s]{1,80}$/;   // номер заказа на сайте: любые символы кроме пробелов
const clip = (v, n) => String(v == null ? '' : v).replace(/[\u0000-\u001F]/g, ' ').trim().slice(0, n);

// Приложение присылает свои новые заказы и смену их статуса. Устройство подтверждается секретом (как в чате и push):
// если секрет уже известен серверу (чат, push, прошлый заказ), он должен совпасть; иначе запоминается первый.
async function ordersReport(body, env) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  const list = Array.isArray(body.orders) ? body.orders.slice(0, 20) : [];
  if (!list.length) return json({ ok: true, saved: 0 });
  const hash = await sha256(secret);
  const known = await env.DB.prepare(
    `SELECT secret_hash FROM threads WHERE device = ? UNION ALL SELECT secret_hash FROM users WHERE device = ?
     UNION ALL SELECT secret_hash FROM push_user WHERE device = ? UNION ALL SELECT secret_hash FROM devices WHERE device = ?`
  ).bind(device, device, device, device).all();
  if (known.results.some((r) => r.secret_hash !== hash)) return err('Нет доступа', 403);
  const now = Date.now();
  if (!known.results.length) {
    await env.DB.prepare('INSERT OR IGNORE INTO devices (device, secret_hash, ts) VALUES (?, ?, ?)').bind(device, hash, now).run();
  }
  const have = await env.DB.prepare('SELECT COUNT(*) AS n FROM orders WHERE device = ?').bind(device).first();
  if (have && have.n >= MAX_ORDERS_PER_DEVICE) return err('Слишком много заказов', 429);
  const stmts = [];
  for (const o of list) {
    if (!o || typeof o !== 'object') continue;
    const id = String(o.id == null ? '' : o.id);
    if (!ORDER_ID.test(id)) continue;
    const created = Date.parse(String(o.created_at || ''));
    const qty = Math.floor(Number(o.quantity));
    const kind = Math.floor(Number(o.kind));
    // заказ с этим номером уже принадлежит другому устройству — не перезаписываем (WHERE в конце)
    stmts.push(env.DB.prepare(
      `INSERT INTO orders (id, device, order_number, username, product, quantity, total, currency, method, status, kind, created, updated)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET status = excluded.status, kind = excluded.kind, total = excluded.total, updated = excluded.updated
       WHERE orders.device = excluded.device`
    ).bind(
      id, device, clip(o.order_number, 40), clip(o.username, 64).replace(/^@/, ''), o.product === 'premium' ? 'premium' : 'stars',
      Number.isFinite(qty) ? Math.min(Math.max(qty, 0), 10_000_000) : 0, clip(o.total, 24), clip(o.currency, 8), clip(o.method, 60),
      clip(o.status, 40), kind >= 0 && kind <= 4 ? kind : 4, Number.isFinite(created) ? created : now, now
    ));
  }
  if (stmts.length) await env.DB.batch(stmts);
  return json({ ok: true, saved: stmts.length });
}

// Админка → «Заказы». filter: all | active | done | failed; before — показать заказы старше этого времени (мс).
async function adminOrders(body, env) {
  const filter = ['active', 'done', 'failed'].includes(body.filter) ? body.filter : 'all';
  let before = Number(body.before);
  if (!(before > 0) || before > 4102444800000) before = 4102444800000;
  const cond = { all: '1 = 1', active: 'o.kind NOT IN (2, 3)', done: 'o.kind = 2', failed: 'o.kind = 3' }[filter];
  const { results } = await env.DB.prepare(
    `SELECT o.id, o.order_number, o.username, o.product, o.quantity, o.total, o.currency, o.method, o.status, o.kind, o.created,
            COALESCE(NULLIF(t.nick, ''), u.nick, '') AS nick
       FROM orders o LEFT JOIN threads t ON t.device = o.device LEFT JOIN users u ON u.device = o.device
      WHERE ${cond} AND o.created < ? ORDER BY o.created DESC LIMIT 40`
  ).bind(before).all();
  const c = await env.DB.prepare(
    `SELECT COUNT(*) AS n, COALESCE(SUM(kind = 2), 0) AS done, COALESCE(SUM(kind = 3), 0) AS failed FROM orders`
  ).first();
  const n = c ? c.n : 0, done = c ? c.done : 0, failed = c ? c.failed : 0;
  return json({ orders: results, counts: { all: n, active: n - done - failed, done, failed } });
}

async function adminMessages(body, env) {
  const device = String(body.device || '');
  if (!UUID.test(device)) return err('Некорректный запрос');
  const { results } = await env.DB.prepare(MSG_SQL_ADMIN).bind(device, Number(body.after) || 0).all();
  const t = await env.DB.prepare('SELECT user_read_ts FROM threads WHERE device = ?').bind(device).first();
  // админ открыл чат — сообщения пользователя прочитаны (пишем, только если есть новое)
  await env.DB.prepare(
    'UPDATE threads SET unread = 0, admin_read_ts = ? WHERE device = ? AND (unread > 0 OR admin_read_ts < last_user_ts)'
  ).bind(Date.now(), device).run();
  return json({ messages: results, read_ts: t ? t.user_read_ts || 0 : 0 });
}

async function adminImage(body, env) {
  const device = String(body.device || '');
  if (!UUID.test(device)) return err('Некорректный запрос');
  const m = await env.DB.prepare('SELECT image FROM messages WHERE id = ? AND device = ?').bind(Number(body.id) || 0, device).first();
  if (!m || !m.image) return err('Фото не найдено', 404);
  return json({ image: m.image });
}

async function adminReply(body, env, ctx) {
  const device = String(body.device || '');
  const text = String(body.text || '').trim().slice(0, 1000);
  const image = cleanImage(body.image);
  if (!UUID.test(device)) return err('Некорректный запрос');
  if (image === null) return err('Фото слишком большое или повреждено');
  if (!text && !image) return err('Пустое сообщение');
  const t = await env.DB.prepare('SELECT device FROM threads WHERE device = ?').bind(device).first();
  if (!t) return err('Чат не найден', 404);
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare(`INSERT INTO messages (device, sender, text, ts, image) VALUES (?, 'admin', ?, ?, ?)`).bind(device, text, now, image),
    env.DB.prepare(
      `UPDATE threads SET last_text = ?, last_ts = ?, unread = 0, admin_read_ts = ?,
         status = CASE WHEN status = 'new' THEN 'work' ELSE status END WHERE device = ?`
    ).bind(text || '📷 Фото', now, now, device),
  ]);
  // push пользователю (если настроен FCM): id нужен, чтобы фоновый опрос не показал то же уведомление второй раз
  ctx.waitUntil((async () => {
    const m = await env.DB.prepare(`SELECT id FROM messages WHERE device = ? AND sender = 'admin' ORDER BY id DESC LIMIT 1`).bind(device).first();
    await pushToUser(env, device, { type: 'support', id: m ? m.id : 0, text: (text || '📷 Фото').slice(0, 160) });
  })().catch(() => {}));
  return json({ ok: true });
}

// ====================== общий чат, личные сообщения, ники, «галочка» ======================

// Ник: 3–20 символов, либо только латиница, либо только кириллица, плюс цифры и «_».
const NICK_LATIN = /^[A-Za-z0-9_]{3,20}$/;
const NICK_CYR = /^[А-Яа-яЁё0-9_]{3,20}$/;
const NICK_HAS_LETTER = /[A-Za-zА-Яа-яЁё]/;
// Похожие кириллические буквы приводятся к латинским: «Аdmin» и «Admin», «кот» и «kot» — один и тот же ник.
const LOOKALIKE = { 'а': 'a', 'в': 'b', 'е': 'e', 'ё': 'e', 'к': 'k', 'м': 'm', 'н': 'h', 'о': 'o', 'р': 'p', 'с': 'c', 'т': 't', 'у': 'y', 'х': 'x' };
const nickKey = (n) => [...String(n).toLowerCase()].map((c) => LOOKALIKE[c] || c).join('');
// Ники, которыми нельзя представляться: чтобы никто не выдавал себя за администрацию
const RESERVED_WORDS = ['admin', 'administrator', 'moderator', 'moder', 'support', 'system', 'pharmshop', 'админ', 'администратор', 'модератор', 'модер', 'поддержка', 'система', 'фармшоп'];
const RESERVED_KEYS = RESERVED_WORDS.map(nickKey);
const isReservedNick = (key) => RESERVED_KEYS.some((r) => key === r || key.startsWith(r));

const CHAT_USER_SQL = `SELECT id, device, secret_hash, nick, badge, muted_until, nick_changed, last_seen, avatar_v, crown FROM users WHERE device = ?`;

// Проверка пользователя чата. Возвращает { u, ip, now } или { e: Response }.
async function chatUser(body, env, request) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return { e: err('Некорректный запрос') };
  const ip = (request.headers.get('cf-connecting-ip') || 'unknown').slice(0, 64);
  if (await isBlocked(env, device, ip)) return { e: err('Доступ к чату ограничен', 403) };
  const u = await env.DB.prepare(CHAT_USER_SQL).bind(device).first();
  if (!u) return { e: err('Сначала выберите ник', 404) };
  if (u.secret_hash !== (await sha256(secret))) return { e: err('Нет доступа', 403) };
  const now = Date.now();
  if (now - (u.last_seen || 0) > 60_000) {
    await env.DB.prepare('UPDATE users SET last_seen = ?, last_ip = ? WHERE id = ?').bind(now, ip, u.id).run();
  }
  return { u, ip, now };
}

// То же, что chatUser, но для опросов: пользователь, уже проверенный по базе меньше AUTH_TTL назад (с тем же IP и секретом),
// проверяется без запросов к D1. Только для чтения; отправка, реакции и прочие записи используют chatUser.
async function chatUserFast(body, env, request) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return chatUser(body, env, request);
  const ip = (request.headers.get('cf-connecting-ip') || 'unknown').slice(0, 64);
  const now = Date.now();
  const c = AUTH_MEM.get(device);
  if (c && now - c.at < AUTH_TTL && c.ip === ip && c.hash === (await sha256(secret))) {
    if (now - c.seen > 60_000) {   // отметка «был в сети» — раз в минуту, как и раньше
      c.seen = now;
      await env.DB.prepare('UPDATE users SET last_seen = ?, last_ip = ? WHERE id = ?').bind(now, ip, c.u.id).run();
    }
    return { u: c.u, ip, now };
  }
  const a = await chatUser(body, env, request);
  if (!a.e) {
    if (AUTH_MEM.size > 2000) AUTH_MEM.clear();
    AUTH_MEM.set(device, { u: a.u, ip, hash: a.u.secret_hash, at: a.now, seen: a.now });
  }
  return a;
}

const meOf = (u, now) => ({ registered: true, uid: u.id, nick: u.nick, badge: u.badge ? 1 : 0, crown: u.crown ? 1 : 0, av: u.avatar_v || 0, muted_until: u.muted_until > now ? u.muted_until : 0 });
// Сколько сообщений человек написал (общий чат + личные): для достижения «за общение».
async function countMsgs(env, uid) {
  const r = await env.DB.prepare(
    'SELECT (SELECT COUNT(*) FROM room_messages WHERE uid = ?) + (SELECT COUNT(*) FROM dm_messages WHERE sender = ?) AS n'
  ).bind(uid, uid).first();
  return r ? r.n || 0 : 0;
}
const cleanChatText = (v) => String(v || '').replace(/\r\n?/g, '\n').replace(/\n{3,}/g, '\n\n').trim().slice(0, CHAT_TEXT_MAX);

async function chatMe(body, env, request) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  const ip = (request.headers.get('cf-connecting-ip') || 'unknown').slice(0, 64);
  if (await isBlocked(env, device, ip)) return err('Доступ к чату ограничен', 403);
  const u = await env.DB.prepare(CHAT_USER_SQL).bind(device).first();
  if (!u) return json({ registered: false });
  if (u.secret_hash !== (await sha256(secret))) return err('Нет доступа', 403);
  return json({ ...meOf(u, Date.now()), msgs: await countMsgs(env, u.id), nick_change_at: u.nick_changed ? u.nick_changed + NICK_CHANGE_MS : 0 });
}

// Проверка токена hCaptcha на сервере hCaptcha. Возвращает 'ok' | 'bad' | 'off' (нет секрета) | 'down' (hCaptcha недоступен).
async function verifyCaptcha(env, token) {
  if (!env.HCAPTCHA_SECRET) return 'off';
  if (!token || token.length > 6000) return 'bad';
  try {
    const form = new URLSearchParams({ secret: env.HCAPTCHA_SECRET, response: token, sitekey: HCAPTCHA_SITEKEY });
    const r = await fetch('https://api.hcaptcha.com/siteverify', { method: 'POST', body: form });
    const j = await r.json();
    return j && j.success === true ? 'ok' : 'bad';
  } catch (e) { return 'down'; }
}

// Занять ник или сменить свой (не чаще раза в 7 дней). Новому пользователю нужен токен капчи (captcha).
async function chatRegister(body, env, request) {
  const device = String(body.device || '');
  const secret = String(body.secret || '');
  const nick = String(body.nick || '').replace(/^@/, '').trim();
  if (!UUID.test(device) || secret.length < 16) return err('Некорректный запрос');
  if (!(NICK_LATIN.test(nick) || NICK_CYR.test(nick)) || !NICK_HAS_LETTER.test(nick)) {
    return err('Ник: 3–20 символов, только латиница или только кириллица, цифры и «_»');
  }
  const key = nickKey(nick);
  if (isReservedNick(key)) return err('Этот ник зарезервирован');
  const ip = (request.headers.get('cf-connecting-ip') || 'unknown').slice(0, 64);
  if (await isBlocked(env, device, ip)) return err('Доступ к чату ограничен', 403);
  const hash = await sha256(secret);
  const now = Date.now();

  const th = await env.DB.prepare('SELECT secret_hash FROM threads WHERE device = ?').bind(device).first();
  if (th && th.secret_hash !== hash) return err('Нет доступа', 403);
  const u = await env.DB.prepare(CHAT_USER_SQL).bind(device).first();
  const taken = 'Этот ник уже занят или слишком похож на чужой';

  if (u) {
    if (u.secret_hash !== hash) return err('Нет доступа', 403);
    if (nick === u.nick) return json({ ok: true, nick });
    if (u.nick_changed && now - u.nick_changed < NICK_CHANGE_MS) {
      const days = Math.ceil((NICK_CHANGE_MS - (now - u.nick_changed)) / DAY);
      return err(`Ник можно менять раз в 7 дней. Подождите ещё ${days} дн.`, 429);
    }
    const other = await env.DB.prepare('SELECT id FROM users WHERE nick_key = ? AND id != ?').bind(key, u.id).first();
    if (other) return err(taken, 409);
    try {
      await env.DB.prepare('UPDATE users SET nick = ?, nick_key = ?, nick_changed = ? WHERE id = ?').bind(nick, key, now, u.id).run();
    } catch (e) { return err(taken, 409); }   // гонка: UNIQUE сработал
    return json({ ok: true, nick });
  }

  const dayAgo = now - DAY;
  const c = await env.DB.prepare('SELECT COUNT(*) AS n FROM users WHERE last_ip = ? AND created > ?').bind(ip, dayAgo).first();
  if (c && c.n >= RATE_NEW_USERS_PER_IP) return err('Слишком много новых ников с вашего адреса. Попробуйте завтра', 429);
  if (await env.DB.prepare('SELECT 1 AS x FROM users WHERE nick_key = ?').bind(key).first()) return err(taken, 409);
  if (body.check === true) return json({ ok: true, check: true });   // приложение сперва проверяет ник и только потом показывает капчу
  // капча — после всех дешёвых проверок, чтобы токен (он одноразовый) не сгорал зря на занятом нике
  const captcha = String(body.captcha || '');
  if (!captcha) return err('Пройдите проверку «я не робот»', 400);
  const cv = await verifyCaptcha(env, captcha);
  if (cv === 'off') return err('Проверка на сервере не настроена (нет HCAPTCHA_SECRET)', 503);
  if (cv === 'down') return err('Не удалось проверить капчу. Попробуйте ещё раз', 503);
  if (cv !== 'ok') return err('Проверка не пройдена. Попробуйте ещё раз', 403);
  try {
    await env.DB.prepare(
      'INSERT INTO users (device, secret_hash, nick, nick_key, created, last_seen, last_ip) VALUES (?, ?, ?, ?, ?, ?, ?)'
    ).bind(device, hash, nick, key, now, now, ip).run();
  } catch (e) { return err(taken, 409); }       // двое заняли ник одновременно — второй получает отказ от UNIQUE
  return json({ ok: true, nick });
}

// ----- аватарки чата -----
// Приложение присылает ту же картинку, что стоит в профиле, уменьшенную до 128×128. Пустое поле image — убрать аватарку.
async function avatarSet(body, env, request) {
  const a = await chatUser(body, env, request);
  if (a.e) return a.e;
  const img = String(body.image || '');
  if (!img) {
    await env.DB.prepare(`UPDATE users SET avatar = '', avatar_v = 0 WHERE id = ?`).bind(a.u.id).run();
    return json({ ok: true, av: 0 });
  }
  if (img.length > MAX_AVATAR || !B64.test(img)) return err('Некорректная картинка');
  const v = a.now;
  await env.DB.prepare('UPDATE users SET avatar = ?, avatar_v = ? WHERE id = ?').bind(img, v, a.u.id).run();
  return json({ ok: true, av: v });
}

async function avatarGet(body, env, request) {
  const a = await chatUser(body, env, request);
  if (a.e) return a.e;
  const t = await env.DB.prepare('SELECT avatar, avatar_v FROM users WHERE id = ?').bind(Number(body.uid) || 0).first();
  return json({ av: t ? t.avatar_v || 0 : 0, image: t ? t.avatar || '' : '' });
}

// «Зелёная корона»: приложение сообщает, что у человека собраны все достижения (оно считает их у себя). Только украшение.
async function crownSet(body, env, request) {
  const a = await chatUser(body, env, request);
  if (a.e) return a.e;
  const v = body.crown === true || body.crown === 1 ? 1 : 0;
  if ((a.u.crown ? 1 : 0) !== v) { await env.DB.prepare('UPDATE users SET crown = ? WHERE id = ?').bind(v, a.u.id).run(); authDirty(); }
  return json({ ok: true, crown: v });
}

// ----- общий чат (одна комната) -----
// reply_to > 0 и q_id = null — оригинал, на который отвечали, уже удалён
const ROOM_SQL = `SELECT r.id, r.uid, COALESCE(u.nick, 'удалён') AS nick, COALESCE(u.badge, 0) AS badge, COALESCE(u.crown, 0) AS crown, COALESCE(u.avatar_v, 0) AS av, r.text, r.ts,
                         COALESCE(r.reply_to, 0) AS reply_to, q.id AS q_id, COALESCE(qu.nick, 'удалён') AS q_nick, substr(q.text, 1, 140) AS q_text
                    FROM room_messages r LEFT JOIN users u ON u.id = r.uid
                    LEFT JOIN room_messages q ON q.id = r.reply_to AND r.reply_to > 0
                    LEFT JOIN users qu ON qu.id = q.uid`;

// Реакции на последние 100 сообщений ленты: [{m: номер сообщения, e: эмодзи, n: сколько, me: 1 — это моя}].
// Приходят, когда у клиента устаревшая версия реакций (rx), поэтому реакции на старых сообщениях обновляются без перечитывания ленты.
async function roomReactions(env, uid) {
  const f = await env.DB.prepare('SELECT COALESCE(MIN(id), 0) AS m FROM (SELECT id FROM room_messages ORDER BY id DESC LIMIT 100)').first();
  const from = f ? f.m : 0;
  if (!from) return [];
  const { results } = await env.DB.prepare(
    `SELECT msg AS m, emoji AS e, COUNT(*) AS n, MAX(CASE WHEN uid = ? THEN 1 ELSE 0 END) AS me
       FROM room_reactions WHERE msg >= ? GROUP BY msg, emoji ORDER BY msg, MIN(ts)`
  ).bind(uid, from).all();
  return results;
}

// Состояние комнаты одним запросом: номер последнего сообщения, ревизия ленты (rev) и версия реакций (rx).
// Хранится в памяти воркера на ROOM_TTL: пока ничего не менялось, опросы клиентов обходятся без запросов к D1.
async function roomState(env, fresh = false) {
  if (!fresh && Date.now() - ROOM_MEM.at < ROOM_TTL) return { ...ROOM_MEM };
  const r = await env.DB.prepare(
    `SELECT COALESCE((SELECT MAX(id) FROM room_messages), 0) AS id,
            COALESCE((SELECT CAST(v AS INTEGER) FROM settings WHERE k = 'room_rev'), 0) AS rev,
            COALESCE((SELECT CAST(v AS INTEGER) FROM settings WHERE k = 'room_rx'), 0) AS rx`
  ).first();
  ROOM_MEM.id = r ? r.id || 0 : 0;
  ROOM_MEM.rev = r ? r.rev || 0 : 0;
  ROOM_MEM.rx = r ? r.rx || 0 : 0;
  ROOM_MEM.at = Date.now();
  return { ...ROOM_MEM };
}
// Любое удаление сообщений увеличивает «ревизию»: клиенты, увидев новое число, перечитывают ленту заново.
async function bumpRoomRev(env) {
  await env.DB.prepare(
    `INSERT INTO settings (k, v) VALUES ('room_rev', '1') ON CONFLICT(k) DO UPDATE SET v = CAST(v AS INTEGER) + 1`
  ).run();
  roomDirty();
}
// Любое изменение реакций увеличивает их версию (rx): клиенты получают реакции, только когда версия сменилась.
async function bumpRoomRx(env) {
  await env.DB.prepare(
    `INSERT INTO settings (k, v) VALUES ('room_rx', '1') ON CONFLICT(k) DO UPDATE SET v = CAST(v AS INTEGER) + 1`
  ).run();
  roomDirty();
}
async function roomFetch(env, after) {
  if (after > 0) {
    const { results } = await env.DB.prepare(`${ROOM_SQL} WHERE r.id > ? ORDER BY r.id LIMIT 100`).bind(after).all();
    return results;
  }
  const { results } = await env.DB.prepare(`${ROOM_SQL} ORDER BY r.id DESC LIMIT 100`).all();
  return results.reverse();
}

// Опрос общего чата. Клиент присылает after (последнее сообщение), rev (ревизия ленты) и rx (версия реакций).
// Если ничего не изменилось — отвечаем из памяти воркера без запросов к D1. Реакции приходят, только когда rx сменился
// (или клиент старый и rx не присылает — тогда как раньше, с каждым опросом).
async function roomMessages(body, env, request) {
  const a = await chatUserFast(body, env, request);
  if (a.e) return a.e;
  const st = await roomState(env);
  let after = Number(body.after) || 0;
  const hasRx = body.rx !== undefined;
  const reset = body.rev === undefined || Number(body.rev) !== st.rev;
  if (!reset && hasRx && Number(body.rx) === st.rx && after >= st.id) {
    return json({ messages: [], rev: st.rev, rx: st.rx, reset: false, me: meOf(a.u, a.now) });
  }
  if (reset) after = 0;
  const out = { messages: await roomFetch(env, after), rev: st.rev, rx: st.rx, reset, me: meOf(a.u, a.now) };
  if (reset || !hasRx || Number(body.rx) !== st.rx) out.reactions = await roomReactions(env, a.u.id);
  return json(out);
}

async function roomSend(body, env, request) {
  const a = await chatUser(body, env, request);
  if (a.e) return a.e;
  const { u, now } = a;
  const text = cleanChatText(body.text);
  if (!text) return err('Пустое сообщение');
  if (u.muted_until > now) {
    const mins = Math.ceil((u.muted_until - now) / 60000);
    return err(u.muted_until >= MUTE_FOREVER ? 'Вам закрыта отправка сообщений в общий чат' : `Вам закрыта отправка в общий чат ещё на ${mins} мин`, 403);
  }
  if (!u.badge) {
    const r = await env.DB.prepare('SELECT COUNT(*) AS n, MAX(ts) AS last FROM room_messages WHERE uid = ? AND ts > ?').bind(u.id, now - 60_000).first();
    if (r && r.n >= RATE_ROOM_PER_MIN) return err('Слишком много сообщений. Подождите минуту', 429);
    if (r && r.last && now - r.last < ROOM_COOLDOWN_MS) {
      const wait = ROOM_COOLDOWN_MS - (now - r.last);
      return json({ error: `Подождите ${Math.ceil(wait / 1000)} с до следующего сообщения`, retry_ms: wait }, 429);
    }
  }
  // ответ на сообщение: если оригинала уже нет, отправляем как обычное
  let replyTo = Number(body.reply_to) || 0;
  if (replyTo > 0) {
    const q = await env.DB.prepare('SELECT id FROM room_messages WHERE id = ?').bind(replyTo).first();
    if (!q) replyTo = 0;
  } else replyTo = 0;
  await env.DB.prepare('INSERT INTO room_messages (uid, text, ts, reply_to) VALUES (?, ?, ?, ?)').bind(u.id, text, now, replyTo).run();
  roomDirty();
  return json({ ok: true, msgs: await countMsgs(env, u.id), cooldown_ms: u.badge ? 0 : ROOM_COOLDOWN_MS });
}

// Удалить сообщение может автор или модератор («галочка»).
async function roomDelete(body, env, request) {
  const a = await chatUser(body, env, request);
  if (a.e) return a.e;
  const id = Number(body.id) || 0;
  const m = await env.DB.prepare('SELECT uid FROM room_messages WHERE id = ?').bind(id).first();
  if (!m) return json({ ok: true });
  if (m.uid !== a.u.id && !a.u.badge) return err('Нет прав', 403);
  await env.DB.batch([
    env.DB.prepare('DELETE FROM room_messages WHERE id = ?').bind(id),
    env.DB.prepare('DELETE FROM room_reactions WHERE msg = ?').bind(id),
  ]);
  await bumpRoomRev(env);
  return json({ ok: true });
}

// Реакция на сообщение. Та же реакция второй раз — снимается, другая — заменяет прежнюю. emoji = '' тоже снимает.
async function roomReact(body, env, request) {
  const a = await chatUser(body, env, request);
  if (a.e) return a.e;
  const { u, now } = a;
  if (u.muted_until > now) return err('Вам закрыта отправка сообщений в общий чат', 403);
  const id = Number(body.id) || 0;
  const raw = noVS(body.emoji);
  const emoji = raw ? REACTIONS.find((x) => noVS(x) === raw) : '';
  if (emoji === undefined) return err('Такой реакции нет');
  const m = await env.DB.prepare('SELECT id FROM room_messages WHERE id = ?').bind(id).first();
  if (!m) return err('Сообщение уже удалено', 404);
  const r = await env.DB.prepare('SELECT COUNT(*) AS n FROM room_reactions WHERE uid = ? AND ts > ?').bind(u.id, now - 60_000).first();
  if (r && r.n >= RATE_REACT_PER_MIN) return err('Слишком часто. Подождите минуту', 429);
  const cur = await env.DB.prepare('SELECT emoji FROM room_reactions WHERE msg = ? AND uid = ?').bind(id, u.id).first();
  if (!emoji || (cur && cur.emoji === emoji)) {
    await env.DB.prepare('DELETE FROM room_reactions WHERE msg = ? AND uid = ?').bind(id, u.id).run();
  } else {
    await env.DB.prepare(
      `INSERT INTO room_reactions (msg, uid, emoji, ts) VALUES (?, ?, ?, ?)
         ON CONFLICT(msg, uid) DO UPDATE SET emoji = excluded.emoji, ts = excluded.ts`
    ).bind(id, u.id, emoji, now).run();
  }
  await bumpRoomRx(env);
  const st = await roomState(env, true);   // версия читается до самих реакций: данные не старше неё
  return json({ ok: true, rx: st.rx, reactions: await roomReactions(env, u.id) });
}

// Модератор закрывает пользователю отправку в общий чат. minutes: 0 — снять, иначе из списка. Другого модератора заглушить нельзя.
async function roomMute(body, env, request) {
  const a = await chatUser(body, env, request);
  if (a.e) return a.e;
  if (!a.u.badge) return err('Нет прав', 403);
  const uid = Number(body.uid) || 0;
  const minutes = Number(body.minutes);
  if (![0, 60, 1440, 10080].includes(minutes)) return err('Некорректный запрос');
  const t = await env.DB.prepare('SELECT id, badge FROM users WHERE id = ?').bind(uid).first();
  if (!t) return err('Пользователь не найден', 404);
  if (t.id === a.u.id || t.badge) return err('Модератора может ограничить только администратор', 403);
  await env.DB.prepare('UPDATE users SET muted_until = ? WHERE id = ?').bind(minutes ? a.now + minutes * 60_000 : 0, uid).run();
  authDirty();
  return json({ ok: true });
}

// ----- личные сообщения -----
const pairOf = (x, y) => (x < y ? [x, y] : [y, x]);
const DM_SQL = `SELECT d.id, d.sender AS uid, COALESCE(u.nick, 'удалён') AS nick, COALESCE(u.badge, 0) AS badge, COALESCE(u.crown, 0) AS crown, COALESCE(u.avatar_v, 0) AS av, d.text, d.ts
                  FROM dm_messages d LEFT JOIN users u ON u.id = d.sender WHERE d.a = ? AND d.b = ?`;
// Номер последнего личного сообщения (по всем перепискам), в памяти воркера на DM_TTL.
async function dmState(env) {
  if (Date.now() - DM_MEM.at < DM_TTL) return { ...DM_MEM };
  const r = await env.DB.prepare('SELECT COALESCE(MAX(id), 0) AS id FROM dm_messages').first();
  DM_MEM.id = r ? r.id || 0 : 0;
  DM_MEM.at = Date.now();
  return { ...DM_MEM };
}
async function dmFetch(env, a, b, after) {
  if (after > 0) {
    const { results } = await env.DB.prepare(`${DM_SQL} AND d.id > ? ORDER BY d.id LIMIT 200`).bind(a, b, after).all();
    return results;
  }
  const { results } = await env.DB.prepare(`${DM_SQL} ORDER BY d.id DESC LIMIT 200`).bind(a, b).all();
  return results.reverse();
}

async function dmFind(body, env, request) {
  const a = await chatUser(body, env, request);
  if (a.e) return a.e;
  const nick = String(body.nick || '').replace(/^@/, '').trim().slice(0, 40);
  if (!nick) return err('Введите ник');
  const t = await env.DB.prepare('SELECT id, nick, badge, crown, avatar_v FROM users WHERE nick_key = ?').bind(nickKey(nick)).first();
  if (!t) return err('Пользователь не найден', 404);
  if (t.id === a.u.id) return err('Это вы');
  return json({ uid: t.id, nick: t.nick, badge: t.badge ? 1 : 0, crown: t.crown ? 1 : 0, av: t.avatar_v || 0 });
}

// Только число непрочитанных личных: дешёвый опрос для вкладки «Общий чат». Список переписок целиком — /api/dm/list.
// Клиент присылает mid — номер последнего личного сообщения из прошлого ответа; пока он не изменился, число то же (same: true).
async function dmUnread(body, env, request) {
  const a = await chatUserFast(body, env, request);
  if (a.e) return a.e;
  const st = await dmState(env);
  if (body.mid !== undefined && Number(body.mid) === st.id && st.id > 0) return json({ same: true, mid: st.id });
  const me = a.u.id;
  const r = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM dm_messages x
      WHERE x.sender != ? AND (x.a = ? OR x.b = ?)
        AND x.id > COALESCE((SELECT last_id FROM dm_reads r WHERE r.uid = ? AND r.peer = x.sender), 0)`
  ).bind(me, me, me, me).first();
  return json({ unread: r ? r.n || 0 : 0, mid: st.id });
}

async function dmList(body, env, request) {
  const a = await chatUserFast(body, env, request);
  if (a.e) return a.e;
  const me = a.u.id;
  const { results } = await env.DB.prepare(
    `SELECT p.peer AS uid, u.nick AS nick, u.badge AS badge, COALESCE(u.crown, 0) AS crown, COALESCE(u.avatar_v, 0) AS av, m.text AS text, m.ts AS ts, m.sender AS sender,
            (SELECT COUNT(*) FROM dm_messages x
              WHERE x.sender = p.peer AND ((x.a = ? AND x.b = p.peer) OR (x.b = ? AND x.a = p.peer))
                AND x.id > COALESCE((SELECT last_id FROM dm_reads r WHERE r.uid = ? AND r.peer = p.peer), 0)) AS unread
       FROM (SELECT CASE WHEN a = ? THEN b ELSE a END AS peer, MAX(id) AS last_id
               FROM dm_messages WHERE a = ? OR b = ? GROUP BY peer) p
       JOIN users u ON u.id = p.peer
       JOIN dm_messages m ON m.id = p.last_id
      ORDER BY p.last_id DESC LIMIT 100`
  ).bind(me, me, me, me, me, me).all();
  return json({ chats: results, unread: results.reduce((s, r) => s + (r.unread || 0), 0), mid: (await dmState(env)).id });
}

async function dmMessages(body, env, request) {
  const a = await chatUserFast(body, env, request);
  if (a.e) return a.e;
  const peerId = Number(body.with) || 0;
  const after = Number(body.after) || 0;
  const [x, y] = pairOf(a.u.id, peerId);
  // опрос открытой переписки, пока нигде в личных ничего нового нет: только «до какого сообщения прочитал собеседник»
  if (after > 0 && (await dmState(env)).id <= after) {
    const pr0 = await env.DB.prepare('SELECT last_id FROM dm_reads WHERE uid = ? AND peer = ?').bind(peerId, a.u.id).first();
    return json({ messages: [], peer_read: pr0 ? pr0.last_id : 0 });
  }
  const peer = await env.DB.prepare('SELECT id, nick, badge, crown, avatar_v FROM users WHERE id = ?').bind(peerId).first();
  if (!peer) return err('Пользователь не найден', 404);
  const messages = await dmFetch(env, x, y, after);
  // открыли переписку — всё, что написал собеседник, прочитано (отмечаем, только если это первая загрузка или пришло новое)
  const last = after > 0 && !messages.length ? null
    : await env.DB.prepare('SELECT MAX(id) AS m FROM dm_messages WHERE a = ? AND b = ? AND sender = ?').bind(x, y, peer.id).first();
  if (last && last.m) {
    await env.DB.prepare(
      'INSERT INTO dm_reads (uid, peer, last_id) VALUES (?, ?, ?) ON CONFLICT(uid, peer) DO UPDATE SET last_id = MAX(last_id, excluded.last_id)'
    ).bind(a.u.id, peer.id, last.m).run();
  }
  const pr = await env.DB.prepare('SELECT last_id FROM dm_reads WHERE uid = ? AND peer = ?').bind(peer.id, a.u.id).first();
  return json({ messages, peer: { uid: peer.id, nick: peer.nick, badge: peer.badge ? 1 : 0, crown: peer.crown ? 1 : 0, av: peer.avatar_v || 0 }, peer_read: pr ? pr.last_id : 0 });
}

async function dmSend(body, env, request) {
  const a = await chatUser(body, env, request);
  if (a.e) return a.e;
  const text = cleanChatText(body.text);
  if (!text) return err('Пустое сообщение');
  const to = Number(body.to) || 0;
  if (to === a.u.id) return err('Нельзя писать самому себе');
  const peer = await env.DB.prepare('SELECT id FROM users WHERE id = ?').bind(to).first();
  if (!peer) return err('Пользователь не найден', 404);
  const r = await env.DB.prepare('SELECT COUNT(*) AS n, MAX(ts) AS last FROM dm_messages WHERE sender = ? AND ts > ?').bind(a.u.id, a.now - 60_000).first();
  if (r && r.n >= RATE_DM_PER_MIN) return err('Слишком много сообщений. Подождите минуту', 429);
  if (r && r.last && a.now - r.last < 700) return err('Слишком часто, подождите секунду', 429);
  const [x, y] = pairOf(a.u.id, peer.id);
  await env.DB.prepare('INSERT INTO dm_messages (a, b, sender, text, ts) VALUES (?, ?, ?, ?, ?)').bind(x, y, a.u.id, text, a.now).run();
  dmDirty();
  return json({ ok: true, msgs: await countMsgs(env, a.u.id) });
}

// ----- админ: пользователи, галочки, личные чаты -----
const USERS_PAGE = 50;

async function adminUsers(body, env) {
  const q = String(body.q || '').trim().replace(/^@/, '').slice(0, 40);
  const offset = Math.max(0, Math.floor(Number(body.offset) || 0));
  const where = q ? `WHERE u.nick LIKE ? ESCAPE '\\'` : '';
  const args = q ? [likeOf(q)] : [];
  const { results } = await env.DB.prepare(
    `SELECT u.id, u.nick, u.badge, u.muted_until, u.created, u.last_seen, u.last_ip, u.device,
            (SELECT COUNT(*) FROM room_messages r WHERE r.uid = u.id) AS room_msgs,
            (SELECT COUNT(*) FROM dm_messages d WHERE d.sender = u.id) AS dm_msgs
       FROM users u ${where} ORDER BY u.badge DESC, u.last_seen DESC LIMIT ? OFFSET ?`
  ).bind(...args, USERS_PAGE + 1, offset).all();
  const total = await env.DB.prepare(`SELECT COUNT(*) AS n, COALESCE(SUM(badge), 0) AS badges FROM users`).first();
  const { results: bl } = await env.DB.prepare(`SELECT value FROM blocks WHERE kind = 'device'`).all();
  const blocked = new Set(bl.map((b) => b.value));
  const now = Date.now();
  const users = results.slice(0, USERS_PAGE).map(({ device, ...u }) => ({
    ...u, muted_until: u.muted_until > now ? u.muted_until : 0, blocked_device: blocked.has(device),
  }));
  return json({ users, more: results.length > USERS_PAGE, total: total ? total.n : 0, badges: total ? total.badges : 0 });
}

async function adminUserBadge(body, env) {
  const uid = Number(body.uid) || 0;
  const r = await env.DB.prepare('UPDATE users SET badge = ? WHERE id = ?').bind(body.badge ? 1 : 0, uid).run();
  authDirty();
  if (!r.meta || !r.meta.changes) return err('Пользователь не найден', 404);
  return json({ ok: true });
}

// minutes: 0 — снять ограничение, -1 — навсегда
async function adminUserMute(body, env) {
  const uid = Number(body.uid) || 0;
  const m = Number(body.minutes);
  if (!Number.isFinite(m) || m < -1 || m > 525_600) return err('Некорректный запрос');
  const until = m === 0 ? 0 : m === -1 ? MUTE_FOREVER : Date.now() + m * 60_000;
  const r = await env.DB.prepare('UPDATE users SET muted_until = ? WHERE id = ?').bind(until, uid).run();
  authDirty();
  if (!r.meta || !r.meta.changes) return err('Пользователь не найден', 404);
  return json({ ok: true });
}

// Освободить ник: удаляет пользователя и его сообщения в общем чате. Личные переписки остаются у администрации.
async function adminUserReset(body, env) {
  const uid = Number(body.uid) || 0;
  await env.DB.batch([
    env.DB.prepare('DELETE FROM room_reactions WHERE uid = ? OR msg IN (SELECT id FROM room_messages WHERE uid = ?)').bind(uid, uid),
    env.DB.prepare('DELETE FROM room_messages WHERE uid = ?').bind(uid),
    env.DB.prepare('DELETE FROM dm_reads WHERE uid = ? OR peer = ?').bind(uid, uid),
    env.DB.prepare('DELETE FROM users WHERE id = ?').bind(uid),
  ]);
  authDirty();
  await bumpRoomRev(env);
  return json({ ok: true });
}

async function adminRoomMessages(body, env) {
  const st = await roomState(env);
  let after = Number(body.after) || 0;
  const hasRx = body.rx !== undefined;
  const reset = body.rev === undefined || Number(body.rev) !== st.rev;
  if (!reset && hasRx && Number(body.rx) === st.rx && after >= st.id) {
    return json({ messages: [], rev: st.rev, rx: st.rx, reset: false });
  }
  if (reset) after = 0;
  const out = { messages: await roomFetch(env, after), rev: st.rev, rx: st.rx, reset };
  if (reset || !hasRx || Number(body.rx) !== st.rx) out.reactions = await roomReactions(env, 0);
  return json(out);
}
async function adminRoomDelete(body, env) {
  const id = Number(body.id) || 0;
  await env.DB.batch([
    env.DB.prepare('DELETE FROM room_messages WHERE id = ?').bind(id),
    env.DB.prepare('DELETE FROM room_reactions WHERE msg = ?').bind(id),
  ]);
  await bumpRoomRev(env);
  return json({ ok: true });
}

// Список личных переписок (все пары пользователей)
async function adminDmThreads(body, env) {
  const q = String(body.q || '').trim().replace(/^@/, '').slice(0, 40);
  const like = q ? likeOf(q) : '';
  const { results } = await env.DB.prepare(
    `SELECT c.a AS a, c.b AS b, COALESCE(ua.nick, 'удалён') AS nick_a, COALESCE(ub.nick, 'удалён') AS nick_b,
            m.text AS text, m.ts AS ts, c.n AS n
       FROM (SELECT a, b, MAX(id) AS last_id, COUNT(*) AS n FROM dm_messages GROUP BY a, b) c
       JOIN dm_messages m ON m.id = c.last_id
       LEFT JOIN users ua ON ua.id = c.a
       LEFT JOIN users ub ON ub.id = c.b
      WHERE (? = '' OR ua.nick LIKE ? ESCAPE '\\' OR ub.nick LIKE ? ESCAPE '\\')
      ORDER BY c.last_id DESC LIMIT 100`
  ).bind(q, like, like).all();
  return json({ chats: results });
}

async function adminDmMessages(body, env) {
  const [x, y] = pairOf(Number(body.a) || 0, Number(body.b) || 0);
  return json({ messages: await dmFetch(env, x, y, Number(body.after) || 0) });
}

// ----- прокси личного кабинета VPN для PWA (в браузере прямые запросы к pvpn.vitrinka.net закрыты CORS) -----
// Запрос: { device, m, p, b, bearer, refresh, ref } -> ответ: { code, text, refresh }.
// refresh — значение cookie client_refresh: сервер подставляет его в запрос и отдаёт новое из Set-Cookie.
const VPN_BASE = 'https://pvpn.vitrinka.net';
const VPN_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
const VPN_PATH = /^\/api\/[A-Za-z0-9_\-./~%?&=+:,;@!$*()]*$/;
const VPN_TOKEN = /^[A-Za-z0-9._~+\/=-]{1,4000}$/;
const VPN_REF = /^\/[A-Za-z0-9_\-./]{0,100}$/;
async function vpnCall(body) {
  const device = String(body.device || '');
  if (!UUID.test(device)) return err('Некорректный запрос');
  const m = String(body.m || 'GET').toUpperCase();
  const p = String(body.p || '');
  if (!VPN_METHODS.includes(m)) return err('Некорректный запрос');
  if (!VPN_PATH.test(p) || p.length > 500 || p.includes('..') || p.includes('//')) return err('Некорректный адрес');
  const b = body.b == null ? null : String(body.b);
  if (b !== null && b.length > 100_000) return err('Слишком большой запрос', 413);
  const bearer = String(body.bearer || '');
  const refresh = String(body.refresh || '');
  if ((bearer && !VPN_TOKEN.test(bearer)) || (refresh && !VPN_TOKEN.test(refresh))) return err('Некорректный запрос');
  const ref = VPN_REF.test(String(body.ref || '')) ? String(body.ref) : '/app/plans';

  const headers = {
    Accept: 'application/json',
    'User-Agent': 'PharmShopApp/1.0',
    Origin: VPN_BASE,
    Referer: VPN_BASE + ref,
  };
  if (bearer) headers.Authorization = 'Bearer ' + bearer;
  if (refresh) headers.Cookie = 'client_refresh=' + refresh;
  if (b !== null) headers['Content-Type'] = 'application/json';

  let res, text;
  try {
    res = await fetch(VPN_BASE + p, {
      method: m,
      headers,
      body: b !== null && m !== 'GET' ? b : undefined,
      redirect: 'manual',
      signal: AbortSignal.timeout(20000),
    });
    text = await res.text();
  } catch (e) {
    return err('Сервер VPN недоступен. Попробуйте позже', 502);
  }
  let cookies = [];
  if (typeof res.headers.getSetCookie === 'function') cookies = res.headers.getSetCookie();
  else { const c = res.headers.get('set-cookie'); if (c) cookies = [c]; }
  const mm = /(?:^|[\s,])client_refresh=([^;,\s]*)/.exec(cookies.join('\n'));
  return json({ code: res.status, text, refresh: mm ? mm[1] : '' });
}

// ----- CORS: PWA открывается с другого адреса и ходит на воркер из браузера -----
const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'POST, GET, OPTIONS',
  'access-control-allow-headers': 'content-type',
  'access-control-max-age': '86400',
};

const app = {
  async fetch(request, env, ctx) {
    if (request.method === 'GET') return new Response('PharmShop chat OK');
    if (request.method !== 'POST') return err('Not found', 404);
    if (!env.DB) return err('База данных не подключена (переменная DB)', 500);
    if (Number(request.headers.get('content-length') || 0) > MAX_BODY) return err('Слишком большой запрос', 413);
    await ensureSchema(env.DB);
    const path = new URL(request.url).pathname;
    let body = {};
    try { body = await request.json(); } catch (e) { /* пустое тело */ }

    const maint = await chatMaintenance(env, path);
    if (maint) return maint;
    if (path === '/api/vpn/call') return vpnCall(body);
    if (path === '/api/orders/report') return ordersReport(body, env);
    if (path === '/api/chat/send') return userSend(body, env, ctx, request);
    if (path === '/api/chat/messages') return userMessages(body, env);
    if (path === '/api/chat/image') return userImage(body, env);
    if (path === '/api/chat/poll') return userPoll(body, env);
    if (path === '/api/chat/rate') return userRate(body, env, ctx);
    if (path === '/api/push/register') return pushRegister(body, env);
    if (path === '/api/app/config') return appConfig(env);
    if (path === '/api/chat/me') return chatMe(body, env, request);
    if (path === '/api/chat/register') return chatRegister(body, env, request);
    if (path === '/api/chat/avatar_set') return avatarSet(body, env, request);
    if (path === '/api/chat/avatar_get') return avatarGet(body, env, request);
    if (path === '/api/chat/crown') return crownSet(body, env, request);
    if (path === '/api/room/messages') return roomMessages(body, env, request);
    if (path === '/api/room/send') return roomSend(body, env, request);
    if (path === '/api/room/delete') return roomDelete(body, env, request);
    if (path === '/api/room/react') return roomReact(body, env, request);
    if (path === '/api/room/mute') return roomMute(body, env, request);
    if (path === '/api/dm/find') return dmFind(body, env, request);
    if (path === '/api/dm/list') return dmList(body, env, request);
    if (path === '/api/dm/unread') return dmUnread(body, env, request);
    if (path === '/api/dm/messages') return dmMessages(body, env, request);
    if (path === '/api/dm/send') return dmSend(body, env, request);

    if (path.startsWith('/api/admin/')) {
      const a = await adminAuth(request, env, path);
      if (a === 'locked') return err('Слишком много попыток. Попробуйте через 15 минут', 429);
      if (!a) return err('Неверный код', 401);
      if (path === '/api/admin/login') return adminLogin(env);
      if (path === '/api/admin/logout') return adminLogout(request, env);
      if (path === '/api/admin/threads') return adminThreads(body, env);
      if (path === '/api/admin/poll') return adminPoll(env);
      if (path === '/api/admin/push_register') return adminPushRegister(body, env);
      if (path === '/api/admin/push_unregister') return adminPushUnregister(body, env);
      if (path === '/api/admin/related') return adminRelated(body, env);
      if (path === '/api/admin/profile') return adminProfile(body, env);
      if (path === '/api/admin/status') return adminSetStatus(body, env);
      if (path === '/api/admin/ratings') return adminRatings(body, env);
      if (path === '/api/admin/orders') return adminOrders(body, env);
      if (path === '/api/admin/block') { authDirty(); return adminBlock(body, env); }
      if (path === '/api/admin/unblock') { authDirty(); return adminUnblock(body, env); }
      if (path === '/api/admin/blocks') return adminBlocks(env);
      if (path === '/api/admin/settings') return adminSettings(env);
      if (path === '/api/admin/settings_save') return adminSaveSettings(body, env);
      if (path === '/api/admin/messages') return adminMessages(body, env);
      if (path === '/api/admin/reply') return adminReply(body, env, ctx);
      if (path === '/api/admin/image') return adminImage(body, env);
      if (path === '/api/admin/users') return adminUsers(body, env);
      if (path === '/api/admin/user_badge') return adminUserBadge(body, env);
      if (path === '/api/admin/user_mute') return adminUserMute(body, env);
      if (path === '/api/admin/user_reset') return adminUserReset(body, env);
      if (path === '/api/admin/room_messages') return adminRoomMessages(body, env);
      if (path === '/api/admin/room_delete') return adminRoomDelete(body, env);
      if (path === '/api/admin/dm_threads') return adminDmThreads(body, env);
      if (path === '/api/admin/dm_messages') return adminDmMessages(body, env);
    }
    return err('Not found', 404);
  },
};

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    let res;
    try { res = await app.fetch(request, env, ctx); } catch (e) { res = err('Ошибка сервера', 500); }
    const h = new Headers(res.headers);
    for (const k in CORS) h.set(k, CORS[k]);
    return new Response(res.body, { status: res.status, statusText: res.statusText, headers: h });
  },
};
