/* Маячок — мапа групи. Код групи живе у location.hash і не йде на сервер. */

const API = 'https://mayachok.kikikiska.workers.dev';
const APK_URL = 'https://github.com/dneese/mayachok/releases/latest/download/mayachok-1.0.apk';

const POLL_MS = 10000;
const CHAT_POLL_MS = 3000;
const FRESH_MS = 2 * 60 * 1000;
const HOURS_OPTIONS = [1, 3, 12];

const state = {
  code: null,
  users: new Map(),
  markers: new Map(),
  trails: new Map(),
  selected: null,
  hours: 12,
  // чат
  lastMessageId: 0,
  chatName: '',
  tab: 'people',
  unread: 0,
};

let map = null;
let started = false;
let editing = null; // uid, чию саме ім'я редагують

const $ = (id) => document.getElementById(id);

// --- код групи ---

/** Чистить код: без дефісів, у нижньому регістрі — так сервер і приймає. */
function cleanCode(raw) {
  return String(raw || '').trim().toLowerCase().replace(/[^a-z0-9]/g, '');
}

/**
 * Дістає код із того, що вставив користувач: посилання, deep link або сам код.
 * Так само, як Prefs.extractCode() у застосунку.
 */
function codeFromInput(raw) {
  let text = String(raw || '').trim();
  if (!text) return null;
  // mayachok://join/КОД — беремо останній сегмент шляху, щоб у код
  // не просочилися слова «mayachok» та «join»
  const slash = text.lastIndexOf('/');
  if (slash >= 0 && slash < text.length - 1) text = text.slice(slash + 1);
  // код живе у фрагменті після '#'
  const hash = text.lastIndexOf('#');
  if (hash >= 0 && hash < text.length - 1) text = text.slice(hash + 1);
  const clean = cleanCode(text);
  return clean.length >= 8 && clean.length <= 64 ? clean : null;
}

function codeFromHash() {
  return codeFromInput(decodeURIComponent(location.hash.replace(/^#/, '')));
}

/** xxxx-xxxx-xxxx — щоб код можна було прочитати вголос. */
function prettyCode(raw) {
  const clean = cleanCode(raw);
  return clean.match(/.{1,4}/g)?.join('-') || clean;
}

function groupUrl() {
  return location.origin + location.pathname + '#' + state.code;
}

function deepLink() {
  return 'mayachok://join/' + prettyCode(state.code);
}

function inviteText() {
  return (
    'Маячок — сімейний GPS-трекер.\n\n' +
    'Відкрий ось це посилання — і ти в групі:\n' +
    groupUrl() +
    '\n\nКод групи (якщо треба ввести вручну): ' +
    prettyCode(state.code) +
    '\nЗастосунок для Android: ' +
    APK_URL
  );
}

// Зміна лише хеша не перезавантажує документ, тому start() треба
// викликати вручну — інакше ворота лишаються на екрані.
function applyCode(code) {
  state.code = cleanCode(code);
  $('code-input').value = state.code;
  $('code-value').textContent = groupUrl();
  $('share-code').textContent = prettyCode(state.code);
  $('share-link').textContent = groupUrl();
  start();
}

function setCode(code) {
  const clean = cleanCode(code);
  const encoded = '#' + encodeURIComponent(clean);
  if (location.hash !== encoded) {
    history.replaceState(null, '', location.pathname + location.search + encoded);
  }
  applyCode(clean);
}

window.addEventListener('hashchange', () => {
  const code = codeFromHash();
  if (code) applyCode(code);
});

// --- форматування ---

function timeAgo(ts, now) {
  if (!ts) return 'невідомо';
  const s = Math.max(0, Math.round((now - ts) / 1000));
  if (s < 10) return 'щойно';
  if (s < 60) return `${s} с тому`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} хв тому`;
  return `${Math.floor(m / 60)} год тому`;
}

function initial(name, uid) {
  const base = (name || '').trim();
  if (base) return [...base][0].toUpperCase();
  return (uid || '?').slice(0, 2).toUpperCase();
}

function displayName(user) {
  return (user.name && user.name.trim()) || `Пристрій ${user.uid.slice(0, 4)}`;
}

function isFresh(ts, now) {
  return now - ts < FRESH_MS;
}

// Стійкий колір на основі uid: різна людина — різний колір.
function userColor(user) {
  let h = 0;
  const s = (user && user.uid) || '';
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360;
  return `hsl(${h}, 62%, 48%)`;
}

function escapeHtml(value) {
  return String(value == null ? '' : value).replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c],
  );
}

// --- карта ---

function initMap() {
  map = L.map('map', { zoomControl: false, attributionControl: true }).setView(
    [49.84, 24.03],
    12,
  );

  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
  }).addTo(map);

  L.control.zoom({ position: 'bottomright' }).addTo(map);
  map.on('click', () => selectUser(null));
}

function pinIcon(user, now) {
  return L.divIcon({
    className: '',
    html: `<div class="pin ${isFresh(user.ts, now) ? 'fresh' : 'stale'}" style="--c:${userColor(user)}"></div>`,
    iconSize: [18, 18],
    iconAnchor: [9, 9],
    popupAnchor: [0, -10],
  });
}

function popupHtml(user, now) {
  const rows = [`<b>${escapeHtml(displayName(user))}</b>`, timeAgo(user.ts, now)];
  if (user.acc != null && user.acc > 0) rows.push(`точність ±${Math.round(user.acc)} м`);
  if (user.bat != null) rows.push(`батарея ${user.bat}%`);
  return `${rows.join('<br>')}<div class="meta">Колір закріплений за цим пристроєм</div>`;
}

// --- оновлення ---

async function poll() {
  if (!state.code) return;
  try {
    const res = await fetch(`${API}/api/group?code=${encodeURIComponent(state.code)}`, {
      cache: 'no-store',
    });
    if (!res.ok) throw new Error('http ' + res.status);
    const data = await res.json();

    state.users.clear();
    for (const u of data.users) state.users.set(u.uid, u);

    render();
    if (state.selected && !state.users.has(state.selected)) selectUser(null);

    // Порожня група — найчастіше це помилка в коді, а не відсутність людей.
    if (data.users.length === 0) setStatus('ok', 'у групі поки нікого немає');
    else setStatus('ok', `${data.users.length} у групі`);
  } catch {
    setStatus('err', 'немає зв’язку з сервером');
  }
}

function render() {
  if (!map) return;
  const now = Date.now();
  const seen = new Set();

  for (const user of state.users.values()) {
    const hasFix = user.lat != null && user.lon != null;
    if (!hasFix) continue;
    seen.add(user.uid);

    const marker = state.markers.get(user.uid);
    if (!marker) {
      const m = L.marker([user.lat, user.lon], {
        icon: pinIcon(user, now),
        title: displayName(user),
      });
      m.on('click', () => selectUser(user.uid));
      m.bindPopup(popupHtml(user, now));
      m.addTo(map);
      state.markers.set(user.uid, m);
    } else {
      marker.setLatLng([user.lat, user.lon]);
      marker.setIcon(pinIcon(user, now));
      marker.setPopupContent(popupHtml(user, now));
    }
  }

  for (const [uid, marker] of state.markers) {
    if (!seen.has(uid)) {
      map.removeLayer(marker);
      state.markers.delete(uid);
      state.trails.delete(uid);
    }
  }

  renderPeople(now);
  $('people-count').textContent = `${state.users.size} у групі`;
}

function renderPeople(now) {
  const list = $('people');
  list.textContent = '';

  if (state.users.size === 0) {
    const empty = document.createElement('div');
    empty.className = 'people-empty';
    empty.textContent =
      'Поки нікого не видно. Переконайтеся, що інші натиснули «Почати» і мають ту саму групу.';
    list.appendChild(empty);
    return;
  }

  for (const user of state.users.values()) {
    const hasFix = user.lat != null && user.lon != null;
    const fresh = isFresh(user.ts, now);

    const row = document.createElement('div');
    row.className = 'person' + (state.selected === user.uid ? ' selected' : '');

    if (editing === user.uid) {
      row.innerHTML = `
        <div class="avatar" style="background:${userColor(user)}">${escapeHtml(initial(user.name, user.uid))}</div>
        <div class="person-meta">
          <input class="rename-input" maxlength="24" value="${escapeHtml(user.name || '')}" placeholder="ім’я">
        </div>
        <div class="person-actions">
          <button class="ok" data-uid="${escapeHtml(user.uid)}">OK</button>
          <button class="cancel">✕</button>
        </div>`;
      const input = row.querySelector('.rename-input');
      input.focus();
      input.select();
      input.onkeydown = (e) => {
        if (e.key === 'Enter') saveRename(user.uid, input.value);
        if (e.key === 'Escape') cancelRename();
      };
    } else {
      // Людина, що щойно приєдналася, ще не має точки — показуємо це прямо,
      // щоб не здавалося, що вона зникла або не прийшла.
      const sub = hasFix ? timeAgo(user.ts, now) : 'чекаємо сигнал GPS';
      row.innerHTML = `
        <div class="avatar ${hasFix && fresh ? 'fresh' : 'stale'}${hasFix ? '' : ' waiting'}" style="background:${userColor(user)}">${escapeHtml(initial(user.name, user.uid))}</div>
        <div class="person-meta">
          <div class="person-name">${escapeHtml(displayName(user))}</div>
          <div class="person-sub">${sub}${user.bat != null && hasFix ? ' · ' + user.bat + '%' : ''}</div>
        </div>
        <div class="person-actions">
          <button class="edit" data-uid="${escapeHtml(user.uid)}" title="Перейменувати">✎</button>
        </div>`;
    }

    row.onclick = (event) => {
      if (event.target.closest('button') || editing) return;
      // без точки слід і маркер показати нема чого
      if (!hasFix) return;
      selectUser(user.uid);
    };
    list.appendChild(row);
  }
}

// --- чат ---

const chatNameKey = 'mayachok-name';

function localUid() {
  let uid = localStorage.getItem('mayachok-uid');
  if (!uid) {
    uid = 'web-' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
    localStorage.setItem('mayachok-uid', uid);
  }
  return uid;
}

function clockTime(ts) {
  const d = new Date(ts);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

/** Реєструє браузер як учасника групи, щоб сервер дозволив писати в чат. */
async function ensureChatMember() {
  try {
    await fetch(`${API}/api/join`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: state.code, uid: localUid(), name: state.chatName, role: 'chat' }),
    });
  } catch {
    // не критично: напишемо й так, сервер перевірить і скаже
  }
}

async function pollChat() {
  if (!state.code) return;
  try {
    const res = await fetch(
      `${API}/api/chat?code=${encodeURIComponent(state.code)}&after=${state.lastMessageId}`,
      { cache: 'no-store' },
    );
    if (!res.ok) return;
    const data = await res.json();
    if (!data.messages || data.messages.length === 0) return;

    for (const m of data.messages) {
      addMessage(m);
      state.lastMessageId = Math.max(state.lastMessageId, m.id);
    }
    if (state.tab !== 'chat') state.unread += data.messages.length;
    updateChatBadge();
    scrollChatToEnd();
  } catch {
    // чат не критичний — тихо пропускаємо опитування
  }
}

function addMessage(m) {
  const log = $('chat-log');
  const mine = m.uid === localUid();
  const row = document.createElement('div');
  row.className = 'msg' + (mine ? ' mine' : '');
  row.innerHTML = `
    <div class="msg-head">
      <span class="msg-name">${escapeHtml(m.name || 'Хтось')}</span>
      <span class="msg-time">${clockTime(m.ts)}</span>
    </div>
    <div class="msg-body">${escapeHtml(m.body).replace(/\n/g, '<br>')}</div>`;
  log.appendChild(row);
}

function scrollChatToEnd() {
  const log = $('chat-log');
  log.scrollTop = log.scrollHeight;
}

function updateChatBadge() {
  const badge = $('chat-badge');
  if (state.unread > 0 && state.tab !== 'chat') {
    badge.textContent = state.unread > 99 ? '99+' : String(state.unread);
    badge.classList.remove('hidden');
  } else {
    badge.classList.add('hidden');
    state.unread = 0;
  }
}

function switchTab(name) {
  state.tab = name;
  $('tab-people').classList.toggle('hidden', name !== 'people');
  $('tab-chat').classList.toggle('hidden', name !== 'chat');
  document.querySelectorAll('.tab').forEach((t) => {
    t.classList.toggle('active', t.dataset.tab === name);
  });
  if (name === 'chat') {
    updateChatBadge();
    scrollChatToEnd();
  } else {
    render();
  }
  if (map) setTimeout(() => map.invalidateSize(), 50);
}

async function sendChat(text) {
  const body = text.trim();
  if (!body) return;
  $('chat-input').value = '';
  await ensureChatMember();
  try {
    await fetch(`${API}/api/say`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        code: state.code,
        uid: localUid(),
        name: state.chatName,
        body,
      }),
    });
  } catch {
    $('chat-input').value = body; // повертаємо текст, щоб не загубити
    flash('не вдалося надіслати');
  }
  pollChat();
}

function openChat() {
  switchTab('chat');
  if (state.chatName) {
    $('chat-input').focus();
  } else {
    $('chat-name').value = localStorage.getItem(chatNameKey) || '';
    openModal('modal-name');
  }
}

// --- слід ---

async function selectUser(uid) {
  state.selected = uid;
  render();

  for (const [id, layer] of state.trails) {
    if (id !== uid && layer) map.removeLayer(layer);
  }
  if (!uid) return;
  if (state.trails.has(uid)) return;

  try {
    const res = await fetch(
      `${API}/api/track?code=${encodeURIComponent(state.code)}&uid=${encodeURIComponent(uid)}&hours=${state.hours}`,
      { cache: 'no-store' },
    );
    if (!res.ok) throw new Error('http ' + res.status);
    const geo = await res.json();
    const coords = geo.geometry.coordinates || [];
    if (coords.length < 2) return;

    const layer = L.polyline(
      coords.map(([lon, lat]) => [lat, lon]),
      {
        color: userColor(state.users.get(uid)),
        weight: 4,
        opacity: 0.7,
        lineJoin: 'round',
      },
    ).addTo(map);
    layer.bindPopup(`${geo.properties.count ?? coords.length} точок за ${state.hours} год`);
    state.trails.set(uid, layer);
  } catch {
    // слід не критичний — просто не малюємо
  }
}

async function rename(uid, name) {
  try {
    const res = await fetch(`${API}/api/rename`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: state.code, uid, name }),
    });
    if (!res.ok) throw new Error('http ' + res.status);
    cancelRename();
    await poll();
  } catch {
    setStatus('err', 'не вдалося перейменувати');
  }
}

function saveRename(uid, value) {
  const name = value.trim();
  if (!name) return cancelRename();
  rename(uid, name);
}

function cancelRename() {
  editing = null;
  render();
}

function fitAll() {
  if (!map) return;
  const pts = [];
  for (const u of state.users.values()) if (u.lat != null) pts.push([u.lat, u.lon]);
  if (pts.length) map.fitBounds(L.latLngBounds(pts).pad(0.2));
  else map.setView([49.84, 24.03], 12);
}

$('btn-chat').onclick = openChat;

$('chat-name-ok').onclick = () => {
  const name = $('chat-name').value.trim();
  if (!name) return;
  state.chatName = name;
  localStorage.setItem(chatNameKey, name);
  closeModal('modal-name');
  ensureChatMember();
  $('chat-input').focus();
};

$('chat-form').addEventListener('submit', (event) => {
  event.preventDefault();
  if (!state.chatName) {
    openChat();
    return;
  }
  sendChat($('chat-input').value);
});

document.querySelectorAll('.tab').forEach((tab) => {
  tab.addEventListener('click', () => switchTab(tab.dataset.tab));
});

// --- інтерфейс ---

function setStatus(kind, text) {
  $('status-dot').className = kind;
  $('status-text').textContent = text;
}

function flash(text) {
  const old = $('status-text').textContent;
  setStatus('ok', text);
  setTimeout(() => {
    if ($('status-text').textContent === text) setStatus('ok', old);
  }, 2000);
}

async function copy(text, what) {
  try {
    if (navigator.clipboard) {
      await navigator.clipboard.writeText(text);
    } else {
      // старі браузери: тимчасове textarea
      const area = document.createElement('textarea');
      area.value = text;
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      document.execCommand('copy');
      area.remove();
    }
    flash(`${what} скопійовано`);
  } catch {
    flash('не вдалося скопіювати');
  }
}

function openModal(id) {
  $(id).classList.add('open');
}
function closeModal(id) {
  $(id).classList.remove('open');
}

function start() {
  if (started) return;
  started = true;
  $('gate').classList.add('hidden');
  document.body.classList.add('live');
  initMap();
  poll();
  setInterval(poll, POLL_MS);

  // чат питаємо частіше — повідомлення мають з'являтися одразу
  pollChat();
  setInterval(pollChat, CHAT_POLL_MS);
}

// --- події ---

$('btn-fit').onclick = fitAll;

$('btn-hours').onclick = () => {
  const i = HOURS_OPTIONS.indexOf(state.hours);
  state.hours = HOURS_OPTIONS[(i + 1) % HOURS_OPTIONS.length];
  $('btn-hours').textContent = `${state.hours} год`;
  for (const layer of state.trails.values()) map.removeLayer(layer);
  state.trails.clear();
  if (state.selected) selectUser(state.selected);
};

$('btn-info').onclick = () => openModal('modal-info');

$('sheet-toggle').onclick = () => {
  const collapsed = $('sheet').classList.toggle('collapsed');
  // карпа має перерахувати розмір, бо панель змінює вільну площу
  document.body.classList.toggle('sheet-collapsed', collapsed);
  $('sheet-chevron').textContent = collapsed ? '▴' : '▾';
  setTimeout(() => map && map.invalidateSize(), 60);
};

$('btn-copy-link').onclick = () => copy(groupUrl(), 'посилання');

$('btn-app').onclick = () => {
  // на телефоні без застосунку браузер просто не знає схему — тоді копіюємо посилання
  location.href = deepLink();
  setTimeout(() => copy(groupUrl(), 'посилання'), 1200);
};

$('btn-share').onclick = () => openModal('modal-share');

$('btn-share-native').onclick = async () => {
  try {
    if (navigator.share) {
      await navigator.share({
        title: 'Маячок — код групи ' + prettyCode(state.code),
        text: inviteText(),
      });
    } else {
      await copy(inviteText(), 'запрошення');
    }
  } catch {
    // користувач скасував поділення
  }
};

$('people').addEventListener('click', (event) => {
  const ok = event.target.closest('.ok');
  if (ok) {
    const row = ok.closest('.person');
    saveRename(ok.dataset.uid, row.querySelector('.rename-input').value);
    return;
  }
  if (event.target.closest('.cancel')) return cancelRename();
  const edit = event.target.closest('.edit');
  if (edit) {
    editing = edit.dataset.uid;
    render();
  }
});

document.addEventListener('click', (event) => {
  const closer = event.target.closest('[data-close]');
  if (closer) return closeModal(closer.dataset.close);
  // клік по затемненню закриває модалку
  if (event.target.classList.contains('modal')) closeModal(event.target.id);
});

document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') {
    closeModal('modal-share');
    closeModal('modal-info');
  }
});

$('btn-join').onclick = () => {
  const code = codeFromInput($('code-input').value);
  $('gate-error').textContent = '';
  if (!code) {
    $('gate-error').textContent = 'Вставте посилання групи або код.';
    return;
  }
  setCode(code);
};

$('btn-create').onclick = async () => {
  $('gate-error').textContent = '';
  const buttons = document.querySelectorAll('.gate-actions button');
  buttons.forEach((b) => (b.disabled = true));

  try {
    const res = await fetch(`${API}/api/create`, { method: 'POST' });
    if (!res.ok) throw new Error('http ' + res.status);
    const data = await res.json();
    setCode(data.code);
  } catch {
    $('gate-error').textContent = 'Не вдалося створити групу. Спробуйте ще раз.';
    buttons.forEach((b) => (b.disabled = false));
  }
};

$('code-input').addEventListener('keydown', (event) => {
  if (event.key === 'Enter') $('btn-join').click();
});

// --- старт ---

$('btn-hours').textContent = `${state.hours} год`;

const initialCode = codeFromHash();
if (initialCode) applyCode(initialCode);
else $('gate').classList.remove('hidden');
