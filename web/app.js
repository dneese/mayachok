/* gps-tracker — мапа групи. Код групи живе у location.hash і не йде на сервер. */

const API = window.GPS_API || 'https://mayachok.kikikiska.workers.dev';
const POLL_MS = 10000;
const FRESH_MS = 2 * 60 * 1000; // до 2 хв — «щойно онлайн»
const HOURS_OPTIONS = [1, 3, 12];

const state = {
  code: null,
  users: new Map(),
  markers: new Map(),
  trails: new Map(),
  selected: null,
  hours: 12,
  staleAfterMs: 30 * 60 * 1000,
};

let map = null;
let started = false;

const $ = (id) => document.getElementById(id);

// --- код групи з hash ---
function codeFromHash() {
  const raw = decodeURIComponent(location.hash.replace(/^#/, '')).trim();
  const clean = raw.replace(/[^a-z0-9]/gi, '').toLowerCase();
  return clean.length >= 8 && clean.length <= 64 ? clean : null;
}

// Зміна лише хеша не перезавантажує документ, тому start() треба
// викликати вручну — інакше ворота лишаються на екрані.
function applyCode(code) {
  state.code = code;
  $('code-input').value = code;
  start();
}

function setCode(code) {
  const encoded = '#' + encodeURIComponent(code);
  if (location.hash === encoded) {
    applyCode(code);
    return;
  }
  history.replaceState(null, '', location.pathname + location.search + encoded);
  applyCode(code);
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
  const h = Math.floor(m / 60);
  return `${h} год тому`;
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

// --- карта ---
function initMap() {
  map = L.map('map', { zoomControl: true, attributionControl: true }).setView([49.84, 24.03], 12);

  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
  }).addTo(map);

  map.on('click', () => selectUser(null));
}

function pinIcon(user, now) {
  const fresh = isFresh(user.ts, now);
  return L.divIcon({
    className: '',
    html: `<div class="pin ${fresh ? '' : 'stale'}" style="background:${userColor(user)}"></div>`,
    iconSize: [18, 18],
    iconAnchor: [9, 9],
    popupAnchor: [0, -10],
  });
}

// Стійкий колір на основі uid: різна людина — різний колір.
function userColor(user) {
  let h = 0;
  const s = user.uid || '';
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360;
  return `hsl(${h}, 62%, 48%)`;
}

function popupHtml(user, now) {
  const rows = [
    `<b>${escapeHtml(displayName(user))}</b>`,
    timeAgo(user.ts, now),
  ];
  if (user.acc != null && user.acc > 0) rows.push(`точність ±${Math.round(user.acc)} м`);
  if (user.bat != null) rows.push(`батарея ${user.bat}%`);
  return `
    ${rows.join('<br>')}
    <div class="rename-row">
      <input type="text" maxlength="24" placeholder="ім'я" value="${escapeHtml(user.name || '')}">
      <button data-uid="${escapeHtml(user.uid)}">OK</button>
    </div>`;
}

function escapeHtml(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

// --- оновлення ---
async function poll() {
  if (!state.code) return;
  try {
    const res = await fetch(`${API}/api/group?code=${encodeURIComponent(state.code)}`, { cache: 'no-store' });
    if (!res.ok) throw new Error('http ' + res.status);
    const data = await res.json();

    state.staleAfterMs = data.staleAfterMs || state.staleAfterMs;
    state.users.clear();
    for (const u of data.users) state.users.set(u.uid, u);

    setStatus('ok', `${data.users.length} у групі`);
    render();
    if (state.selected && !state.users.has(state.selected)) selectUser(null);

    // Порожня група: найчастіше це помилка в коді, а не відсутність людей.
    if (data.users.length === 0) {
      setStatus('ok', 'у групі поки нікого немає — перевірте код');
    }
  } catch (error) {
    setStatus('err', 'немає зв’язку з сервером');
  }
}

function render() {
  const now = Date.now();
  const seen = new Set();

  for (const user of state.users.values()) {
    if (user.lat == null || user.lon == null) continue;
    seen.add(user.uid);

    const marker = state.markers.get(user.uid);
    if (!marker) {
      const m = L.marker([user.lat, user.lon], { icon: pinIcon(user, now), title: displayName(user) });
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

  renderList(now);
}

function renderList(now) {
  const list = $('list');
  list.textContent = '';

  for (const user of state.users.values()) {
    if (user.lat == null) continue;
    const fresh = isFresh(user.ts, now);
    const el = document.createElement('div');
    el.className = 'person' + (state.selected === user.uid ? ' selected' : '');
    el.innerHTML = `
      <div class="avatar ${fresh ? 'fresh' : 'stale'}" style="background:${userColor(user)}">${escapeHtml(initial(user.name, user.uid))}</div>
      <div class="person-meta">
        <div class="person-name">${escapeHtml(displayName(user))}</div>
        <div class="person-sub">${timeAgo(user.ts, now)}</div>
      </div>`;
    el.onclick = () => selectUser(user.uid);
    list.appendChild(el);
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

    const user = state.users.get(uid);
    const layer = L.polyline(
      coords.map(([lon, lat]) => [lat, lon]),
      { color: userColor(user || { uid }), weight: 4, opacity: 0.65, lineJoin: 'round' },
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
    await poll();
  } catch {
    setStatus('err', 'не вдалося перейменувати');
  }
}

function fitAll() {
  const pts = [];
  for (const u of state.users.values()) if (u.lat != null) pts.push([u.lat, u.lon]);
  if (pts.length) map.fitBounds(L.latLngBounds(pts).pad(0.18));
  else map.setView([49.84, 24.03], 12);
}

// --- інтерфейс ---
function setStatus(kind, text) {
  $('dot').className = kind;
  $('status-text').textContent = text;
}

function showGate() {
  $('gate').classList.remove('hidden');
}

function start() {
  if (started) return;
  started = true;
  $('gate').classList.add('hidden');
  initMap();
  poll();
  setInterval(poll, POLL_MS);
}

document.addEventListener('click', (event) => {
  const button = event.target.closest('.rename-row button');
  if (!button) return;
  const input = button.parentElement.querySelector('input');
  const value = input.value.trim();
  if (value) rename(button.dataset.uid, value);
  else input.value = '';
});

$('btn-fit').onclick = fitAll;

$('btn-hours').onclick = () => {
  const i = HOURS_OPTIONS.indexOf(state.hours);
  state.hours = HOURS_OPTIONS[(i + 1) % HOURS_OPTIONS.length];
  $('btn-hours').textContent = `${state.hours} год`;

  // сліди будуємо наново, бо інтервал змінився
  for (const layer of state.trails.values()) map.removeLayer(layer);
  state.trails.clear();
  if (state.selected) selectUser(state.selected);
};

$('btn-link').onclick = async () => {
  const url = location.origin + location.pathname + '#' + encodeURIComponent(state.code);
  try {
    if (navigator.share) await navigator.share({ title: 'Сімейний трекер', url });
    else {
      await navigator.clipboard.writeText(url);
      setStatus('ok', 'посилання скопійовано');
    }
  } catch {
    // користувач скасував поділення
  }
};

$('btn-join').onclick = () => {
  const value = $('code-input').value.trim();
  $('gate-error').textContent = '';
  if (value.length < 8) {
    $('gate-error').textContent = 'Код закороткий.';
    return;
  }
  setCode(value);
};

$('btn-create').onclick = async () => {
  $('gate-error').textContent = '';
  const buttons = document.querySelectorAll('.gate-actions button');
  buttons.forEach((b) => (b.disabled = true));

  try {
    const res = await fetch(`${API}/api/create`, { method: 'POST' });
    if (!res.ok) throw new Error('http ' + res.status);
    const data = await res.json();
    $('code-input').value = data.code;
    setCode(data.code);
  } catch {
    $('gate-error').textContent = 'Не вдалося створити групу. Спробуйте ще раз.';
    buttons.forEach((b) => (b.disabled = false));
  }
};

$('code-input').addEventListener('keydown', (event) => {
  if (event.key === 'Enter') $('btn-join').onclick();
});

// --- старт ---
const initialCode = codeFromHash();
if (initialCode) {
  state.code = initialCode;
  start();
} else {
  showGate();
}
