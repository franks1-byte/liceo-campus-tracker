/* Liceo Campus Tracker — map, GPS, camera, offline queue, Supabase sync. */
(() => {
  'use strict';
  const CFG = window.APP_CONFIG;
  const sb = window.supabase.createClient(CFG.SUPABASE_URL, CFG.SUPABASE_KEY);
  const PHOTO_BASE = CFG.SUPABASE_URL + '/storage/v1/object/public/photos/';

  const BUILDING_KINDS = ['Academic', 'Office', 'Library', 'Laboratory', 'Gym / Sports', 'Canteen', 'Chapel', 'Clinic', 'Gate', 'Parking', 'Other'];
  const ROOM_KINDS = ['Classroom', 'Laboratory', 'Office', 'Library', 'Auditorium', 'Comfort room', 'Other'];

  const $ = (id) => document.getElementById(id);

  /** Build DOM safely: user text only ever goes in as text nodes. */
  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'class') el.className = v;
      else if (k in el && k !== 'list' && k !== 'form') el[k] = v;
      else el.setAttribute(k, v);
    }
    for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid);
    return el;
  }

  // ---------- local storage (IndexedDB) ----------
  const dbReady = new Promise((resolve, reject) => {
    const req = indexedDB.open('liceo-tracker', 1);
    req.onupgradeneeded = () => {
      req.result.createObjectStore('kv');
      req.result.createObjectStore('queue', { keyPath: 'qid', autoIncrement: true });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  async function idb(store, mode, fn) {
    const db = await dbReady;
    return new Promise((resolve, reject) => {
      const tx = db.transaction(store, mode);
      const req = fn(tx.objectStore(store));
      tx.oncomplete = () => resolve(req && req.result);
      tx.onerror = () => reject(tx.error);
    });
  }
  const kvGet = (k) => idb('kv', 'readonly', (s) => s.get(k)).catch(() => undefined);
  const kvSet = (k, v) => idb('kv', 'readwrite', (s) => s.put(v, k)).catch(() => {});
  const queueAll = () => idb('queue', 'readonly', (s) => s.getAll()).catch(() => []);
  const queueAdd = (op) => idb('queue', 'readwrite', (s) => s.add(op));
  const queueDel = (qid) => idb('queue', 'readwrite', (s) => s.delete(qid));

  // ---------- state ----------
  const S = {
    snapshot: { buildings: [], rooms: [] }, // last copy from the server
    queue: [],                              // changes waiting to upload
    buildings: [], rooms: [],               // snapshot + queued changes
    session: null, profile: null,
    pos: null, watchId: null, guide: null,
    filter: 'all', q: '', lastSync: null, installEvt: null
  };
  const uid = () => S.session && S.session.user.id;
  const isAdmin = () => !!(S.profile && S.profile.role === 'admin');
  const canEdit = (row) => !!uid() && (isAdmin() || row.created_by === uid());

  /** Rebuild what the user sees: server copy with the offline changes replayed on top. */
  function rebuild() {
    const t = { buildings: S.snapshot.buildings.map((r) => ({ ...r })), rooms: S.snapshot.rooms.map((r) => ({ ...r })) };
    for (const op of S.queue) {
      const arr = t[op.table];
      const i = arr.findIndex((r) => r.id === (op.id || (op.row && op.row.id)));
      if (op.op === 'insert' && i < 0) arr.push({ ...op.row, _pending: true, _blob: op.blob });
      else if (op.op === 'update' && i >= 0) arr[i] = { ...arr[i], ...op.patch, _pending: true, _blob: op.blob || arr[i]._blob };
      else if (op.op === 'delete' && i >= 0) {
        arr.splice(i, 1);
        if (op.table === 'buildings') t.rooms = t.rooms.filter((r) => r.building_id !== op.id);
      }
    }
    S.buildings = t.buildings; S.rooms = t.rooms;
    renderAll();
  }

  // ---------- helpers ----------
  let toastTimer;
  function toast(msg) {
    const el = $('toast');
    el.textContent = msg;
    // sit inside the open dialog (if any) so the message is not hidden behind it
    ([...document.querySelectorAll('dialog[open]')].pop() || document.body).append(el);
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, 3800);
  }
  function distance(a, b) {
    const R = 6371000, rad = Math.PI / 180;
    const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
    const x = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(x));
  }
  const DIRS_SHORT = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
  const DIRS = ['north', 'north-east', 'east', 'south-east', 'south', 'south-west', 'west', 'north-west'];
  /** Compass bearing in degrees (0 = north, 90 = east) from a to b. */
  function bearing(a, b) {
    const rad = Math.PI / 180;
    const y = Math.sin((b.lng - a.lng) * rad) * Math.cos(b.lat * rad);
    const x = Math.cos(a.lat * rad) * Math.sin(b.lat * rad) - Math.sin(a.lat * rad) * Math.cos(b.lat * rad) * Math.cos((b.lng - a.lng) * rad);
    return (Math.atan2(y, x) / rad + 360) % 360;
  }
  const compass = (a, b) => DIRS[Math.round(bearing(a, b) / 45) % 8];
  const fmtDist = (m) => (m < 1000 ? Math.round(m) + ' m' : (m / 1000).toFixed(m < 10000 ? 1 : 0) + ' km');
  const floorLabel = (f) => (f == null ? null : f === 0 ? 'Ground floor' : 'Floor ' + f);
  const buildingOf = (room) => S.buildings.find((b) => b.id === room.building_id);
  const initials = (b) => (b.code || b.name).replace(/[^A-Za-z0-9 ]/g, '').split(/\s+/).map((w) => w[0]).join('').slice(0, 3).toUpperCase() || '•';
  const blobUrls = new WeakMap();
  function photoSrc(row) {
    if (row._blob) { if (!blobUrls.has(row._blob)) blobUrls.set(row._blob, URL.createObjectURL(row._blob)); return blobUrls.get(row._blob); }
    return row.photo_path ? PHOTO_BASE + row.photo_path : null;
  }
  const isNetworkError = (err) => !navigator.onLine || /fetch|network|load failed/i.test((err && err.message) || '');

  // ---------- map ----------
  const map = L.map('map', { zoomControl: false, attributionControl: true }).setView([CFG.CAMPUS.lat, CFG.CAMPUS.lng], CFG.CAMPUS.zoom);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxNativeZoom: 19, maxZoom: 21, crossOrigin: true,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
  }).addTo(map);
  const markers = L.layerGroup().addTo(map);
  let meMarker = null, meCircle = null, guideLine = null;

  function renderMarkers() {
    markers.clearLayers();
    for (const b of S.buildings) {
      const icon = L.divIcon({ className: '', iconSize: [32, 32], iconAnchor: [16, 32], html: '' });
      const m = L.marker([b.lat, b.lng], { icon, title: b.name, alt: b.name, keyboard: true }).addTo(markers);
      const pin = h('div', { class: 'pin' + (b._pending ? ' pending' : '') }, h('b', {}, initials(b)));
      m.getElement().append(pin);
      m.on('click', () => openBuilding(b.id));
    }
  }

  /** Save the campus area tiles once, so the map still draws with no signal. */
  async function warmTiles() {
    if (!navigator.onLine || (await kvGet('tilesWarm'))) return;
    const { lat, lng } = CFG.CAMPUS, span = 0.003, jobs = [];
    const tx = (lon, z) => Math.floor(((lon + 180) / 360) * 2 ** z);
    const ty = (la, z) => Math.floor(((1 - Math.log(Math.tan((la * Math.PI) / 180) + 1 / Math.cos((la * Math.PI) / 180)) / Math.PI) / 2) * 2 ** z);
    for (const z of [16, 17, 18]) {
      for (let x = tx(lng - span, z); x <= tx(lng + span, z); x++)
        for (let y = ty(lat + span, z); y <= ty(lat - span, z); y++)
          jobs.push(fetch(`https://tile.openstreetmap.org/${z}/${x}/${y}.png`, { mode: 'cors' }).catch(() => {}));
    }
    await Promise.all(jobs);
    kvSet('tilesWarm', true);
  }

  // ---------- GPS ----------
  function startGps(recenter) {
    if (!('geolocation' in navigator)) { toast('This device has no GPS / location support.'); return; }
    if (S.watchId != null) { if (recenter && S.pos) map.setView([S.pos.lat, S.pos.lng], Math.max(map.getZoom(), 18)); return; }
    $('locateBtn').classList.add('on');
    let first = true;
    S.watchId = navigator.geolocation.watchPosition((p) => {
      S.pos = { lat: p.coords.latitude, lng: p.coords.longitude, acc: p.coords.accuracy };
      const ll = [S.pos.lat, S.pos.lng];
      if (!meMarker) {
        meCircle = L.circle(ll, { radius: S.pos.acc, color: '#1a73e8', weight: 1, fillOpacity: 0.12 }).addTo(map);
        meMarker = L.marker(ll, { icon: L.divIcon({ className: '', iconSize: [18, 18], iconAnchor: [9, 9], html: '<div class="me"></div>' }), interactive: false, keyboard: false }).addTo(map);
      } else { meMarker.setLatLng(ll); meCircle.setLatLng(ll).setRadius(S.pos.acc); }
      if (first && recenter) map.setView(ll, Math.max(map.getZoom(), 18));
      first = false;
      renderList(); renderGuide();
    }, (err) => {
      stopGps();
      toast(err.code === 1 ? 'Location permission was denied. Allow it in your browser settings.' : 'Could not get your location. Try again outdoors.');
    }, { enableHighAccuracy: true, maximumAge: 5000, timeout: 20000 });
  }
  function stopGps() {
    if (S.watchId != null) navigator.geolocation.clearWatch(S.watchId);
    S.watchId = null; $('locateBtn').classList.remove('on');
  }
  function startGuide(name, lat, lng) {
    S.guide = { name, lat, lng };
    closeSheet(); setView('ar'); renderGuide();
  }
  function renderGuide(fit) {
    const g = S.guide;
    $('guideBar').hidden = !g;
    renderMini();
    if (guideLine) { guideLine.remove(); guideLine = null; }
    if (!g) return;
    $('guideName').textContent = g.name;
    if (!S.pos) { $('guideInfo').textContent = 'Waiting for GPS…'; map.setView([g.lat, g.lng], 18); return; }
    const d = distance(S.pos, g);
    $('guideInfo').textContent = d < Math.max(15, S.pos.acc)
      ? `You are here (GPS ±${Math.round(S.pos.acc)} m)`
      : `${fmtDist(d)} to the ${compass(S.pos, g)} · GPS ±${Math.round(S.pos.acc)} m`;
    guideLine = L.polyline([[S.pos.lat, S.pos.lng], [g.lat, g.lng]], { color: '#7a1420', weight: 4, dashArray: '8 8' }).addTo(map);
    if (fit) map.fitBounds(guideLine.getBounds(), { padding: [60, 60], maxZoom: 19 });
  }

  // ---------- rendering ----------
  function renderAll() { renderMarkers(); renderMini(); renderList(); renderStatus(); }

  function renderStatus() {
    const net = $('netPill');
    net.textContent = navigator.onLine ? 'Online' : 'Offline';
    net.classList.toggle('off', !navigator.onLine);
    const pill = $('syncPill');
    pill.hidden = S.queue.length === 0;
    pill.textContent = `${S.queue.length} to sync`;
    $('subtitle').textContent = S.buildings.length || S.rooms.length
      ? `${S.buildings.length} building${S.buildings.length === 1 ? '' : 's'} · ${S.rooms.length} room${S.rooms.length === 1 ? '' : 's'}`
      : 'Buildings, classrooms and more';
  }

  function listItem(kind, row) {
    const b = kind === 'room' ? buildingOf(row) : row;
    const d = S.pos && b ? fmtDist(distance(S.pos, b)) : '';
    const sub = kind === 'room'
      ? [row.kind, b && b.name, floorLabel(row.floor)].filter(Boolean).join(' · ')
      : [row.kind, row.code, ((n) => `${n} room${n === 1 ? '' : 's'}`)(S.rooms.filter((r) => r.building_id === row.id).length)].filter(Boolean).join(' · ');
    return h('li', {}, h('button', { class: 'item', type: 'button', onclick: () => (kind === 'room' ? openRoom(row.id) : openBuilding(row.id)) },
      h('span', { class: 'ico' + (kind === 'room' ? ' room' : ''), 'aria-hidden': 'true' }, kind === 'room' ? 'RM' : initials(row)),
      h('span', {}, h('span', { class: 't' }, row.name, row._pending && h('span', { class: 'tag' }, 'not synced')), h('span', { class: 's', style: 'display:block' }, sub)),
      h('span', { class: 'd' }, d)));
  }

  function renderList() {
    const q = S.q.trim().toLowerCase();
    const match = (...parts) => !q || parts.filter(Boolean).join(' ').toLowerCase().includes(q);
    let items = [];
    if (S.filter !== 'rooms') items.push(...S.buildings.filter((b) => match(b.name, b.code, b.kind, b.description)).map((b) => ['building', b]));
    if (S.filter !== 'buildings') items.push(...S.rooms.filter((r) => { const b = buildingOf(r); return match(r.name, r.kind, r.notes, b && b.name, b && b.code); }).map((r) => ['room', r]));
    const dist = ([k, r]) => { const b = k === 'room' ? buildingOf(r) : r; return S.pos && b ? distance(S.pos, b) : 0; };
    items.sort((a, b) => dist(a) - dist(b) || a[1].name.localeCompare(b[1].name, undefined, { numeric: true }));
    const list = $('list');
    if (items.length) { list.replaceChildren(...items.map(([k, r]) => listItem(k, r))); return; }
    const nothing = !S.buildings.length && !S.rooms.length;
    list.replaceChildren(h('li', { class: 'empty' },
      h('strong', {}, nothing ? 'No places yet' : 'Nothing matches'),
      nothing ? 'Stand at a building, tap Add, and pin it with your GPS.' : 'Try a different word or filter.'));
  }

  // ---------- sheets ----------
  const sheet = $('sheet'), sheetBody = $('sheetBody');
  function openSheet(...nodes) { sheetBody.replaceChildren(...nodes.flat().filter(Boolean)); if (!sheet.open) sheet.showModal(); sheet.scrollTop = 0; }
  function closeSheet() { if (sheet.open) sheet.close(); }
  sheet.addEventListener('click', (e) => { if (e.target === sheet) closeSheet(); });
  sheet.addEventListener('close', () => { if (!uid()) rememberGuest(); });
  const head = (title, sub) => h('div', { class: 'sheet-head' },
    h('div', {}, h('h2', {}, title), sub && h('p', {}, sub)),
    h('button', { class: 'x', type: 'button', 'aria-label': 'Close', onclick: closeSheet }, '✕'));
  const fact = (k, v) => v != null && v !== '' && h('div', {}, h('dt', {}, k), h('dd', {}, String(v)));

  function openBuilding(id) {
    const b = S.buildings.find((x) => x.id === id);
    if (!b) return;
    const rooms = S.rooms.filter((r) => r.building_id === id).sort((x, y) => (x.floor ?? 0) - (y.floor ?? 0) || x.name.localeCompare(y.name, undefined, { numeric: true }));
    const src = photoSrc(b);
    openSheet(
      head(b.name, [b.kind, b._pending && 'waiting to sync'].filter(Boolean).join(' · ')),
      src && h('img', { class: 'photo', src, alt: 'Photo of ' + b.name }),
      h('dl', { class: 'facts' }, fact('Code', b.code), fact('Floors', b.floors), fact('Rooms', rooms.length),
        fact('Distance', S.pos ? fmtDist(distance(S.pos, b)) : null), fact('GPS', `${b.lat.toFixed(5)}, ${b.lng.toFixed(5)}`)),
      b.description && h('p', { style: 'margin:0;overflow-wrap:anywhere' }, b.description),
      h('div', { class: 'row' },
        h('button', { class: 'btn', type: 'button', onclick: () => startGuide(b.name, b.lat, b.lng) }, 'Guide me'),
        h('button', { class: 'btn ghost', type: 'button', onclick: () => { closeSheet(); setView('map'); map.setView([b.lat, b.lng], 19); } }, 'Show on map'),
        h('a', { class: 'btn ghost', target: '_blank', rel: 'noopener', href: `https://www.google.com/maps/dir/?api=1&destination=${b.lat},${b.lng}&travelmode=walking` }, 'Google Maps')),
      h('p', { class: 'sub' }, 'Rooms'),
      rooms.length ? h('ul', { class: 'list' }, rooms.map((r) => listItem('room', r))) : h('p', { class: 'note' }, 'No rooms listed for this building yet.'),
      uid() && h('button', { class: 'btn ghost', type: 'button', onclick: () => openForm('room', null, b.id) }, '+ Add a room here'),
      canEdit(b) && h('div', { class: 'row' },
        h('button', { class: 'btn ghost', type: 'button', onclick: () => openForm('building', b) }, 'Edit'),
        h('button', { class: 'btn danger', type: 'button', onclick: () => remove('buildings', b, rooms.length) }, 'Delete')));
  }

  function openRoom(id) {
    const r = S.rooms.find((x) => x.id === id);
    if (!r) return;
    const b = buildingOf(r), src = photoSrc(r);
    openSheet(
      head(r.name, [r.kind, r._pending && 'waiting to sync'].filter(Boolean).join(' · ')),
      src && h('img', { class: 'photo', src, alt: 'Photo of ' + r.name }),
      h('dl', { class: 'facts' }, fact('Building', b && b.name), fact('Floor', floorLabel(r.floor)), fact('Capacity', r.capacity),
        fact('Distance', S.pos && b ? fmtDist(distance(S.pos, b)) : null)),
      r.notes && h('p', { style: 'margin:0;overflow-wrap:anywhere' }, r.notes),
      b && h('div', { class: 'row' },
        h('button', { class: 'btn', type: 'button', onclick: () => startGuide(`${r.name} — ${b.name}`, b.lat, b.lng) }, 'Guide me'),
        h('button', { class: 'btn ghost', type: 'button', onclick: () => openBuilding(b.id) }, 'View building')),
      canEdit(r) && h('div', { class: 'row' },
        h('button', { class: 'btn ghost', type: 'button', onclick: () => openForm('room', r) }, 'Edit'),
        h('button', { class: 'btn danger', type: 'button', onclick: () => remove('rooms', r, 0) }, 'Delete')));
  }

  // ---------- add / edit form ----------
  let draft = null; // { loc, blob } for the form currently open
  function openForm(kind, existing, buildingId) {
    if (!uid()) {
      openSheet(head('Sign in to add places', 'Guests can look around; students and admins can add.'),
        h('button', { class: 'btn', type: 'button', onclick: openAccount }, 'Sign in or create an account'));
      return;
    }
    if (kind === 'room' && !S.buildings.length) kind = 'building';
    draft = { blob: null, loc: existing && kind === 'building' ? { lat: existing.lat, lng: existing.lng } : S.pos ? { lat: S.pos.lat, lng: S.pos.lng } : null };
    const e = existing || {};
    const field = (label, input) => h('label', { class: 'f' }, label, input);
    const opt = (list, cur) => list.map((k) => h('option', { value: k, selected: k === cur }, k));
    const name = h('input', { name: 'name', required: true, maxLength: 120, value: e.name || '', placeholder: kind === 'room' ? 'e.g. Room 204' : 'e.g. Engineering Building' });
    const err = h('p', { class: 'err', hidden: true });
    const locText = h('span', {});
    const showLoc = () => { locText.replaceChildren(draft.loc ? h('b', {}, `${draft.loc.lat.toFixed(5)}, ${draft.loc.lng.toFixed(5)}`) : 'No location set yet.'); };
    const preview = h('img', { class: 'photo', alt: 'Photo preview', hidden: !photoSrc(e), src: photoSrc(e) || '' });
    let fields;
    if (kind === 'building') {
      showLoc();
      fields = [
        field('Building name', name),
        h('div', { class: 'two' },
          field('Type', h('select', { name: 'kind' }, opt(BUILDING_KINDS, e.kind || 'Academic'))),
          field('Short code', h('input', { name: 'code', maxLength: 12, value: e.code || '', placeholder: 'e.g. ENG' }))),
        field('Number of floors', h('input', { name: 'floors', type: 'number', min: 1, max: 60, inputMode: 'numeric', value: e.floors ?? '' })),
        field('Description', h('textarea', { name: 'description', maxLength: 600, value: e.description || '' })),
        h('div', { class: 'note' }, 'Location: ', locText,
          h('div', { class: 'row', style: 'margin-top:8px' },
            h('button', { class: 'btn small', type: 'button', onclick: () => useGps(showLoc) }, 'Use my GPS'),
            h('button', { class: 'btn small ghost', type: 'button', onclick: () => pickOnMap(showLoc) }, 'Pick on map')))
      ];
    } else {
      fields = [
        field('Room name or number', name),
        field('Building', h('select', { name: 'building_id', required: true },
          [...S.buildings].sort((a, b) => a.name.localeCompare(b.name)).map((b) => h('option', { value: b.id, selected: b.id === (e.building_id || buildingId) }, b.name)))),
        h('div', { class: 'two' },
          field('Type', h('select', { name: 'kind' }, opt(ROOM_KINDS, e.kind || 'Classroom'))),
          field('Floor (0 = ground)', h('input', { name: 'floor', type: 'number', min: -5, max: 60, inputMode: 'numeric', value: e.floor ?? '' }))),
        field('Capacity', h('input', { name: 'capacity', type: 'number', min: 0, max: 100000, inputMode: 'numeric', value: e.capacity ?? '' })),
        field('Notes', h('textarea', { name: 'notes', maxLength: 600, value: e.notes || '', placeholder: 'e.g. beside the stairs, aircon, projector' }))
      ];
    }
    const form = h('form', {
      onsubmit: (ev) => {
        ev.preventDefault();
        const f = Object.fromEntries(new FormData(form));
        const num = (v) => (v === '' || v == null ? null : Number(v));
        const txt = (v) => (v && v.trim() ? v.trim() : null);
        let row;
        if (kind === 'building') {
          if (!draft.loc) { err.textContent = 'Set the location first: use your GPS or pick it on the map.'; err.hidden = false; return; }
          row = { name: f.name.trim(), kind: f.kind, code: txt(f.code), floors: num(f.floors), description: txt(f.description), lat: draft.loc.lat, lng: draft.loc.lng };
        } else {
          row = { name: f.name.trim(), kind: f.kind, building_id: f.building_id, floor: num(f.floor), capacity: num(f.capacity), notes: txt(f.notes) };
        }
        if (!row.name) { err.textContent = 'Give it a name.'; err.hidden = false; return; }
        save(kind === 'building' ? 'buildings' : 'rooms', existing, row, draft.blob);
      }
    },
      !existing && S.buildings.length > 0 && h('div', { class: 'seg' },
        h('button', { type: 'button', class: kind === 'building' ? 'on' : '', onclick: () => openForm('building') }, 'Building'),
        h('button', { type: 'button', class: kind === 'room' ? 'on' : '', onclick: () => openForm('room', null, buildingId) }, 'Room')),
      fields,
      preview,
      h('button', { class: 'btn ghost', type: 'button', onclick: () => takePhoto((blob) => { draft.blob = blob; preview.src = URL.createObjectURL(blob); preview.hidden = false; }) }, 'Take a photo'),
      err,
      h('button', { class: 'btn', type: 'submit' }, existing ? 'Save changes' : navigator.onLine ? 'Save' : 'Save offline'));
    openSheet(head(existing ? 'Edit ' + kind : 'Add a ' + kind, navigator.onLine ? null : 'You are offline. It will upload when you reconnect.'), form);
  }

  function useGps(done) {
    if (!('geolocation' in navigator)) { toast('This device has no GPS / location support.'); return; }
    toast('Getting your location…');
    navigator.geolocation.getCurrentPosition((p) => {
      draft.loc = { lat: p.coords.latitude, lng: p.coords.longitude };
      done(); toast(`Location set (GPS ±${Math.round(p.coords.accuracy)} m)`);
    }, () => toast('Could not get your location. Allow location access, or pick on the map.'), { enableHighAccuracy: true, timeout: 20000 });
  }
  function pickOnMap(done) {
    sheet.close(); setView('map');
    if (draft.loc) map.setView([draft.loc.lat, draft.loc.lng], Math.max(map.getZoom(), 18));
    $('crosshair').hidden = false; $('pickBar').hidden = false; $('guideBar').hidden = true;
    const finish = (set) => {
      $('crosshair').hidden = true; $('pickBar').hidden = true; $('guideBar').hidden = !S.guide;
      if (set) { const c = map.getCenter(); draft.loc = { lat: c.lat, lng: c.lng }; done(); }
      sheet.showModal();
    };
    $('pickOk').onclick = () => finish(true);
    $('pickCancel').onclick = () => finish(false);
  }

  // ---------- camera ----------
  const cam = $('camera'), video = $('video');
  let camStream = null, camDone = null;
  async function shrink(source) {
    const bmp = await createImageBitmap(source);
    const scale = Math.min(1, 1280 / Math.max(bmp.width, bmp.height));
    const c = h('canvas', { width: Math.round(bmp.width * scale), height: Math.round(bmp.height * scale) });
    c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
    return new Promise((res) => c.toBlob(res, 'image/jpeg', 0.8));
  }
  function stopCam() { if (camStream) camStream.getTracks().forEach((t) => t.stop()); camStream = null; video.srcObject = null; if (cam.open) cam.close(); }
  async function takePhoto(done) {
    camDone = done;
    try {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error('no camera api');
      camStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 } }, audio: false });
      video.srcObject = camStream;
      cam.showModal();
    } catch (e) {
      // no camera, or permission refused: fall back to the phone's own camera / gallery picker
      if (navigator.userActivation && !navigator.userActivation.isActive) { cam.showModal(); toast('Camera unavailable. Tap Gallery to choose a photo.'); }
      else $('camFile').click();
    }
  }
  $('camShoot').onclick = async () => {
    if (!video.videoWidth) return;
    const blob = await shrink(video);
    stopCam(); if (blob && camDone) camDone(blob);
  };
  $('camClose').onclick = stopCam;
  cam.addEventListener('close', stopCam);
  $('camFile').onchange = async (e) => {
    const file = e.target.files[0]; e.target.value = '';
    if (!file) return;
    try { const blob = await shrink(file); stopCam(); if (blob && camDone) camDone(blob); }
    catch (err) { toast('That image could not be read.'); }
  };

  // ---------- AR camera view: labels float where each building really is ----------
  const arVideo = $('arVideo'), arLayer = $('arLayer');
  const AR = { on: false, resume: false, stream: null, heading: null, pitch: 90, raf: 0, manual: false, sensor: false, els: new Map() };
  const AR_FOV = 58; // degrees the phone camera sees side to side when held upright

  function onOrient(e) {
    let hd = null;
    if (typeof e.webkitCompassHeading === 'number') hd = e.webkitCompassHeading; // iPhone
    else if (e.absolute && e.alpha != null && e.beta != null && e.gamma != null) {
      // direction the back camera points, from the phone's rotation (W3C device orientation formula)
      const r = Math.PI / 180, cZ = Math.cos(e.alpha * r), sZ = Math.sin(e.alpha * r), sX = Math.sin(e.beta * r), cY = Math.cos(e.gamma * r), sY = Math.sin(e.gamma * r);
      const vx = -cZ * sY - sZ * sX * cY, vy = -sZ * sY + cZ * sX * cY;
      hd = (Math.atan2(vx, vy) / r + 360) % 360;
    }
    if (hd == null || Number.isNaN(hd)) return;
    AR.sensor = true; AR.manual = false;
    const turn = ((hd - (AR.heading ?? hd) + 540) % 360) - 180; // smooth out compass jitter
    AR.heading = ((AR.heading ?? hd) + turn * 0.2 + 360) % 360;
    if (e.beta != null) AR.pitch += (e.beta - AR.pitch) * 0.2;
  }

  async function startAR() {
    // iPhone only grants motion access when asked from inside a tap, so ask before anything else
    const motion = window.DeviceOrientationEvent && typeof DeviceOrientationEvent.requestPermission === 'function'
      ? DeviceOrientationEvent.requestPermission().catch(() => 'denied') : Promise.resolve('granted');
    $('arStart').hidden = true;
    if (AR.on) return;
    Object.assign(AR, { on: true, heading: null, pitch: 90, manual: false, sensor: false });
    startGps(false);
    await motion;
    if (!AR.on) return;
    window.addEventListener('deviceorientationabsolute', onOrient);
    window.addEventListener('deviceorientation', onOrient);
    setTimeout(() => { if (AR.on && !AR.sensor) { AR.manual = true; AR.heading = AR.heading ?? 0; } }, 2000);
    cancelAnimationFrame(AR.raf); AR.raf = requestAnimationFrame(drawAR);
    setTimeout(() => mini.invalidateSize(), 60);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false });
      if (!AR.on) { stream.getTracks().forEach((t) => t.stop()); return; }
      AR.stream = stream; arVideo.srcObject = stream;
    } catch (e) { toast('Camera not available. Showing labels without the camera picture.'); }
  }
  function stopAR() {
    AR.on = false;
    window.removeEventListener('deviceorientationabsolute', onOrient);
    window.removeEventListener('deviceorientation', onOrient);
    cancelAnimationFrame(AR.raf);
    if (AR.stream) AR.stream.getTracks().forEach((t) => t.stop());
    AR.stream = null; arVideo.srcObject = null;
  }
  $('arGo').onclick = startAR;
  $('arStop').onclick = () => { S.guide = null; renderGuide(); };
  $('miniBtn').onclick = () => setView('map');
  // save battery: release the camera while the app is in the background
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && AR.on) { stopAR(); AR.resume = true; }
    else if (!document.hidden && AR.resume) { AR.resume = false; if (document.body.dataset.view === 'ar') startAR(); }
  });

  // ---------- corner minimap (rotates so the way you face is always up) ----------
  const mini = L.map('miniMap', { zoomControl: false, attributionControl: false, dragging: false, touchZoom: false, scrollWheelZoom: false,
    doubleClickZoom: false, boxZoom: false, keyboard: false, zoomSnap: 0, fadeAnimation: false }).setView([CFG.CAMPUS.lat, CFG.CAMPUS.lng], 17.6);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxNativeZoom: 19, maxZoom: 21, crossOrigin: true }).addTo(mini);
  const miniBlips = L.layerGroup().addTo(mini);
  function renderMini() {
    miniBlips.clearLayers();
    const g = S.guide;
    if (g && S.pos) L.polyline([[S.pos.lat, S.pos.lng], [g.lat, g.lng]], { color: '#c05cff', weight: 5, opacity: 0.9, interactive: false }).addTo(miniBlips);
    for (const b of S.buildings) {
      const target = !!g && g.lat === b.lat && g.lng === b.lng;
      L.circleMarker([b.lat, b.lng], { radius: target ? 8 : 5.500, color: '#111', weight: 2, fillColor: target ? '#c05cff' : '#f2c744', fillOpacity: 1, interactive: false }).addTo(miniBlips);
    }
    if (S.pos) mini.setView([S.pos.lat, S.pos.lng], mini.getZoom(), { animate: false });
  }
  function drawMini() {
    const hd = AR.heading || 0, r = (hd * Math.PI) / 180;
    $('miniRot').style.transform = `rotate(${(-hd).toFixed(1)}deg)`;
    // keep the N badge on the edge of the minimap, in the direction of north
    const sx = -Math.sin(r), sy = -Math.cos(r), t = Math.min(79 / Math.abs(sx || 1e-6), 56 / Math.abs(sy || 1e-6));
    $('miniN').style.transform = `translate(${(79 + sx * t).toFixed(1)}px, ${(56 + sy * t).toFixed(1)}px)`;
    const gps = S.pos ? Math.max(12, Math.min(100, 110 - S.pos.acc * 2)) : 0;
    $('barGps').firstChild.style.width = gps + '%';
    $('barNet').classList.toggle('off', !navigator.onLine);
    $('barNet').firstChild.style.width = navigator.onLine ? '100%' : '45%';
  }

  // no compass (laptops, some tablets): drag to look around instead
  let dragX = null;
  arLayer.addEventListener('pointerdown', (e) => { if (AR.manual && e.target === arLayer) dragX = e.clientX; });
  arLayer.addEventListener('pointermove', (e) => {
    if (dragX == null) return;
    AR.heading = (AR.heading - (e.clientX - dragX) * (AR_FOV / arLayer.clientWidth) + 360) % 360; dragX = e.clientX;
  });
  window.addEventListener('pointerup', () => { dragX = null; });

  function drawAR() {
    AR.raf = requestAnimationFrame(drawAR);
    const w = arLayer.clientWidth, ht = arLayer.clientHeight, seen = new Set();
    let side = 0, msg, lock = null, shown = 0;

    if (S.pos && AR.heading != null) {
      // tilting the phone up pushes the horizon (and the labels standing on it) down the screen
      const horizon = Math.min(ht - 190, Math.max(230, ht / 2 + 8 + (AR.pitch - 90) * (ht / 70)));
      const placed = [];
      const items = S.buildings
        .map((b, i) => ({ b, i, d: distance(S.pos, b), off: ((bearing(S.pos, b) - AR.heading + 540) % 360) - 180 }))
        .sort((x, y) => x.d - y.d);
      // the building closest to the centre of the view gets the lock
      for (const it of items) if (Math.abs(it.off) < 7 && (!lock || Math.abs(it.off) < Math.abs(lock.off))) lock = it;
      for (const it of items) {
        const { b, d, off } = it;
        const target = !!S.guide && S.guide.lat === b.lat && S.guide.lng === b.lng;
        if (Math.abs(off) > AR_FOV / 2 + 10) { if (target) side = off < 0 ? -1 : 1; continue; }
        if (placed.length >= 8 && !target) continue;
        const x = w / 2 + (off / (AR_FOV / 2)) * (w / 2);
        let y = horizon;
        while (y > 210 && placed.some((p) => Math.abs(p.x - x) < 150 && Math.abs(p.y - y) < 96)) y -= 100;
        placed.push({ x, y });
        let el = AR.els.get(b.id);
        if (!el) {
          el = h('button', { class: 'ar-mark', type: 'button', onclick: () => openBuilding(b.id) },
            h('span', { class: 'box' }, h('span', { class: 'id' }), h('span', { class: 'n' }), h('span', { class: 'm' })),
            h('span', { class: 'stem' }), h('span', { class: 'dot' }));
          AR.els.set(b.id, el); arLayer.append(el);
        }
        const near = d < Math.max(15, S.pos.acc);
        const meta = near ? 'YOU ARE HERE' : `${fmtDist(d)} · ${DIRS_SHORT[Math.round(bearing(S.pos, b) / 45) % 8]}`;
        const id = `TRK.${String(it.i + 1).padStart(2, '0')}${it === lock ? ' · LOCKED' : target ? ' · TARGET' : ''}`;
        const [idEl, n, m] = [el.querySelector('.id'), el.querySelector('.n'), el.querySelector('.m')];
        if (idEl.textContent !== id) idEl.textContent = id;
        if (n.textContent !== b.name) n.textContent = b.name;
        if (m.textContent !== meta) m.textContent = meta;
        el.classList.toggle('target', target);
        el.classList.toggle('locked', it === lock);
        el.style.zIndex = String(100000 - Math.round(d));
        const scale = Math.min(1.15, Math.max(0.72, 1.15 - d / 500));
        el.style.transform = `translate(${x.toFixed(1)}px, ${y.toFixed(1)}px) translate(-50%, -100%) scale(${scale.toFixed(2)})`;
        seen.add(b.id); shown++;
      }
    }
    for (const [id, el] of AR.els) if (!seen.has(id)) { el.remove(); AR.els.delete(id); }

    if (!S.pos) msg = 'Acquiring GPS…';
    else if (AR.heading == null) msg = 'Calibrating compass… hold the phone upright and move it in a figure 8';
    else if (!S.buildings.length) msg = 'No buildings to track yet. Add one first.';
    else if (lock) { const rooms = S.rooms.filter((r) => r.building_id === lock.b.id).length; msg = `Locked: ${lock.b.name} · ${fmtDist(lock.d)} · ${rooms} room${rooms === 1 ? '' : 's'} · tap it to open`; }
    else if (AR.manual) msg = 'No compass on this device. Drag left or right to look around.';
    else msg = `Scanning… point the camera around you · GPS ±${Math.round(S.pos.acc)} m`;
    if (!lock && S.guide && S.pos && AR.heading != null) msg = `Target: ${S.guide.name} · ${fmtDist(distance(S.pos, S.guide))} · ${DIRS_SHORT[Math.round(bearing(S.pos, S.guide) / 45) % 8]}`;
    $('arStop').hidden = !S.guide;
    drawMini();
    const set = (id, text) => { if ($(id).textContent !== text) $(id).textContent = text; };
    set('arMsg', msg);
    set('arHeading', AR.heading == null ? 'HDG ---' : `${DIRS_SHORT[Math.round(AR.heading / 45) % 8]} ${String(Math.round(AR.heading) % 360).padStart(3, '0')}°`);
    set('arCount', `TRK ${shown}/${S.buildings.length}`);
    $('arMsg').classList.toggle('locked', !!lock);
    $('arReticle').classList.toggle('locked', !!lock);
    $('arLeft').hidden = side !== -1; $('arRight').hidden = side !== 1;
  }

  // ---------- saving + offline queue ----------
  async function enqueue(op) {
    op.qid = await queueAdd(op);
    S.queue.push(op);
    rebuild();
    flush().then(pull);
  }
  async function save(table, existing, row, blob) {
    if (existing) await enqueue({ op: 'update', table, id: existing.id, patch: row, blob });
    else await enqueue({ op: 'insert', table, row: { ...row, id: crypto.randomUUID(), created_by: uid() }, blob });
    closeSheet();
    toast(navigator.onLine ? 'Saved' : 'Saved on this device. It will upload when you are back online.');
  }
  async function remove(table, row, roomCount) {
    const extra = roomCount ? ` and its ${roomCount} room${roomCount === 1 ? '' : 's'}` : '';
    if (!confirm(`Delete "${row.name}"${extra}? This cannot be undone.`)) return;
    await enqueue({ op: 'delete', table, id: row.id, photo_path: row.photo_path });
    closeSheet(); toast('Deleted');
  }

  let flushing = false;
  async function flush() {
    if (flushing || !navigator.onLine || !S.queue.length) return;
    if (!uid()) return; // changes stay queued until the user signs in again
    flushing = true;
    let failed = 0;
    try {
      for (const op of [...S.queue]) {
        try {
          let photo = {};
          if (op.blob) {
            const path = `${uid()}/${op.id || op.row.id}-${op.qid}.jpg`;
            const up = await sb.storage.from('photos').upload(path, op.blob, { contentType: 'image/jpeg', cacheControl: '31536000' });
            if (up.error && !/exists|duplicate/i.test(up.error.message)) throw up.error;
            photo = { photo_path: path };
          }
          let res;
          if (op.op === 'insert') res = await sb.from(op.table).insert({ ...op.row, ...photo });
          else if (op.op === 'update') res = await sb.from(op.table).update({ ...op.patch, ...photo }).eq('id', op.id);
          else {
            res = await sb.from(op.table).delete().eq('id', op.id);
            if (!res.error && op.photo_path) sb.storage.from('photos').remove([op.photo_path]).catch(() => {});
          }
          if (res.error && res.error.code !== '23505') throw res.error; // 23505 = already uploaded on an earlier try
        } catch (err) {
          if (isNetworkError(err)) break; // still offline: keep everything and try later
          failed++;
          console.warn('sync rejected', op, err);
        }
        await queueDel(op.qid);
        S.queue = S.queue.filter((x) => x.qid !== op.qid);
      }
    } finally { flushing = false; }
    if (failed) toast(`${failed} change${failed === 1 ? ' was' : 's were'} rejected by the server (no permission or invalid).`);
  }

  /** Download the latest copy from Supabase and keep it for offline use. */
  async function pull() {
    if (!navigator.onLine) { rebuild(); return; }
    const [b, r] = await Promise.all([sb.from('buildings').select('*'), sb.from('rooms').select('*')]);
    if (b.error || r.error) { rebuild(); if (!isNetworkError(b.error || r.error)) toast('Could not load the latest data.'); return; }
    S.snapshot = { buildings: b.data, rooms: r.data };
    S.lastSync = Date.now();
    kvSet('snapshot', S.snapshot); kvSet('lastSync', S.lastSync);
    rebuild();
  }

  // ---------- account ----------
  async function loadProfile() {
    if (!uid()) { S.profile = null; kvSet('profile', null); return; }
    const cached = await kvGet('profile');
    if (cached && cached.id === uid()) S.profile = cached;
    if (!navigator.onLine) return;
    const { data } = await sb.from('profiles').select('*').eq('id', uid()).maybeSingle();
    if (data) { S.profile = data; kvSet('profile', data); }
  }

  function openAccount() {
    const syncBox = h('div', { class: 'note' },
      h('b', {}, navigator.onLine ? 'Online' : 'Offline'), ' · ',
      S.queue.length ? `${S.queue.length} change${S.queue.length === 1 ? '' : 's'} waiting to upload` : 'everything is synced',
      S.lastSync ? ` · last updated ${new Date(S.lastSync).toLocaleString()}` : '',
      h('div', { class: 'row', style: 'margin-top:8px' },
        h('button', { class: 'btn small ghost', type: 'button', disabled: !navigator.onLine, onclick: async () => { await flush(); await pull(); toast('Up to date'); openAccount(); } }, 'Sync now'),
        S.installEvt && h('button', { class: 'btn small', type: 'button', onclick: async () => { S.installEvt.prompt(); S.installEvt = null; } }, 'Install app')));
    const installTip = !S.installEvt && !matchMedia('(display-mode: standalone)').matches &&
      h('p', { class: 'note' }, 'To install on your phone: open the browser menu and choose ', h('b', {}, 'Add to Home screen'), ' (on iPhone: Share, then Add to Home Screen).');

    if (uid()) {
      const p = S.profile || {};
      const users = h('div', { style: 'display:grid;gap:8px' });
      if (isAdmin() && navigator.onLine) {
        sb.from('profiles').select('*').order('created_at').then(({ data }) => {
          if (!data) return;
          users.replaceChildren(h('p', { class: 'sub', style: 'margin-bottom:0' }, 'People'), ...data.map((u) => h('div', { class: 'user' },
            h('div', {}, u.full_name || 'No name', h('small', {}, u.email)),
            h('select', {
              'aria-label': 'Role for ' + (u.full_name || u.email), disabled: u.id === uid(),
              onchange: async (ev) => {
                const { error } = await sb.from('profiles').update({ role: ev.target.value }).eq('id', u.id);
                toast(error ? 'Could not change the role.' : 'Role updated');
              }
            }, ['student', 'admin'].map((r) => h('option', { value: r, selected: u.role === r }, r))))));
        });
      }
      openSheet(head('Account', S.session.user.email),
        h('div', { class: 'user' }, h('div', {}, p.full_name || 'Signed in', h('small', {}, isAdmin() ? 'Can edit and delete everything' : 'Can add places and edit your own')),
          h('span', { class: 'tag role' }, p.role || 'student')),
        syncBox, installTip, users,
        !isAdmin() && h('button', { class: 'btn ghost', type: 'button', onclick: async () => {
          const code = (prompt('Enter the admin code') || '').trim().toUpperCase(); if (!code) return;
          const r = await sb.rpc('claim_admin', { code }); await loadProfile(); renderAll();
          toast(r.data === true ? 'You are now an admin.' : 'That admin code is not correct.'); openAccount();
        } }, 'I have an admin code'),
        h('button', { class: 'btn ghost', type: 'button', onclick: async () => {
          if (S.queue.length && !confirm('You have changes that are not uploaded yet. Sign out anyway? They stay on this device until you sign in again.')) return;
          await sb.auth.signOut({ scope: 'local' }); closeSheet(); toast('Signed out');
        } }, 'Sign out'));
      return;
    }

    // not signed in: choose who you are
    const roleCard = (title, text, onclick, cls) => h('button', { class: 'role ' + (cls || ''), type: 'button', onclick },
      h('strong', {}, title), h('span', {}, text));
    openSheet(head('Welcome', 'Who is using the app?'),
      roleCard('Student', 'Log in or register to add places and photos.', () => openAuth('student')),
      roleCard('Admin', 'Log in or register with the admin code to manage everything.', () => openAuth('admin')),
      roleCard('Guest', 'No account. Look around, search and get directions.', () => { rememberGuest(); closeSheet(); }, 'ghost'),
      syncBox, installTip);
  }
  function rememberGuest() { try { localStorage.setItem('liceo-guest', '1'); } catch (e) { /* private mode */ } }

  /** Log in / register form for one role ('student' or 'admin'). */
  function openAuth(role, startMode) {
    let mode = startMode || 'in';
    const admin = role === 'admin';
    const err = h('p', { class: 'err', hidden: true });
    const fail = (msg) => { err.textContent = msg; err.hidden = false; };
    const nameField = h('label', { class: 'f', hidden: true }, 'Full name', h('input', { name: 'full_name', maxLength: 80, autocomplete: 'name' }));
    const codeHint = h('span', {}, 'Admin code (first time only)');
    const codeInput = h('input', { name: 'code', autocomplete: 'off', autocapitalize: 'characters', placeholder: 'LICEO-XXXX-XXXX-XXXX' });
    const codeField = admin && h('label', { class: 'f' }, codeHint, codeInput);
    const submit = h('button', { class: 'btn', type: 'submit' });
    const tabs = ['in', 'up'].map((m) => h('button', { type: 'button', onclick: () => setMode(m) }, m === 'in' ? 'Log in' : 'Register'));
    function setMode(m) {
      mode = m; err.hidden = true;
      tabs.forEach((t, i) => t.classList.toggle('on', (i === 0) === (m === 'in')));
      nameField.hidden = m === 'in';
      submit.textContent = `${m === 'in' ? 'Log in' : 'Register'} as ${role}`;
      if (admin) { codeHint.textContent = m === 'in' ? 'Admin code (first time only)' : 'Admin code'; codeInput.required = m === 'up'; }
    }
    const form = h('form', { onsubmit: async (ev) => {
      ev.preventDefault();
      const f = Object.fromEntries(new FormData(form));
      const code = (f.code || '').trim().toUpperCase();
      if (!navigator.onLine) return fail('You need a connection to log in or register.');
      if (mode === 'up' && !(f.full_name || '').trim()) return fail('Enter your full name.');
      submit.disabled = true; err.hidden = true;
      try {
        const res = mode === 'in'
          ? await sb.auth.signInWithPassword({ email: f.email, password: f.password })
          : await sb.auth.signUp({ email: f.email, password: f.password, options: { data: { full_name: f.full_name.trim() }, emailRedirectTo: location.origin + location.pathname } });
        if (res.error) return fail(res.error.message);
        if (!res.data.session) { // email confirmation is switched on in Supabase
          closeSheet();
          toast(admin ? 'Check your email to confirm, then log in as Admin with your admin code.' : 'Check your email to confirm your account, then log in.');
          return;
        }
        S.session = res.data.session;
        let claimed = false;
        if (admin && code) { const r = await sb.rpc('claim_admin', { code }); claimed = r.data === true; }
        await loadProfile(); renderAll();
        if (admin && !isAdmin()) {
          setMode('in'); // the account exists now, so the next try is a log in
          return fail(code && !claimed
            ? 'That admin code is not correct. You are logged in as a student for now; try the code again.'
            : 'This account is not an admin yet. Enter the admin code to make it one.');
        }
        closeSheet(); toast(isAdmin() ? 'Welcome, admin!' : 'Welcome!');
        flush().then(pull);
      } finally { submit.disabled = false; }
    } },
      h('div', { class: 'seg' }, tabs), nameField,
      h('label', { class: 'f' }, 'Email', h('input', { name: 'email', type: 'email', required: true, autocomplete: 'email' })),
      h('label', { class: 'f' }, 'Password', h('input', { name: 'password', type: 'password', required: true, minLength: 6, autocomplete: 'current-password' })),
      codeField, err, submit);
    setMode(mode);
    openSheet(head(admin ? 'Admin' : 'Student', admin ? 'Admins can edit or delete anything and manage people' : 'Students can add places and edit their own'),
      form,
      h('button', { class: 'btn ghost', type: 'button', onclick: openAccount }, '‹ Choose a different role'));
  }

  // ---------- navigation + events ----------
  function setView(v) {
    document.body.dataset.view = v;
    document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('on', b.dataset.tab === v));
    if (v === 'ar') startAR(); else stopAR();
    if (v === 'map') setTimeout(() => { map.invalidateSize(); if (S.guide) renderGuide(true); }, 50);
  }
  document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => {
    const t = b.dataset.tab;
    if (t === 'add') openForm('building'); else if (t === 'account') openAccount(); else setView(t);
  }));
  document.querySelectorAll('.chip').forEach((c) => c.addEventListener('click', () => {
    S.filter = c.dataset.filter;
    document.querySelectorAll('.chip').forEach((x) => x.classList.toggle('on', x === c));
    renderList();
  }));
  $('q').addEventListener('input', (e) => { S.q = e.target.value; renderList(); });
  $('locateBtn').onclick = () => startGps(true);
  $('campusBtn').onclick = () => map.setView([CFG.CAMPUS.lat, CFG.CAMPUS.lng], CFG.CAMPUS.zoom);
  $('guideStop').onclick = () => { S.guide = null; renderGuide(); };
  $('syncPill').onclick = openAccount;
  window.addEventListener('online', () => { renderStatus(); toast('Back online. Syncing…'); flush().then(pull); });
  window.addEventListener('offline', () => { renderStatus(); toast('You are offline. The app keeps working.'); });
  window.addEventListener('resize', () => map.invalidateSize());
  window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); S.installEvt = e; });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && navigator.onLine) flush().then(pull); });

  sb.auth.onAuthStateChange((_evt, session) => {
    const changed = (session && session.user.id) !== uid();
    S.session = session;
    if (changed) setTimeout(async () => { await loadProfile(); renderAll(); flush().then(pull); }, 0);
  });

  // ---------- start ----------
  (async () => {
    S.snapshot = (await kvGet('snapshot')) || S.snapshot;
    S.lastSync = (await kvGet('lastSync')) || null;
    S.queue = await queueAll();
    rebuild(); // show the saved copy immediately, even with no signal
    const { data } = await sb.auth.getSession();
    S.session = data.session;
    await loadProfile();
    renderAll();
    let seenGuest = false; try { seenGuest = !!localStorage.getItem('liceo-guest'); } catch (e) { /* private mode */ }
    if (!uid() && !seenGuest) openAccount(); // first visit: ask student, admin or guest
    await flush();
    await pull();
    warmTiles();
    if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
  })();
})();
