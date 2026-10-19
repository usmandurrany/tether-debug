'use strict';

// Everything shown here comes from devices and is untrusted: it is only ever
// inserted with textContent, never as HTML.

const $ = (id) => document.getElementById(id);
const MAX_ROWS = 3000;
const ONLINE_MS = 60_000;

const state = {
  devices: new Map(),
  installId: null,
  session: null,
  label: '',
  entries: [], // newest first
  live: true,
  source: null,
  loading: false,
};

// ---- API ----

async function api(path) {
  const res = await fetch(path, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  return res.json();
}

function filterParams() {
  const p = new URLSearchParams();
  const set = (k, v) => { if (v) p.set(k, v); };
  set('installId', state.installId);
  set('session', state.session);
  set('label', state.label);
  set('q', $('f-q').value.trim());
  set('account', $('f-account').value.trim());
  set('dir', $('f-dir').value);
  set('kind', $('f-kind').value);
  set('level', $('f-level').value);
  return p;
}

// ---- Devices and sessions ----

async function loadDevices() {
  try {
    const devices = await api('/api/v1/devices');
    state.devices = new Map(devices.map((d) => [d.installId, d]));
    renderDevices();
  } catch (e) {
    setStatus(`Could not load devices: ${e.message}`);
  }
}

async function loadLabels() {
  const labels = await api('/api/v1/labels').catch(() => []);
  const select = $('label');
  const current = select.value;
  select.replaceChildren(option('', 'All rounds'), ...labels.map((l) => option(l, l)));
  select.value = labels.includes(current) ? current : '';
}

function option(value, text) {
  const o = document.createElement('option');
  o.value = value;
  o.textContent = text;
  return o;
}

function deviceName(d) {
  return d ? (d.model || `${d.platform} device`) : 'unknown device';
}

function renderDevices() {
  const list = $('devices');
  const now = Date.now();
  const items = [...state.devices.values()].map((d) => {
    const li = document.createElement('li');
    if (d.installId === state.installId) li.classList.add('selected');
    const name = document.createElement('div');
    name.className = 'name';
    if (now - d.lastSeen < ONLINE_MS) {
      const dot = document.createElement('span');
      dot.className = 'online';
      dot.title = 'Sent logs in the last minute';
      name.append(dot);
    }
    name.append(deviceName(d));
    li.append(
      name,
      small([d.platform, d.osVersion].filter(Boolean).join(' ')),
      small([d.appId, d.appVersion && `v${d.appVersion}`].filter(Boolean).join(' ')),
      small(d.lastAccount || 'no account yet'),
      small(`last seen ${ago(d.lastSeen)} · ${d.sessions} sessions · ${d.entries} entries`),
    );
    li.addEventListener('click', () => selectDevice(d.installId));
    return li;
  });
  if (items.length === 0) items.push(emptyItem('No device has sent logs yet.'));
  list.replaceChildren(...items);
}

function small(text) {
  const div = document.createElement('div');
  div.className = 'small';
  div.textContent = text;
  return div;
}

function emptyItem(text) {
  const li = document.createElement('li');
  li.className = 'empty';
  li.textContent = text;
  return li;
}

async function selectDevice(installId) {
  state.installId = installId;
  state.session = null;
  renderDevices();
  await loadSessions();
  reload();
}

async function loadSessions() {
  const box = $('sessions-box');
  if (!state.installId) {
    box.hidden = true;
    return;
  }
  const p = new URLSearchParams({ installId: state.installId });
  if (state.label) p.set('label', state.label);
  const sessions = await api(`/api/v1/sessions?${p}`).catch(() => []);
  box.hidden = false;
  const items = sessions.map((s) => {
    const li = document.createElement('li');
    if (s.id === state.session) li.classList.add('selected');
    const exp = document.createElement('a');
    exp.className = 'export';
    exp.href = `/api/v1/sessions/${encodeURIComponent(s.id)}/export`;
    exp.textContent = 'Export';
    exp.addEventListener('click', (e) => e.stopPropagation());
    const title = document.createElement('div');
    title.textContent = `Started ${new Date(s.startedAt).toLocaleString()}`;
    li.append(exp, title, small([s.label, s.lastAccount].filter(Boolean).join(' · ') || 'no label'));
    const counts = small(`${s.entries} entries · last ${ago(s.lastSeen)}`);
    if (s.dropped > 0) {
      const drop = document.createElement('span');
      drop.className = 'badge-drop';
      drop.textContent = ` · ${s.dropped} dropped on device`;
      drop.title = 'The device queue overflowed while the portal was unreachable';
      counts.append(drop);
    }
    li.append(counts);
    li.addEventListener('click', () => {
      state.session = state.session === s.id ? null : s.id;
      loadSessions();
      reload();
    });
    return li;
  });
  if (items.length === 0) items.push(emptyItem('No sessions.'));
  $('sessions').replaceChildren(...items);
}

// ---- Entries ----

async function reload() {
  state.entries = [];
  $('entries').replaceChildren();
  renderContext();
  await loadOlder();
  connectLive();
}

async function loadOlder() {
  if (state.loading) return;
  state.loading = true;
  try {
    const p = filterParams();
    const oldest = state.entries[state.entries.length - 1];
    if (oldest) p.set('before', oldest.id);
    p.set('limit', '200');
    const page = await api(`/api/v1/entries?${p}`);
    state.entries.push(...page);
    $('entries').append(...page.map((e) => row(e, false)));
    $('older').hidden = page.length < 200;
    if (state.entries.length === 0) $('entries').replaceChildren(emptyItem('Nothing matches yet. New entries appear here as they arrive.'));
    setStatus(`${state.entries.length} entries shown`);
  } catch (e) {
    setStatus(`Could not load entries: ${e.message}`);
  } finally {
    state.loading = false;
  }
}

function prepend(entries) {
  const known = new Set(state.entries.slice(0, 500).map((e) => e.id));
  const fresh = entries.filter((e) => !known.has(e.id)).sort((a, b) => b.id - a.id);
  if (fresh.length === 0) return;
  const list = $('entries');
  if (state.entries.length === 0) list.replaceChildren();
  state.entries.unshift(...fresh);
  list.prepend(...fresh.map((e) => row(e, true)));
  while (state.entries.length > MAX_ROWS) {
    state.entries.pop();
    list.lastElementChild?.remove();
  }
  $('older').hidden = false;
  setStatus(`${state.entries.length} entries shown`);
}

function row(e, fresh) {
  const li = $('entry-tpl').content.firstElementChild.cloneNode(true);
  if (fresh) li.classList.add('fresh');
  li.classList.add(`level-${e.level}`);
  const d = state.devices.get(e.installId);
  const ts = li.querySelector('.ts');
  ts.textContent = clock(e.ts);
  ts.title = `${new Date(e.ts).toLocaleString()} on the device · received ${new Date(e.receivedAt).toLocaleString()}`;
  ts.dateTime = new Date(e.ts).toISOString();
  li.querySelector('.dev').textContent = deviceName(d);
  li.querySelector('.acct').textContent = e.account || '';
  const dir = li.querySelector('.dir');
  dir.textContent = e.dir === 'out' ? '→' : e.dir === 'in' ? '←' : '•';
  dir.title = e.dir === 'out' ? 'sent' : e.dir === 'in' ? 'received' : e.level;
  if (e.dir) dir.classList.add(e.dir);
  li.querySelector('.kind').textContent = e.stanza ? [e.stanza.name, e.stanza.type].filter(Boolean).join(' · ') : `${e.level} ${e.tag}`;
  li.querySelector('.sum').textContent = summary(e);
  const detail = li.querySelector('.detail');
  li.querySelector('.row').addEventListener('click', () => {
    if (detail.hidden && !detail.dataset.ready) {
      detail.dataset.ready = '1';
      detail.querySelector('.meta').textContent = [
        `session ${e.sessionId} #${e.seq}`,
        e.label && `round ${e.label}`,
        d && [deviceName(d), d.platform, d.osVersion, d.appVersion && `app v${d.appVersion}`].filter(Boolean).join(', '),
        e.stanza?.payload && `payload ${e.stanza.payload}`,
      ].filter(Boolean).join(' · ');
      renderXml(detail.querySelector('.xml'), e);
    }
    detail.hidden = !detail.hidden;
  });
  li.querySelector('.copy').addEventListener('click', async (ev) => {
    const btn = ev.currentTarget;
    try {
      await navigator.clipboard.writeText(e.error ? `${e.text}\n${e.error}` : e.text);
      btn.textContent = 'Copied';
    } catch {
      btn.textContent = 'Copy failed';
    }
    setTimeout(() => { btn.textContent = 'Copy'; }, 1500);
  });
  return li;
}

function summary(e) {
  if (!e.stanza) return e.text.split('\n')[0];
  const s = e.stanza;
  const peer = e.dir === 'out' ? s.to && `to ${s.to}` : s.from && `from ${s.from}`;
  const body = /<body>([^<]*)<\/body>/.exec(e.text)?.[1];
  return [peer, s.payload && shortNs(s.payload), s.id && `id ${s.id}`, body && `“${decodeEntities(body)}”`]
    .filter(Boolean).join('  ') || e.text;
}

function shortNs(ns) {
  return ns.replace(/^urn:xmpp:/, '').replace(/^http:\/\/jabber\.org\/protocol\//, '').replace(/^urn:ietf:params:xml:ns:/, '');
}

function decodeEntities(s) {
  return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&apos;/g, "'").replace(/&quot;/g, '"').replace(/&amp;/g, '&');
}

// ---- XML rendering ----

function renderXml(pre, e) {
  pre.replaceChildren();
  const doc = e.stanza
    ? new DOMParser().parseFromString(`<r xmlns="jabber:client">${e.text}</r>`, 'application/xml')
    : null;
  const root = doc && !doc.getElementsByTagName('parsererror').length ? doc.documentElement : null;
  if (!root || root.children.length !== 1) {
    pre.textContent = e.error ? `${e.text}\n\n${e.error}` : e.text;
    return;
  }
  writeElement(pre, root.firstElementChild, 0);
}

function span(cls, text) {
  const s = document.createElement('span');
  s.className = cls;
  s.textContent = text;
  return s;
}

function writeElement(out, el, depth) {
  const pad = '  '.repeat(depth);
  out.append(span('t', `<${el.tagName}`));
  for (const a of el.attributes) {
    out.append(' ', span('a', a.name), span('x', '='), span('v', `'${a.value}'`));
  }
  const children = [...el.childNodes].filter((n) => n.nodeType === 1 || n.nodeType === 3);
  if (children.length === 0) {
    out.append(span('t', '/>'));
    return;
  }
  out.append(span('t', '>'));
  const hasText = children.some((n) => n.nodeType === 3 && n.nodeValue !== '');
  for (const child of children) {
    if (child.nodeType === 3) {
      out.append(span('x', child.nodeValue));
    } else {
      if (!hasText) out.append(`\n${pad}  `);
      writeElement(out, child, hasText ? depth : depth + 1);
    }
  }
  if (!hasText) out.append(`\n${pad}`);
  out.append(span('t', `</${el.tagName}>`));
}

// ---- Live updates ----

function connectLive() {
  state.source?.close();
  state.source = null;
  const button = $('live');
  button.setAttribute('aria-pressed', String(state.live));
  button.classList.remove('reconnecting');
  $('live-text').textContent = state.live ? 'Live' : 'Paused';
  if (!state.live) return;
  const source = new EventSource(`/api/v1/stream?${filterParams()}`);
  let dropped = false;
  source.addEventListener('entry', (msg) => {
    const e = JSON.parse(msg.data);
    if (!state.devices.has(e.installId)) loadDevices();
    prepend([e]);
  });
  source.addEventListener('open', () => {
    button.classList.remove('reconnecting');
    $('live-text').textContent = 'Live';
    if (dropped) catchUp(); // fetch what arrived while the stream was down
  });
  source.addEventListener('error', () => {
    dropped = true;
    button.classList.add('reconnecting');
    $('live-text').textContent = 'Reconnecting';
  });
  state.source = source;
}

async function catchUp() {
  const newest = state.entries[0];
  if (!newest) return;
  const p = filterParams();
  p.set('after', newest.id);
  p.set('limit', '1000');
  prepend(await api(`/api/v1/entries?${p}`).catch(() => []));
}

// ---- Helpers ----

function clock(ms) {
  const d = new Date(ms);
  const two = (n) => String(n).padStart(2, '0');
  return `${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`;
}

function ago(ms) {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  return new Date(ms).toLocaleDateString();
}

function setStatus(text) {
  $('status').textContent = text;
}

function renderContext() {
  const parts = [];
  if (state.installId) parts.push(`Device: ${deviceName(state.devices.get(state.installId))}`);
  if (state.session) parts.push(`Session: ${state.session.slice(0, 8)}…`);
  if (state.label) parts.push(`Round: ${state.label}`);
  $('context').textContent = parts.join(' · ');
}

// ---- Wiring ----

let debounce;
function onFilterChange() {
  clearTimeout(debounce);
  debounce = setTimeout(reload, 300);
}

$('filters').addEventListener('input', onFilterChange);
$('filters').addEventListener('submit', (e) => e.preventDefault());
$('f-reset').addEventListener('click', () => {
  $('filters').reset();
  reload();
});
$('label').addEventListener('change', () => {
  state.label = $('label').value;
  state.session = null;
  loadSessions();
  reload();
});
$('all-devices').addEventListener('click', () => {
  state.installId = null;
  state.session = null;
  renderDevices();
  loadSessions();
  reload();
});
$('live').addEventListener('click', () => {
  state.live = !state.live;
  if (state.live) catchUp();
  connectLive();
});
$('older').addEventListener('click', loadOlder);

(async () => {
  await Promise.all([loadDevices(), loadLabels()]);
  await reload();
  setInterval(() => { loadDevices(); if (state.installId) loadSessions(); }, 10_000);
  setInterval(loadLabels, 60_000);
})();
