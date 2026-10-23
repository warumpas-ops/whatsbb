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
  fetchLatestBaileysVersion
} = require('@whiskeysockets/baileys');

const app = express();
const PORT = process.env.PORT || 3000;
const AUTH_DIR = path.join(__dirname, 'auth_info');

app.use(cors());
app.use(express.json());

let sock = null;
let currentQR = null;
let currentQRDataUrl = null;
let connectionStatus = 'DISCONNECTED'; // DISCONNECTED, SCAN_QR, CONNECTED, CONNECTING
let sseClients = [];

// In-memory chat & message cache
const chatsMap = new Map();     // jid -> { id, name, lastMessage, timestamp, unread, isGroup }
const messagesMap = new Map();  // jid -> Array<{ id, fromMe, text, timestamp, sender }>

function broadcastSSE(event, data) {
  const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  sseClients.forEach(client => {
    try {
      client.write(payload);
    } catch (e) {
      // client disconnected
    }
  });
}

async function startWhatsApp() {
  connectionStatus = 'CONNECTING';
  broadcastSSE('status', { status: connectionStatus });

  const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR);
  const { version } = await fetchLatestBaileysVersion();

  sock = makeWASocket({
    version,
    auth: state,
    logger: pino({ level: 'silent' }),
    printQRInTerminal: true,
    browser: ['WhatsBB', 'BlackBerry 10', '1.0.0'],
    syncFullHistory: false
  });

  sock.ev.on('creds.update', saveCreds);

  sock.ev.on('connection.update', async (update) => {
    const { connection, lastDisconnect, qr } = update;

    if (qr) {
      currentQR = qr;
      connectionStatus = 'SCAN_QR';
      try {
        currentQRDataUrl = await qrcode.toDataURL(qr, { width: 300, margin: 2 });
      } catch (e) {
        currentQRDataUrl = null;
      }
      console.log('[WhatsBB] New QR Code generated. Ready to scan on BlackBerry or PC!');
      broadcastSSE('qr', { qr: currentQRDataUrl, raw: qr });
      broadcastSSE('status', { status: connectionStatus });
    }

    if (connection === 'open') {
      connectionStatus = 'CONNECTED';
      currentQR = null;
      currentQRDataUrl = null;
      const user = sock.user ? sock.user.id.split(':')[0] : 'Unknown';
      console.log(`[WhatsBB] WhatsApp Connected successfully as +${user}!`);
      broadcastSSE('status', { status: connectionStatus, user: sock.user });
    }

    if (connection === 'close') {
      const statusCode = lastDisconnect?.error?.output?.statusCode;
      const shouldReconnect = statusCode !== DisconnectReason.loggedOut;
      console.log(`[WhatsBB] Connection closed (code ${statusCode}). Reconnect: ${shouldReconnect}`);

      connectionStatus = 'DISCONNECTED';
      broadcastSSE('status', { status: connectionStatus });

      if (shouldReconnect) {
        setTimeout(startWhatsApp, 3000);
      } else {
        console.log('[WhatsBB] Logged out. Resetting auth...');
        try {
          fs.rmSync(AUTH_DIR, { recursive: true, force: true });
        } catch (e) {}
        setTimeout(startWhatsApp, 2000);
      }
    }
  });

  // Incoming or sent messages
  sock.ev.on('messages.upsert', async ({ messages, type }) => {
    for (const msg of messages) {
      if (!msg.message) continue;

      const jid = msg.key.remoteJid;
      if (!jid || jid === 'status@broadcast') continue;

      const text =
        msg.message.conversation ||
        msg.message.extendedTextMessage?.text ||
        msg.message.imageMessage?.caption ||
        (msg.message.imageMessage ? '[Image]' : '') ||
        (msg.message.audioMessage ? '[Voice Note]' : '') ||
        (msg.message.videoMessage ? '[Video]' : '') ||
        (msg.message.documentMessage ? '[Document]' : '') ||
        '[Message]';

      const fromMe = Boolean(msg.key.fromMe);
      const timestamp = (msg.messageTimestamp ? Number(msg.messageTimestamp) : Math.floor(Date.now() / 1000)) * 1000;
      const sender = msg.pushName || (fromMe ? 'You' : jid.split('@')[0]);

      // Cache message in thread
      if (!messagesMap.has(jid)) {
        messagesMap.set(jid, []);
      }
      const list = messagesMap.get(jid);
      const msgObj = {
        id: msg.key.id,
        fromMe,
        text,
        timestamp,
        sender
      };

      // Avoid duplicates
      if (!list.some(m => m.id === msgObj.id)) {
        list.push(msgObj);
        if (list.length > 100) list.shift();
      }

      // Update chat preview
      const isGroup = jid.endsWith('@g.us');
      const existingChat = chatsMap.get(jid) || {};
      const chatName = msg.pushName || existingChat.name || (isGroup ? 'Group Chat' : '+' + jid.split('@')[0]);

      chatsMap.set(jid, {
        id: jid,
        name: chatName,
        lastMessage: text,
        timestamp,
        unread: fromMe ? 0 : (existingChat.unread || 0) + 1,
        isGroup
      });

      broadcastSSE('message', { jid, message: msgObj });
      broadcastSSE('chat_update', chatsMap.get(jid));
    }
  });
}

// REST Endpoints
app.get('/api/status', (req, res) => {
  res.json({
    status: connectionStatus,
    user: sock?.user || null
  });
});

app.get('/api/qr', (req, res) => {
  res.json({
    status: connectionStatus,
    qr: currentQRDataUrl,
    raw: currentQR
  });
});

app.get('/api/chats', (req, res) => {
  const chats = Array.from(chatsMap.values()).sort((a, b) => b.timestamp - a.timestamp);
  res.json({ chats });
});

app.get('/api/messages', (req, res) => {
  const { jid } = req.query;
  if (!jid) {
    return res.status(400).json({ error: 'Missing jid parameter' });
  }
  const messages = messagesMap.get(jid) || [];
  // Reset unread count
  if (chatsMap.has(jid)) {
    const c = chatsMap.get(jid);
    c.unread = 0;
  }
  res.json({ jid, messages });
});

app.post('/api/send', async (req, res) => {
  const { jid, text } = req.body;
  if (!jid || !text) {
    return res.status(400).json({ error: 'Missing jid or text' });
  }

  if (connectionStatus !== 'CONNECTED' || !sock) {
    return res.status(503).json({ error: 'WhatsApp not connected' });
  }

  try {
    const sent = await sock.sendMessage(jid, { text: text.trim() });
    const timestamp = Date.now();
    const msgObj = {
      id: sent.key.id,
      fromMe: true,
      text: text.trim(),
      timestamp,
      sender: 'You'
    };

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

// SSE endpoint for live events
app.get('/api/events', (req, res) => {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive'
  });
  res.write('\n');
  sseClients.push(res);

  req.on('close', () => {
    sseClients = sseClients.filter(c => c !== res);
  });
});

// Web UI fallback for testing or direct browser use
app.get('/', (req, res) => {
  res.send(`<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
  <title>WhatsBB Bridge</title>
  <style>
    body { font-family: -apple-system, sans-serif; background: #121b22; color: #e9edef; padding: 20px; text-align: center; }
    .card { background: #1f2c34; padding: 24px; border-radius: 12px; max-width: 400px; margin: 40px auto; }
    h2 { margin-top: 0; color: #00a884; }
    #qr-img { border-radius: 8px; margin: 16px 0; background: white; padding: 12px; display: inline-block; }
    .status { padding: 6px 12px; border-radius: 16px; display: inline-block; font-weight: bold; font-size: 14px; }
    .CONNECTED { background: #00a884; color: white; }
    .SCAN_QR { background: #ffaa00; color: black; }
    .CONNECTING { background: #3b82f6; color: white; }
    .DISCONNECTED { background: #ef4444; color: white; }
    p { color: #8696a0; font-size: 14px; line-height: 1.5; }
  </style>
</head>
<body>
  <div class="card">
    <h2>WhatsBB Bridge</h2>
    <div id="status" class="status CONNECTING">Connecting...</div>
    <div id="qr-container"></div>
    <p id="hint">Open WhatsApp on your phone &rarr; Settings &rarr; Linked Devices &rarr; Link a Device, and scan.</p>
  </div>
  <script>
    async function check() {
      try {
        const res = await fetch('/api/qr');
        const data = await res.json();
        const st = document.getElementById('status');
        st.className = 'status ' + data.status;
        st.textContent = data.status === 'CONNECTED' ? 'Connected' : (data.status === 'SCAN_QR' ? 'Scan QR Code' : data.status);
        
        const qrc = document.getElementById('qr-container');
        if (data.qr && data.status === 'SCAN_QR') {
          qrc.innerHTML = '<img id="qr-img" src="' + data.qr + '" width="250" height="250"/>';
        } else if (data.status === 'CONNECTED') {
          qrc.innerHTML = '<p style="color:#00a884; font-size:18px; margin: 30px 0;">&#10003; WhatsApp Linked!</p>';
        }
      } catch (e) {}
    }
    setInterval(check, 2500);
    check();
  </script>
</body>
</html>`);
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`[WhatsBB] Bridge Server running at http://0.0.0.0:${PORT}`);
  startWhatsApp();
});
