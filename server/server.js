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
  makeInMemoryStore
} = require('@whiskeysockets/baileys');

const app = express();
const PORT = process.env.PORT || 3000;
const AUTH_DIR = path.join(__dirname, 'auth_info');

app.use(cors());
app.use(express.json({ limit: '5mb' }));

let sock = null;
let currentQR = null;
let currentQRDataUrl = null;
let connectionStatus = 'DISCONNECTED';
let sseClients = [];

// In-memory store — handles chat list, message history etc.
const store = makeInMemoryStore({ logger: pino({ level: 'silent' }) });

// Persistent message + chat cache on top of store
const chatsMap = new Map();
const messagesMap = new Map();

function broadcastSSE(event, data) {
  const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  sseClients = sseClients.filter(client => {
    try { client.write(payload); return true; } catch { return false; }
  });
}

async function startWhatsApp() {
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
    printQRInTerminal: true,
    browser: ['WhatsBB', 'Chrome', '120.0.0'],
    syncFullHistory: true,        // <-- Full chat history sync
    shouldSyncHistoryMessage: () => true
  });

  // Bind store to socket events
  store.bind(sock.ev);

  sock.ev.on('creds.update', saveCreds);

  sock.ev.on('connection.update', async (update) => {
    const { connection, lastDisconnect, qr } = update;

    if (qr) {
      currentQR = qr;
      connectionStatus = 'SCAN_QR';
      try {
        currentQRDataUrl = await qrcode.toDataURL(qr, { width: 400, margin: 2, color: { dark: '#000000', light: '#ffffff' } });
      } catch (e) { currentQRDataUrl = null; }
      console.log('[WhatsBB] QR Code ready. Scan with WhatsApp > Linked Devices > Link a Device');
      broadcastSSE('qr', { qr: currentQRDataUrl, raw: qr });
      broadcastSSE('status', { status: connectionStatus });
    }

    if (connection === 'open') {
      connectionStatus = 'CONNECTED';
      currentQR = null;
      currentQRDataUrl = null;
      const user = sock.user ? sock.user.id.split(':')[0] : 'Unknown';
      console.log(`[WhatsBB] Connected as +${user}! Syncing chat history...`);
      broadcastSSE('status', { status: connectionStatus, user: sock.user });

      // Load chats from store after connect
      setTimeout(() => {
        syncChatsFromStore();
      }, 3000);
    }

    if (connection === 'close') {
      const statusCode = lastDisconnect?.error?.output?.statusCode;
      const shouldReconnect = statusCode !== DisconnectReason.loggedOut;
      console.log(`[WhatsBB] Connection closed (${statusCode}). Reconnect: ${shouldReconnect}`);
      connectionStatus = 'DISCONNECTED';
      broadcastSSE('status', { status: connectionStatus });

      if (shouldReconnect) {
        setTimeout(startWhatsApp, 3000);
      } else {
        console.log('[WhatsBB] Logged out. Clearing auth...');
        try { fs.rmSync(AUTH_DIR, { recursive: true, force: true }); } catch {}
        setTimeout(startWhatsApp, 2000);
      }
    }
  });

  // Sync chats from store (triggered after chats.set fires)
  sock.ev.on('chats.set', ({ chats: newChats }) => {
    console.log(`[WhatsBB] Received ${newChats.length} chats from history sync`);
    for (const chat of newChats) {
      if (!chat.id || chat.id === 'status@broadcast') continue;
      const isGroup = chat.id.endsWith('@g.us');
      const name = chat.name || chat.verifiedName || (isGroup ? 'Group' : '+' + chat.id.split('@')[0]);
      const existing = chatsMap.get(chat.id) || {};
      chatsMap.set(chat.id, {
        id: chat.id,
        name,
        lastMessage: existing.lastMessage || chat.messages?.messages?.[0]?.message?.conversation || '',
        timestamp: chat.conversationTimestamp ? Number(chat.conversationTimestamp) * 1000 : (existing.timestamp || 0),
        unread: chat.unreadCount || 0,
        isGroup
      });
    }
    broadcastSSE('chats_synced', { count: chatsMap.size });
  });

  sock.ev.on('contacts.upsert', (contacts) => {
    for (const contact of contacts) {
      if (!contact.id) continue;
      if (chatsMap.has(contact.id)) {
        const chat = chatsMap.get(contact.id);
        const newName = contact.name || contact.notify || contact.verifiedName;
        if (newName) chat.name = newName;
      }
    }
  });

  sock.ev.on('messages.upsert', async ({ messages, type }) => {
    for (const msg of messages) {
      if (!msg.message) continue;
      const jid = msg.key.remoteJid;
      if (!jid || jid === 'status@broadcast') continue;

      const text =
        msg.message.conversation ||
        msg.message.extendedTextMessage?.text ||
        msg.message.imageMessage?.caption ||
        (msg.message.imageMessage ? '📷 Photo' : '') ||
        (msg.message.audioMessage ? '🎤 Voice note' : '') ||
        (msg.message.videoMessage ? '🎥 Video' : '') ||
        (msg.message.documentMessage ? '📄 ' + (msg.message.documentMessage.fileName || 'Document') : '') ||
        (msg.message.stickerMessage ? '🎭 Sticker' : '') ||
        '📎 Attachment';

      const fromMe = Boolean(msg.key.fromMe);
      const timestamp = (msg.messageTimestamp ? Number(msg.messageTimestamp) : Math.floor(Date.now() / 1000)) * 1000;
      const sender = msg.pushName || (fromMe ? 'You' : jid.split('@')[0]);

      if (!messagesMap.has(jid)) messagesMap.set(jid, []);
      const list = messagesMap.get(jid);
      const msgObj = { id: msg.key.id, fromMe, text, timestamp, sender };

      if (!list.some(m => m.id === msgObj.id)) {
        list.push(msgObj);
        list.sort((a, b) => a.timestamp - b.timestamp);
        if (list.length > 200) list.shift();
      }

      const isGroup = jid.endsWith('@g.us');
      const existing = chatsMap.get(jid) || {};
      chatsMap.set(jid, {
        id: jid,
        name: existing.name || msg.pushName || (isGroup ? 'Group' : '+' + jid.split('@')[0]),
        lastMessage: text,
        timestamp,
        unread: fromMe ? (existing.unread || 0) : (existing.unread || 0) + 1,
        isGroup
      });

      broadcastSSE('message', { jid, message: msgObj });
      broadcastSSE('chat_update', chatsMap.get(jid));
    }
  });

  // Also handle message history (history sync messages)
  sock.ev.on('messaging-history.set', ({ messages: histMsgs, chats: histChats }) => {
    console.log(`[WhatsBB] History sync: ${histMsgs?.length || 0} messages, ${histChats?.length || 0} chats`);

    if (histChats) {
      for (const chat of histChats) {
        if (!chat.id || chat.id === 'status@broadcast') continue;
        const isGroup = chat.id.endsWith('@g.us');
        const name = chat.name || (isGroup ? 'Group' : '+' + chat.id.split('@')[0]);
        const existing = chatsMap.get(chat.id) || {};
        if (!chatsMap.has(chat.id)) {
          chatsMap.set(chat.id, {
            id: chat.id,
            name,
            lastMessage: existing.lastMessage || '',
            timestamp: chat.conversationTimestamp ? Number(chat.conversationTimestamp) * 1000 : 0,
            unread: chat.unreadCount || 0,
            isGroup
          });
        }
      }
    }

    if (histMsgs) {
      for (const histMsg of histMsgs) {
        const msg = histMsg.message || histMsg;
        if (!msg || !msg.message) continue;
        const jid = msg.key?.remoteJid;
        if (!jid || jid === 'status@broadcast') continue;

        const text =
          msg.message.conversation ||
          msg.message.extendedTextMessage?.text ||
          msg.message.imageMessage?.caption ||
          (msg.message.imageMessage ? '📷 Photo' : '') ||
          (msg.message.audioMessage ? '🎤 Voice note' : '') ||
          (msg.message.videoMessage ? '🎥 Video' : '') ||
          (msg.message.documentMessage ? '📄 Document' : '') ||
          '📎 Attachment';

        const fromMe = Boolean(msg.key.fromMe);
        const timestamp = (msg.messageTimestamp ? Number(msg.messageTimestamp) : 0) * 1000;
        const sender = msg.pushName || (fromMe ? 'You' : jid.split('@')[0]);

        if (!messagesMap.has(jid)) messagesMap.set(jid, []);
        const list = messagesMap.get(jid);
        const msgObj = { id: msg.key.id, fromMe, text, timestamp, sender };
        if (!list.some(m => m.id === msgObj.id)) {
          list.push(msgObj);
        }

        // Update chat's last message with most recent
        const existing = chatsMap.get(jid) || {};
        if (!existing.timestamp || timestamp > existing.timestamp) {
          const isGroup = jid.endsWith('@g.us');
          chatsMap.set(jid, {
            ...existing,
            id: jid,
            name: existing.name || (isGroup ? 'Group' : '+' + jid.split('@')[0]),
            lastMessage: text,
            timestamp,
            isGroup
          });
        }
      }

      // Sort all message lists
      for (const [jid, list] of messagesMap.entries()) {
        list.sort((a, b) => a.timestamp - b.timestamp);
        if (list.length > 200) messagesMap.set(jid, list.slice(-200));
      }
    }
  });
}

function syncChatsFromStore() {
  const storeChats = store.chats?.all?.() || [];
  console.log(`[WhatsBB] Syncing ${storeChats.length} chats from in-memory store`);
  for (const chat of storeChats) {
    if (!chat.id || chat.id === 'status@broadcast') continue;
    if (chatsMap.has(chat.id)) continue; // don't overwrite richer data
    const isGroup = chat.id.endsWith('@g.us');
    chatsMap.set(chat.id, {
      id: chat.id,
      name: chat.name || (isGroup ? 'Group' : '+' + chat.id.split('@')[0]),
      lastMessage: '',
      timestamp: chat.conversationTimestamp ? Number(chat.conversationTimestamp) * 1000 : 0,
      unread: chat.unreadCount || 0,
      isGroup
    });
  }
}

// REST Endpoints
app.get('/api/status', (req, res) => {
  res.json({ status: connectionStatus, user: sock?.user || null });
});

app.get('/api/qr', (req, res) => {
  res.json({ status: connectionStatus, qr: currentQRDataUrl, raw: currentQR });
});

app.get('/api/chats', (req, res) => {
  const chats = Array.from(chatsMap.values())
    .filter(c => c.id && c.id !== 'status@broadcast')
    .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
  res.json({ chats });
});

app.get('/api/messages', (req, res) => {
  const { jid } = req.query;
  if (!jid) return res.status(400).json({ error: 'Missing jid' });
  const messages = messagesMap.get(jid) || [];
  if (chatsMap.has(jid)) chatsMap.get(jid).unread = 0;
  res.json({ jid, messages });
});

app.post('/api/send', async (req, res) => {
  const { jid, text } = req.body;
  if (!jid || !text) return res.status(400).json({ error: 'Missing jid or text' });
  if (connectionStatus !== 'CONNECTED' || !sock) return res.status(503).json({ error: 'Not connected' });

  try {
    const sent = await sock.sendMessage(jid, { text: text.trim() });
    const timestamp = Date.now();
    const msgObj = { id: sent.key.id, fromMe: true, text: text.trim(), timestamp, sender: 'You' };

    if (!messagesMap.has(jid)) messagesMap.set(jid, []);
    messagesMap.get(jid).push(msgObj);

    if (chatsMap.has(jid)) {
      const c = chatsMap.get(jid);
      c.lastMessage = text.trim();
      c.timestamp = timestamp;
    }

    res.json({ success: true, id: sent.key.id, timestamp });
  } catch (err) {
    console.error('[WhatsBB] Send error:', err);
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/logout', async (req, res) => {
  try {
    if (sock) await sock.logout();
    fs.rmSync(AUTH_DIR, { recursive: true, force: true });
    connectionStatus = 'DISCONNECTED';
    res.json({ success: true });
    setTimeout(startWhatsApp, 1500);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/events', (req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
  res.write('\n');
  sseClients.push(res);
  req.on('close', () => { sseClients = sseClients.filter(c => c !== res); });
});

app.get('/', (req, res) => {
  res.send(`<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
  <title>WhatsBB Bridge</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: -apple-system, 'Segoe UI', sans-serif; background: #111b21; color: #e9edef; display:flex; align-items:center; justify-content:center; min-height:100vh; }
    .card { background: #202c33; padding: 32px 28px; border-radius: 16px; max-width: 380px; width: 90%; text-align: center; box-shadow: 0 8px 32px rgba(0,0,0,0.4); }
    .logo { font-size: 32px; font-weight: 800; color: #00a884; letter-spacing: -1px; margin-bottom: 6px; }
    .subtitle { color: #8696a0; font-size: 13px; margin-bottom: 24px; }
    .status-chip { display: inline-block; padding: 4px 14px; border-radius: 99px; font-size: 13px; font-weight: 600; margin-bottom: 20px; }
    .CONNECTED { background: #00a884; color: #fff; }
    .SCAN_QR { background: #f0b429; color: #000; }
    .CONNECTING { background: #3b82f6; color: #fff; }
    .DISCONNECTED { background: #ef4444; color: #fff; }
    #qr-wrap { background: #fff; border-radius: 12px; padding: 12px; display: inline-block; margin: 8px 0 16px; }
    #qr-wrap img { display: block; }
    p { color: #8696a0; font-size: 13px; line-height: 1.6; }
    .stats { margin-top: 20px; font-size: 12px; color: #8696a0; }
  </style>
</head>
<body>
<div class="card">
  <div class="logo">WhatsBB</div>
  <div class="subtitle">BlackBerry 10 Companion Bridge</div>
  <div id="status" class="status-chip CONNECTING">Connecting...</div>
  <div id="qr-wrap" style="display:none"><img id="qr-img" width="260" height="260"/></div>
  <p id="hint">Starting WhatsApp engine...</p>
  <div class="stats" id="stats"></div>
</div>
<script>
  async function check() {
    try {
      const r = await fetch('/api/status'); const d = await r.json();
      const st = document.getElementById('status');
      st.className = 'status-chip ' + d.status;
      const labels = { CONNECTED:'● Connected', SCAN_QR:'Scan QR Code', CONNECTING:'Connecting…', DISCONNECTED:'Disconnected' };
      st.textContent = labels[d.status] || d.status;
      if (d.status === 'CONNECTED') {
        document.getElementById('qr-wrap').style.display = 'none';
        document.getElementById('hint').textContent = '✓ WhatsApp linked! Open WhatsBB on your BlackBerry.';
        if (d.user) document.getElementById('stats').textContent = 'Linked as +' + d.user.id.split(':')[0];
      } else if (d.status === 'SCAN_QR') {
        const qr = await fetch('/api/qr').then(r=>r.json());
        if (qr.qr) { document.getElementById('qr-img').src = qr.qr; document.getElementById('qr-wrap').style.display = 'inline-block'; }
        document.getElementById('hint').textContent = 'Open WhatsApp → Settings → Linked Devices → Link a Device';
      } else {
        document.getElementById('qr-wrap').style.display = 'none';
        document.getElementById('hint').textContent = d.status === 'CONNECTING' ? 'Starting WhatsApp engine…' : 'Disconnected — check console.';
      }
    } catch(e) { document.getElementById('hint').textContent = 'Cannot reach bridge server.'; }
  }
  setInterval(check, 2500); check();
</script>
</body>
</html>`);
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`\n[WhatsBB] Bridge Server on http://0.0.0.0:${PORT}`);
  console.log(`[WhatsBB] Web UI: http://localhost:${PORT}\n`);
  startWhatsApp();
});
