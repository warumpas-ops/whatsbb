const express = require('express');
const cors = require('cors');
const qrcode = require('qrcode');
const pino = require('pino');
const path = require('path');
const fs = require('fs');
const {
  default: makeWASocket,
  DisconnectReason,
  useMultiFileAuthState,
  fetchLatestBaileysVersion,
  makeCacheableSignalKeyStore,
  isJidGroup,
  isJidBroadcast,
  jidNormalizedUser,
  extractMessageContent,
  proto
} = require('@whiskeysockets/baileys');

const app = express();
const PORT = process.env.PORT || 3000;
const AUTH_DIR = path.join(__dirname, 'auth_info');
const DATA_DIR = path.join(__dirname, 'data');
const STORE_FILE = path.join(DATA_DIR, 'store.json');

app.use(cors());
app.use(express.json({ limit: '10mb' }));

let sock = null;
let currentQR = null;
let currentQRDataUrl = null;
let connectionStatus = 'DISCONNECTED';
let sseClients = [];
let reconnectTimer = null;

const chatsMap = new Map();    // jid -> chat object
const messagesMap = new Map(); // jid -> message[]
const contactsMap = new Map(); // jid -> { name }

// ─── Persistence (store.json) ────────────────────────────────────────────────
if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });

function loadStore() {
  if (!fs.existsSync(STORE_FILE)) return;
  try {
    const raw = fs.readFileSync(STORE_FILE, 'utf8');
    const data = JSON.parse(raw);
    if (data.contacts) {
      for (const [k, v] of Object.entries(data.contacts)) contactsMap.set(k, v);
    }
    if (data.chats) {
      for (const [k, v] of Object.entries(data.chats)) chatsMap.set(k, v);
    }
    if (data.messages) {
      for (const [k, v] of Object.entries(data.messages)) messagesMap.set(k, v);
    }
    console.log(`[WhatsBB] Loaded store: ${chatsMap.size} chats, ${messagesMap.size} threads, ${contactsMap.size} contacts`);
  } catch (err) {
    console.error('[WhatsBB] Failed to load store:', err.message);
  }
}

let saveTimeout = null;
function saveStore() {
  if (saveTimeout) clearTimeout(saveTimeout);
  saveTimeout = setTimeout(() => {
    try {
      const data = {
        contacts: Object.fromEntries(contactsMap),
        chats: Object.fromEntries(chatsMap),
        messages: Object.fromEntries(messagesMap),
        savedAt: Date.now()
      };
      const tmp = STORE_FILE + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(data), 'utf8');
      fs.renameSync(tmp, STORE_FILE);
    } catch (err) {
      console.error('[WhatsBB] Failed to save store:', err.message);
    }
  }, 1000);
}

// ─── SSE ──────────────────────────────────────────────────────────────────────
function broadcastSSE(event, data) {
  const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  sseClients = sseClients.filter(c => {
    try { c.write(payload); return true; } catch { return false; }
  });
}

// ─── Extract readable text from any WA message ───────────────────────────────
function extractText(rawMsg) {
  if (!rawMsg) return '';

  let msgContent = rawMsg.message || rawMsg;

  try {
    const extracted = extractMessageContent(msgContent);
    if (extracted) msgContent = extracted;
  } catch (e) {}

  if (!msgContent) return '';

  if (
    msgContent.protocolMessage ||
    msgContent.senderKeyDistributionMessage ||
    msgContent.messageContextInfo ||
    msgContent.reactionMessage ||
    msgContent.pollUpdateMessage ||
    msgContent.appStateSyncKeyShare ||
    msgContent.callLogMesssage
  ) return '';

  if (typeof msgContent.conversation === 'string' && msgContent.conversation.trim()) {
    return msgContent.conversation.trim();
  }
  if (msgContent.extendedTextMessage?.text) return msgContent.extendedTextMessage.text.trim();
  if (msgContent.imageMessage?.caption) return '📷 ' + msgContent.imageMessage.caption.trim();
  if (msgContent.imageMessage) return '📷 Photo';
  if (msgContent.videoMessage?.caption) return '🎥 ' + msgContent.videoMessage.caption.trim();
  if (msgContent.videoMessage) return '🎥 Video';
  if (msgContent.audioMessage) return '🎤 Voice note';
  if (msgContent.stickerMessage) return '🎭 Sticker';
  if (msgContent.documentMessage) return '📄 ' + (msgContent.documentMessage.fileName || 'Document');
  if (msgContent.contactMessage?.displayName) return '👤 ' + msgContent.contactMessage.displayName;
  if (msgContent.locationMessage) return '📍 Location';
  if (msgContent.pollCreationMessage) return '📊 ' + (msgContent.pollCreationMessage.name || 'Poll');
  if (msgContent.listMessage) return '📋 ' + (msgContent.listMessage.title || 'List');
  if (msgContent.buttonsMessage) return '📋 ' + (msgContent.buttonsMessage.contentText || 'Buttons');
  if (msgContent.contactsArrayMessage) return '👥 Contacts';
  if (msgContent.liveLocationMessage) return '📍 Live Location';

  return '';
}

// ─── Resolve display name for a JID ──────────────────────────────────────────
function resolveName(jid, pushName) {
  if (!jid) return 'Unknown';
  const normalized = jidNormalizedUser(jid);
  const contact = contactsMap.get(normalized);
  if (contact?.name) return contact.name;
  if (pushName && pushName.trim()) return pushName.trim();
  const num = jid.split('@')[0];
  return isJidGroup(jid) ? 'Group' : '+' + num;
}

// ─── Upsert helpers ──────────────────────────────────────────────────────────
function upsertMsg(jid, msgObj) {
  if (!messagesMap.has(jid)) messagesMap.set(jid, []);
  const list = messagesMap.get(jid);
  const idx = list.findIndex(m => m.id === msgObj.id);
  if (idx === -1) {
    list.push(msgObj);
  } else {
    list[idx] = msgObj;
  }
  list.sort((a, b) => a.timestamp - b.timestamp);
  if (list.length > 500) list.splice(0, list.length - 500);
  saveStore();
}

function upsertChat(jid, updates) {
  const current = chatsMap.get(jid) || {
    id: jid,
    name: resolveName(jid, null),
    lastMessage: '',
    timestamp: 0,
    unread: 0,
    isGroup: isJidGroup(jid)
  };
  chatsMap.set(jid, Object.assign({}, current, updates));
  saveStore();
}

// ─── Start WhatsApp Socket ───────────────────────────────────────────────────
async function startWhatsApp() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  // Safely cleanup previous socket if any
  if (sock) {
    try {
      sock.ev.removeAllListeners();
      sock.end();
    } catch (e) {}
    sock = null;
  }

  connectionStatus = 'CONNECTING';
  broadcastSSE('status', { status: connectionStatus });

  if (!fs.existsSync(AUTH_DIR)) fs.mkdirSync(AUTH_DIR, { recursive: true });

  const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR);
  const { version } = await fetchLatestBaileysVersion();
  const logger = pino({ level: 'silent' });

  sock = makeWASocket({
    version,
    auth: {
      creds: state.creds,
      keys: makeCacheableSignalKeyStore(state.keys, logger)
    },
    logger,
    browser: ['WhatsBB', 'Chrome', '124.0.0'],
    printQRInTerminal: false,
    syncFullHistory: true,
    shouldSyncHistoryMessage: () => true,
    markOnlineOnConnect: false,
    generateHighQualityLinkPreview: false,
    getMessage: async (key) => {
      const jid = key.remoteJid;
      const list = messagesMap.get(jid) || [];
      const found = list.find(m => m.id === key.id);
      return found ? { conversation: found.text } : undefined;
    }
  });

  sock.ev.on('creds.update', saveCreds);

  // ─── Connection Update ───────────────────────────────────────────────────
  sock.ev.on('connection.update', async ({ connection, lastDisconnect, qr }) => {
    if (qr) {
      currentQR = qr;
      connectionStatus = 'SCAN_QR';
      try {
        currentQRDataUrl = await qrcode.toDataURL(qr, {
          width: 400, margin: 2,
          color: { dark: '#000000', light: '#ffffff' }
        });
      } catch { currentQRDataUrl = null; }
      console.log('[WhatsBB] QR code generated → open http://localhost:' + PORT);
      broadcastSSE('qr', { qr: currentQRDataUrl });
      broadcastSSE('status', { status: connectionStatus });
    }

    if (connection === 'open') {
      connectionStatus = 'CONNECTED';
      currentQR = null;
      currentQRDataUrl = null;
      const num = sock.user?.id?.split(':')[0] || '?';
      console.log(`[WhatsBB] ✓ Connected as +${num}`);
      broadcastSSE('status', { status: connectionStatus, user: sock.user });

      // 1. Fetch all participating groups immediately
      try {
        console.log('[WhatsBB] Fetching participating groups...');
        const groups = await sock.groupFetchAllParticipating();
        const gids = Object.keys(groups);
        console.log(`[WhatsBB] Fetched ${gids.length} groups`);
        for (const [gid, meta] of Object.entries(groups)) {
          upsertChat(gid, {
            name: meta.subject || 'Group',
            isGroup: true,
            timestamp: meta.creation ? Number(meta.creation) * 1000 : Date.now()
          });
        }
      } catch (err) {
        console.log('[WhatsBB] Group fetch notice:', err.message);
      }

      // 2. Trigger app state resync to pull contacts and chats
      try {
        console.log('[WhatsBB] Triggering app state resync...');
        await sock.resyncAppState(['critical_block', 'critical_unblock_low', 'regular_high', 'regular_low', 'regular'], false);
      } catch (err) {
        console.log('[WhatsBB] App state resync notice:', err.message);
      }
    }

    if (connection === 'close') {
      const code = lastDisconnect?.error?.output?.statusCode;
      const shouldReconnect = code !== DisconnectReason.loggedOut;
      console.log(`[WhatsBB] Closed (code ${code}). Reconnecting: ${shouldReconnect}`);
      connectionStatus = 'DISCONNECTED';
      broadcastSSE('status', { status: connectionStatus });

      if (shouldReconnect) {
        // Use 4s delay to avoid conflict loops
        reconnectTimer = setTimeout(startWhatsApp, 4000);
      } else {
        try { fs.rmSync(AUTH_DIR, { recursive: true, force: true }); } catch {}
        reconnectTimer = setTimeout(startWhatsApp, 2000);
      }
    }
  });

  // ─── Contacts ─────────────────────────────────────────────────────────────
  sock.ev.on('contacts.set', ({ contacts }) => {
    console.log(`[WhatsBB] contacts.set: ${contacts.length} entries`);
    for (const c of contacts) {
      if (!c.id) continue;
      const name = c.name || c.notify || c.verifiedName;
      if (name) contactsMap.set(jidNormalizedUser(c.id), { name });
    }
    // Update existing chat names
    for (const [jid, chat] of chatsMap.entries()) {
      const contact = contactsMap.get(jidNormalizedUser(jid));
      if (contact?.name && !isJidGroup(jid)) {
        chat.name = contact.name;
      }
    }
    saveStore();
  });

  sock.ev.on('contacts.upsert', (contacts) => {
    for (const c of contacts) {
      if (!c.id) continue;
      const name = c.name || c.notify || c.verifiedName;
      if (name) {
        contactsMap.set(jidNormalizedUser(c.id), { name });
        const chat = chatsMap.get(c.id);
        if (chat && !isJidGroup(c.id)) chat.name = name;
      }
    }
    saveStore();
  });

  // ─── Chat list ────────────────────────────────────────────────────────────
  sock.ev.on('chats.set', ({ chats }) => {
    console.log(`[WhatsBB] chats.set: ${chats.length} chats`);
    for (const chat of chats) {
      if (!chat.id || isJidBroadcast(chat.id)) continue;
      upsertChat(chat.id, {
        name: resolveName(chat.id, chat.name || chat.verifiedName),
        timestamp: chat.conversationTimestamp ? Number(chat.conversationTimestamp) * 1000 : 0,
        unread: chat.unreadCount || 0
      });
    }
    broadcastSSE('chats_synced', { count: chatsMap.size });
  });

  sock.ev.on('chats.upsert', (chats) => {
    for (const chat of chats) {
      if (!chat.id || isJidBroadcast(chat.id)) continue;
      upsertChat(chat.id, {
        name: resolveName(chat.id, chat.name),
        timestamp: chat.conversationTimestamp ? Number(chat.conversationTimestamp) * 1000 : 0,
        unread: chat.unreadCount || 0
      });
    }
  });

  // ─── Full History Sync ────────────────────────────────────────────────────
  sock.ev.on('messaging-history.set', ({ messages: histMsgs, chats: histChats, contacts: histContacts, isLatest, syncType }) => {
    const msgCount = histMsgs?.length || 0;
    const chatCount = histChats?.length || 0;
    console.log(`[WhatsBB] messaging-history.set: ${msgCount} msgs, ${chatCount} chats, syncType=${syncType}, isLatest=${isLatest}`);

    // Contacts
    if (histContacts) {
      for (const c of histContacts) {
        if (!c.id) continue;
        const name = c.name || c.notify || c.verifiedName;
        if (name) contactsMap.set(jidNormalizedUser(c.id), { name });
      }
    }

    // Chats
    if (histChats) {
      for (const chat of histChats) {
        if (!chat.id || isJidBroadcast(chat.id)) continue;
        upsertChat(chat.id, {
          name: resolveName(chat.id, chat.name),
          timestamp: chat.conversationTimestamp ? Number(chat.conversationTimestamp) * 1000 : 0,
          unread: chat.unreadCount || 0
        });
      }
    }

    // Messages
    if (histMsgs) {
      let imported = 0;
      for (const webMsg of histMsgs) {
        const msgInfo = webMsg.message ? webMsg.message : webMsg;
        const jid = msgInfo?.key?.remoteJid;
        if (!jid || isJidBroadcast(jid)) continue;

        const text = extractText(msgInfo);
        if (!text) continue;

        const fromMe = Boolean(msgInfo.key?.fromMe);
        const rawTs = msgInfo.messageTimestamp;
        const timestamp = rawTs ? (typeof rawTs === 'object' && rawTs.toNumber
          ? rawTs.toNumber() * 1000
          : Number(rawTs) * 1000)
          : 0;
        if (!timestamp) continue;

        const sender = fromMe ? 'You' : resolveName(jid, msgInfo.pushName);
        const id = msgInfo.key?.id || (jid + '_' + timestamp);

        upsertMsg(jid, { id, fromMe, text, timestamp, sender });
        imported++;

        const chat = chatsMap.get(jid);
        if (!chat || timestamp > (chat.timestamp || 0)) {
          upsertChat(jid, {
            name: resolveName(jid, msgInfo.pushName),
            lastMessage: text,
            timestamp
          });
        }
      }
      console.log(`[WhatsBB] Imported ${imported}/${msgCount} messages. Total threads: ${messagesMap.size}`);
    }

    saveStore();
    broadcastSSE('history_synced', { chats: chatsMap.size, threads: messagesMap.size });
  });

  // ─── Live Messages ────────────────────────────────────────────────────────
  sock.ev.on('messages.upsert', async ({ messages, type }) => {
    for (const msg of messages) {
      const jid = msg.key?.remoteJid;
      if (!jid || isJidBroadcast(jid)) continue;
      if (!msg.message) continue;

      const text = extractText(msg);
      if (!text && type !== 'notify') continue;

      const fromMe = Boolean(msg.key.fromMe);
      const ts = msg.messageTimestamp;
      const timestamp = ts ? (typeof ts === 'object' && ts.toNumber
        ? ts.toNumber() * 1000 : Number(ts) * 1000)
        : Date.now();
      const sender = fromMe ? 'You' : resolveName(jid, msg.pushName);
      const id = msg.key.id || (jid + '_' + timestamp);
      const displayText = text || '📎';

      upsertMsg(jid, { id, fromMe, text: displayText, timestamp, sender });
      const currentUnread = chatsMap.get(jid)?.unread || 0;
      upsertChat(jid, {
        name: resolveName(jid, msg.pushName),
        lastMessage: displayText,
        timestamp,
        unread: fromMe ? currentUnread : currentUnread + 1
      });

      console.log(`[WhatsBB] Live msg from ${sender} in ${jid}: "${displayText}"`);

      if (type === 'notify') {
        broadcastSSE('message', { jid, message: { id, fromMe, text: displayText, timestamp, sender } });
        broadcastSSE('chat_update', chatsMap.get(jid));
      }
    }
  });
}

// ─── REST Endpoints ──────────────────────────────────────────────────────────
app.get('/api/status', (req, res) => {
  res.json({
    status: connectionStatus,
    user: sock?.user || null,
    stats: {
      chats: chatsMap.size,
      threads: messagesMap.size,
      contacts: contactsMap.size,
      totalMessages: Array.from(messagesMap.values()).reduce((sum, list) => sum + list.length, 0)
    }
  });
});

app.get('/api/qr', (req, res) => {
  res.json({ status: connectionStatus, qr: currentQRDataUrl });
});

app.get('/api/contacts', (req, res) => {
  const contacts = Array.from(contactsMap.entries())
    .map(([jid, data]) => ({ jid, name: data.name }))
    .sort((a, b) => (a.name || '').localeCompare(b.name || ''));
  res.json({ contacts });
});

app.get('/api/chats', (req, res) => {
  const chats = Array.from(chatsMap.values())
    .filter(c => c.id && !isJidBroadcast(c.id))
    .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
  res.json({ chats });
});

app.get('/api/messages', (req, res) => {
  const { jid } = req.query;
  if (!jid) return res.status(400).json({ error: 'Missing jid' });
  const messages = messagesMap.get(jid) || [];
  if (chatsMap.has(jid)) chatsMap.get(jid).unread = 0;
  res.json({ jid, messages, total: messages.length });
});

app.post('/api/send', async (req, res) => {
  const { jid, text } = req.body;
  if (!jid || !text) return res.status(400).json({ error: 'Missing jid or text' });
  if (connectionStatus !== 'CONNECTED' || !sock) {
    return res.status(503).json({ error: 'WhatsApp not connected. Status: ' + connectionStatus });
  }
  try {
    const sent = await sock.sendMessage(jid, { text: text.trim() });
    const timestamp = Date.now();
    const id = sent?.key?.id || (jid + '_' + timestamp);
    const msgObj = { id, fromMe: true, text: text.trim(), timestamp, sender: 'You' };
    upsertMsg(jid, msgObj);
    upsertChat(jid, { lastMessage: text.trim(), timestamp });
    console.log(`[WhatsBB] Sent message to ${jid}: "${text.trim()}"`);
    res.json({ success: true, id, timestamp });
  } catch (err) {
    console.error('[WhatsBB] Send error:', err.message);
    res.status(500).json({ error: err.message });
  }
});

// Request older history from primary phone on demand
app.post('/api/messages/fetch-history', async (req, res) => {
  const { jid, count } = req.body;
  if (!jid) return res.status(400).json({ error: 'Missing jid' });
  if (connectionStatus !== 'CONNECTED' || !sock) {
    return res.status(503).json({ error: 'WhatsApp not connected' });
  }
  try {
    const msgs = messagesMap.get(jid) || [];
    const oldest = msgs[0];
    const oldestKey = oldest
      ? { remoteJid: jid, fromMe: oldest.fromMe, id: oldest.id }
      : { remoteJid: jid, fromMe: false, id: '' };
    const oldestTs = oldest ? oldest.timestamp : Date.now();
    await sock.fetchMessageHistory(parseInt(count) || 50, oldestKey, oldestTs);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Full reset / fresh pairing to force WhatsApp to re-send all history
app.post('/api/logout', async (req, res) => {
  try {
    if (sock) {
      try { await sock.logout(); } catch {}
      try { sock.end(); } catch {}
      sock = null;
    }
    fs.rmSync(AUTH_DIR, { recursive: true, force: true });
    connectionStatus = 'DISCONNECTED';
    currentQR = null;
    currentQRDataUrl = null;
    console.log('[WhatsBB] Unlinked device session. Generating new pairing QR in 1s...');
    res.json({ success: true, message: 'Unlinked. Fresh QR code will be generated.' });
    setTimeout(startWhatsApp, 1200);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/events', (req, res) => {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive'
  });
  res.write('event: ping\ndata: {}\n\n');
  sseClients.push(res);
  req.on('close', () => { sseClients = sseClients.filter(c => c !== res); });
});

// ─── Bridge Web Dashboard ────────────────────────────────────────────────────
app.get('/', (req, res) => {
  res.send(`<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>WhatsBB Bridge Control</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #111b21; color: #e9edef; padding: 24px 16px; display: flex; justify-content: center; }
    .container { width: 100%; max-width: 640px; }
    .card { background: #202c33; border-radius: 12px; padding: 24px; margin-bottom: 16px; border: 1px solid #2a3942; }
    .header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
    .title { font-size: 22px; font-weight: 700; color: #00a884; }
    .badge { font-size: 12px; font-weight: 700; padding: 4px 12px; border-radius: 99px; text-transform: uppercase; }
    .CONNECTED { background: rgba(0,168,132,.2); color: #00a884; border: 1px solid #00a884; }
    .SCAN_QR { background: rgba(240,180,41,.2); color: #f0b429; border: 1px solid #f0b429; }
    .CONNECTING { background: rgba(59,130,246,.2); color: #3b82f6; border: 1px solid #3b82f6; }
    .DISCONNECTED { background: rgba(239,68,68,.2); color: #ef4444; border: 1px solid #ef4444; }
    .qr-box { text-align: center; padding: 16px; background: #111b21; border-radius: 8px; margin: 16px 0; }
    .qr-box img { background: #fff; padding: 12px; border-radius: 8px; max-width: 280px; }
    .stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin: 16px 0; }
    .stat-card { background: #111b21; padding: 12px; border-radius: 8px; text-align: center; }
    .stat-val { font-size: 20px; font-weight: 700; color: #00a884; }
    .stat-lbl { font-size: 11px; color: #8696a0; text-transform: uppercase; margin-top: 4px; }
    .btn { display: inline-block; padding: 10px 18px; border-radius: 6px; font-size: 13px; font-weight: 600; cursor: pointer; border: none; transition: .2s; text-decoration: none; text-align: center; }
    .btn-teal { background: #00a884; color: #111b21; }
    .btn-teal:hover { background: #029070; }
    .btn-danger { background: #ea4335; color: #fff; }
    .btn-danger:hover { background: #c53427; }
    .btn-sec { background: #2a3942; color: #e9edef; }
    .btn-sec:hover { background: #37474f; }
    .actions { display: flex; gap: 10px; margin-top: 16px; flex-wrap: wrap; }
    .chat-preview { max-height: 280px; overflow-y: auto; background: #111b21; border-radius: 8px; margin-top: 12px; }
    .chat-row { display: flex; justify-content: space-between; padding: 10px 14px; border-bottom: 1px solid #202c33; font-size: 13px; }
    .chat-row:last-child { border-bottom: none; }
    .chat-name { font-weight: 600; color: #e9edef; }
    .chat-msg { color: #8696a0; font-size: 12px; margin-top: 2px; }
    .hint { color: #8696a0; font-size: 13px; line-height: 1.5; margin-top: 8px; }
  </style>
</head>
<body>
  <div class="container">
    <div class="card">
      <div class="header">
        <div>
          <div class="title">WhatsBB Bridge</div>
          <div style="font-size: 12px; color: #8696a0; margin-top: 2px;">BlackBerry 10 Companion Gateway</div>
        </div>
        <span id="badge" class="badge CONNECTING">CONNECTING</span>
      </div>

      <div class="stats-grid">
        <div class="stat-card"><div id="s-chats" class="stat-val">0</div><div class="stat-lbl">Chats</div></div>
        <div class="stat-card"><div id="s-threads" class="stat-val">0</div><div class="stat-lbl">Threads</div></div>
        <div class="stat-card"><div id="s-msgs" class="stat-val">0</div><div class="stat-lbl">Messages</div></div>
        <div class="stat-card"><div id="s-contacts" class="stat-val">0</div><div class="stat-lbl">Contacts</div></div>
      </div>

      <div id="qr-container" class="qr-box" style="display:none;">
        <p style="margin-bottom: 10px; font-weight: 600; font-size: 14px;">Scan with WhatsApp to link:</p>
        <img id="qr-img" alt="WhatsApp QR Code" />
        <p class="hint">WhatsApp → Settings → Linked Devices → Link a Device</p>
      </div>

      <div id="conn-info" class="hint" style="display:none;">
        ✓ Connected as <strong id="user-phone"></strong>. Messages and history are synced to local disk.
      </div>

      <div class="actions">
        <button id="btn-relink" class="btn btn-danger" onclick="relink()">Unlink & Re-scan QR (Full Sync)</button>
        <button class="btn btn-sec" onclick="poll()">Refresh</button>
      </div>
    </div>

    <div class="card">
      <div style="font-weight: 600; font-size: 15px; margin-bottom: 8px;">Synced Conversations</div>
      <div id="chat-list" class="chat-preview">
        <div style="padding: 16px; text-align: center; color: #8696a0; font-size: 13px;">No conversations yet.</div>
      </div>
    </div>
  </div>

  <script>
    async function poll() {
      try {
        const s = await fetch('/api/status').then(r => r.json());
        const badge = document.getElementById('badge');
        badge.className = 'badge ' + s.status;
        badge.textContent = s.status;

        if (s.stats) {
          document.getElementById('s-chats').textContent = s.stats.chats;
          document.getElementById('s-threads').textContent = s.stats.threads;
          document.getElementById('s-msgs').textContent = s.stats.totalMessages || 0;
          document.getElementById('s-contacts').textContent = s.stats.contacts;
        }

        const qrBox = document.getElementById('qr-container');
        const connInfo = document.getElementById('conn-info');

        if (s.status === 'SCAN_QR') {
          const q = await fetch('/api/qr').then(r => r.json());
          if (q.qr) {
            document.getElementById('qr-img').src = q.qr;
            qrBox.style.display = 'block';
          }
          connInfo.style.display = 'none';
        } else if (s.status === 'CONNECTED') {
          qrBox.style.display = 'none';
          connInfo.style.display = 'block';
          const num = s.user?.id ? '+' + s.user.id.split(':')[0] : 'Linked Device';
          document.getElementById('user-phone').textContent = num;
        } else {
          qrBox.style.display = 'none';
          connInfo.style.display = 'none';
        }

        // Load chats
        const cRes = await fetch('/api/chats').then(r => r.json());
        const listDiv = document.getElementById('chat-list');
        if (cRes.chats && cRes.chats.length > 0) {
          listDiv.innerHTML = cRes.chats.slice(0, 30).map(c => \`
            <div class="chat-row">
              <div>
                <div class="chat-name">\${c.name || c.id}</div>
                <div class="chat-msg">\${c.lastMessage || '<i>No messages</i>'}</div>
              </div>
              <div style="color: #667781; font-size: 11px;">\${c.timestamp ? new Date(c.timestamp).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'}) : ''}</div>
            </div>
          \`).join('');
        }
      } catch (err) {}
    }

    async function relink() {
      if (!confirm('This will unlink the current session and generate a fresh QR code so WhatsApp pushes full conversation history. Continue?')) return;
      await fetch('/api/logout', { method: 'POST' });
      alert('Unlinked! Generating fresh QR code...');
      setTimeout(poll, 1500);
    }

    setInterval(poll, 3000);
    poll();
  </script>
</body>
</html>`);
});

// Load persistent store then start server
loadStore();

app.listen(PORT, '0.0.0.0', () => {
  console.log(`\n╔══════════════════════════════════════════╗`);
  console.log(`║         WhatsBB Bridge Server            ║`);
  console.log(`║  Web UI: http://localhost:${PORT}             ║`);
  console.log(`║  BB10 app: http://192.168.1.5:${PORT}        ║`);
  console.log(`╚══════════════════════════════════════════╝\n`);
  startWhatsApp();
});
