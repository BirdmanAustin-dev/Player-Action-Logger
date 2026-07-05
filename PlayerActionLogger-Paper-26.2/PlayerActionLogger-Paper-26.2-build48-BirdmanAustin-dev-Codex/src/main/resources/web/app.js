const $ = (id) => document.getElementById(id);
let token = localStorage.getItem('playerlogger.token') || '';

for (const btn of document.querySelectorAll('.tab')) {
  btn.addEventListener('click', () => switchTab(btn.dataset.tab));
}
$('closeModal').addEventListener('click', () => $('modal').close());
$('serverLogsBtn').addEventListener('click', () => loadLogs('_server'));
$('searchBtn').addEventListener('click', performSearch);
$('tokenBtn').addEventListener('click', setToken);
['term1', 'term2', 'term3'].forEach(id => $(id).addEventListener('keydown', e => { if (e.key === 'Enter') performSearch(); }));

function setToken() {
  const next = prompt('Enter PlayerActionLogger dashboard token. Leave blank to clear.', token);
  if (next === null) return;
  token = next.trim();
  if (token) localStorage.setItem('playerlogger.token', token);
  else localStorage.removeItem('playerlogger.token');
  loadPlayers();
}

function apiFetch(url) {
  const headers = token ? {'X-PlayerLogger-Token': token} : {};
  return fetch(url, {headers}).then(async r => {
    if (r.status === 401) throw new Error('Unauthorized. Click Set Token and enter the token from config.yml.');
    if (!r.ok) throw new Error(await r.text() || 'Request failed');
    return r.json();
  });
}

function switchTab(name) {
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === name));
  document.querySelectorAll('.panel').forEach(p => p.classList.toggle('active', p.id === name));
}

async function loadPlayers() {
  const panel = $('players');
  try {
    const players = await apiFetch('/api/players');
    if (!players.length) { panel.innerHTML = '<div class="loading">No player logs yet.</div>'; return; }
    panel.innerHTML = '<div class="grid">' + players.map(p => `
      <button class="card" onclick="loadLogs('${escapeJs(p.name)}')">
        <h3>${escapeHtml(p.name)}</h3>
        <div>${p.lines} lines • ${escapeHtml(p.size)}</div>
        ${p.uuid ? `<small>UUID: ${escapeHtml(p.uuid)}</small>` : '<small>No UUID indexed yet</small>'}
      </button>`).join('') + '</div>';
  } catch (e) {
    panel.innerHTML = `<div class="loading">${escapeHtml(e.message || 'Error loading players.')}</div>`;
  }
}

async function loadLogs(player) {
  $('modalTitle').textContent = player === '_server' ? 'Server Logs' : `Logs: ${player}`;
  $('modalLogs').innerHTML = '<div class="loading">Loading...</div>';
  $('modal').showModal();
  try {
    const rows = await apiFetch('/api/logs?player=' + encodeURIComponent(player));
    $('modalLogs').innerHTML = rows.map(row => `<div class="log-line"><span class="time">${escapeHtml(row.time)}</span>${escapeHtml(row.action)}</div>`).join('') || '<div class="loading">No log lines.</div>';
  } catch (e) {
    $('modalLogs').innerHTML = `<div class="loading">${escapeHtml(e.message || 'Error loading logs.')}</div>`;
  }
}

async function performSearch() {
  const terms = ['term1', 'term2', 'term3'].map(id => $(id).value.trim()).filter(Boolean);
  if (!terms.length) { $('searchResults').innerHTML = '<div class="loading">Enter at least one term.</div>'; return; }
  const params = new URLSearchParams();
  terms.forEach((term, i) => params.set('term' + (i + 1), term));
  $('searchResults').innerHTML = '<div class="loading">Searching...</div>';
  try {
    const results = await apiFetch('/api/search?' + params);
    if (!results.length) { $('searchResults').innerHTML = '<div class="loading">No results found.</div>'; return; }
    $('searchResults').innerHTML = results.map(result => `
      <div class="card">
        <h3>${escapeHtml(result.player)} (${result.matches.length} matches)</h3>
        ${result.matches.map(m => `<div class="search-match"><span class="line-number">Line ${m.line}</span>${escapeHtml(m.content)}</div>`).join('')}
      </div>`).join('');
  } catch (e) {
    $('searchResults').innerHTML = `<div class="loading">${escapeHtml(e.message || 'Search failed.')}</div>`;
  }
}

function escapeHtml(s) {
  return String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#039;');
}
function escapeJs(s) {
  return String(s).replaceAll('\\', '\\\\').replaceAll("'", "\\'");
}
loadPlayers();
