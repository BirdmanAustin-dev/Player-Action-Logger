const $ = id => document.getElementById(id);
let token = localStorage.getItem('playerlogger.token') || '';
let activityData = null;
let combatData = [];
let playersData = [];
let combinedAlerts = [];
let alertSnapshot = {counts: {open: 0, closed: 0, high: 0, medium: 0, low: 0}, alerts: []};
let moderationState = {enabled: false, tokenRequired: true, temporaryBans: []};
let activeFeedFilter = 'All';
let activeAlertFilter = 'OPEN';
let activeHistoryPlayer = '';
let activeHistoryDate = '';
let historyIndexData = null;

const pageMeta = {
  overview: ['Server overview', 'A low-noise view of recent player behavior with direct links to raw evidence.'],
  feed: ['Activity feed', 'Recent player activity and linked evidence.'],
  players: ['Player profiles', 'Current activity, rolling statistics, notable events, active areas, and raw logs.'],
  history: ['Player history', 'Daily moderation intelligence generated from retained username logs.'],
  combat: ['Combat Center', 'PvP incident summaries with review signals and raw hit timelines.'],
  alerts: ['Alerts and review signals', 'Cautious evidence prompts for staff or LLM review.'],
  insights: ['Server insights', 'Rolling statistics and a lightweight active-area heatmap substitute.'],
  search: ['Search evidence', 'Search player, server, and combat text logs.'],
  about: ['About this build', 'How the lightweight evidence and dashboard model works.']
};

const categoryTone = {
  'Building': 'blue', 'Block removal': 'blue', 'Redstone engineering': 'purple', 'Mining': 'slate',
  'Woodcutting': 'green', 'Excavating / landscaping': 'amber', 'Farming': 'green', 'Animal care': 'green',
  'Fishing': 'cyan', 'Items / storage': 'cyan', 'Item movement': 'cyan', 'Crafting / utility': 'amber',
  'PvP combat': 'red', 'PvE combat': 'orange', 'Travel / exploration': 'indigo',
  'Environmental work': 'amber', 'Chatting': 'blue', 'Commands': 'purple', 'Death': 'red', 'Login': 'green', 'Logout': 'slate'
};

document.querySelectorAll('.nav-item').forEach(btn => btn.addEventListener('click', () => switchTab(btn.dataset.tab)));
document.querySelectorAll('[data-open-tab]').forEach(btn => btn.addEventListener('click', () => switchTab(btn.dataset.openTab)));
document.querySelectorAll('[data-close]').forEach(btn => btn.addEventListener('click', () => $(btn.dataset.close).close()));
$('serverLogsBtn').addEventListener('click', () => loadLogs('_server'));
$('tokenBtn').addEventListener('click', setToken);
$('refreshBtn').addEventListener('click', refreshAll);
$('searchBtn').addEventListener('click', performSearch);
$('playerFilter').addEventListener('input', renderPlayers);
$('historyPlayerSelect').addEventListener('change', e => loadHistoryIndex(e.target.value));
$('moderationForm').addEventListener('submit', submitModerationAction);
$('moderationAction').addEventListener('change', updateModerationDurationVisibility);
['term1', 'term2', 'term3'].forEach(id => $(id).addEventListener('keydown', e => { if (e.key === 'Enter') performSearch(); }));

function setToken() {
  const next = prompt('Enter the PlayerActionLogger dashboard token. Leave blank to clear it.', token);
  if (next === null) return;
  token = next.trim();
  if (token) localStorage.setItem('playerlogger.token', token);
  else localStorage.removeItem('playerlogger.token');
  refreshAll();
}

function apiFetch(url) {
  const headers = token ? {'X-PlayerLogger-Token': token} : {};
  return fetch(url, {headers}).then(async response => {
    if (response.status === 401) throw new Error('Unauthorized. Enter the dashboard token from config.yml.');
    if (!response.ok) throw new Error(await response.text() || 'Request failed');
    return response.json();
  });
}

function apiPost(url, values) {
  const headers = {'Content-Type': 'application/x-www-form-urlencoded'};
  if (token) headers['X-PlayerLogger-Token'] = token;
  return fetch(url, {method: 'POST', headers, body: new URLSearchParams(values)}).then(async response => {
    if (response.status === 401 || response.status === 403) throw new Error(await response.text() || 'Dashboard token required.');
    if (!response.ok) throw new Error(await response.text() || 'Request failed');
    return response.json();
  });
}

function switchTab(name) {
  document.querySelectorAll('.nav-item').forEach(btn => {
    const active = btn.dataset.tab === name;
    btn.classList.toggle('active', active);
    if (active) btn.setAttribute('aria-current', 'page');
    else btn.removeAttribute('aria-current');
  });
  document.querySelectorAll('.panel').forEach(panel => panel.classList.toggle('active', panel.id === name));
  const meta = pageMeta[name] || pageMeta.overview;
  $('pageTitle').textContent = meta[0];
  $('pageSubtitle').textContent = meta[1];
  window.scrollTo({top: 0, behavior: 'smooth'});
}

async function refreshAll() {
  setConnection('refreshing');
  $('refreshBtn').disabled = true;
  try {
    const [activity, combat, players, alerts, moderation] = await Promise.all([
      apiFetch('/api/activity'), apiFetch('/api/combat'), apiFetch('/api/players'),
      apiFetch('/api/alerts?includeClosed=true'), apiFetch('/api/moderation')
    ]);
    activityData = activity;
    combatData = combat || [];
    playersData = players || [];
    alertSnapshot = alerts || alertSnapshot;
    combinedAlerts = alertSnapshot.alerts || [];
    moderationState = moderation || moderationState;

    renderOverview();
    renderFeed();
    renderPlayers();
    renderHistoryPlayerOptions();
    renderCombat();
    renderAlerts();
    renderInsights();

    const openAlertCount = Number(alertSnapshot?.counts?.open || 0);
    $('alertNavCount').textContent = openAlertCount;
    $('alertNavCount').classList.toggle('visible', openAlertCount > 0);
    $('lastUpdated').textContent = new Date().toLocaleTimeString();
    setConnection('connected');
  } catch (error) {
    setConnection('error', error.message || 'Connection failed');
    ['activityPlayers', 'activityFeed', 'playerProfiles', 'combatList', 'alertsList', 'activeAreas']
      .forEach(id => showError(id, error));
  } finally {
    $('refreshBtn').disabled = false;
  }
}

function setConnection(state, message) {
  const dot = $('connectionDot');
  dot.className = 'connection-dot ' + state;
  $('connectionState').textContent = message || (state === 'connected' ? 'Connected' : state === 'refreshing' ? 'Refreshing…' : 'Connection error');
}

function renderOverview() {
  const activity = activityData || {};
  const stats = activity.statistics || {};
  const live = combatData.filter(c => c.status === 'LIVE').length;
  const activeNames = (activity.players || []).filter(p => p.online && !['Idle / AFK', 'Online'].includes(p.currentActivity));
  const headline = live > 0
    ? `${live} live PvP incident${live === 1 ? '' : 's'} and ${activeNames.length} active player${activeNames.length === 1 ? '' : 's'}`
    : activeNames.length > 0
      ? `${activeNames.length} player${activeNames.length === 1 ? '' : 's'} currently showing meaningful activity`
      : 'No significant live activity is currently classified';
  $('heroHeadline').textContent = headline;
  $('heroPulse').classList.toggle('danger', live > 0);
  $('heroPulse').querySelector('b').textContent = live > 0 ? 'Live combat' : 'Monitoring';

  $('summaryCards').innerHTML = [
    statCard('Online', stats.onlinePlayers || 0, 'Players represented as online', 'blue', '●'),
    statCard('Active', stats.activePlayers || 0, 'Meaningful activity in the rolling window', 'green', '↗'),
    statCard('Live PvP', live, live ? 'Open incident evidence available' : 'No active incident', live ? 'red' : 'slate', '⚔'),
    statCard('Review queue', Number(alertSnapshot?.counts?.open || 0), 'Open moderation alerts', Number(alertSnapshot?.counts?.open || 0) ? 'amber' : 'slate', '!')
  ].join('');

  const players = activity.players || [];
  $('activityPlayers').innerHTML = players.length
    ? players.slice(0, 8).map(activityCard).join('')
    : '<div class="empty-state">No activity has been recorded in the current window.</div>';

  $('overviewFeed').innerHTML = feedRows((activity.feed || []).slice(0, 6), true);
  $('overviewAlerts').innerHTML = alertRows(combinedAlerts.filter(a => a.status === 'OPEN').slice(0, 6), true);
  $('overviewCombat').innerHTML = combatData.length
    ? combatData.slice(0, 4).map(c => combatRow(c, true)).join('')
    : '<div class="empty-state compact">No PvP incidents recorded.</div>';
  renderCompactAreas((activity.activeAreas || []).slice(0, 5));
}

function statCard(label, value, note, tone, icon) {
  return `<article class="stat-card ${tone}"><div class="stat-icon">${icon}</div><div><span>${escapeHtml(label)}</span><strong>${Number(value || 0).toLocaleString()}</strong><small>${escapeHtml(note)}</small></div></article>`;
}

function activityCard(player) {
  const state = player.online ? '<span class="presence online">ONLINE</span>' : '<span class="presence offline">OFFLINE</span>';
  const detail = (player.details || []).slice(0, 3).map(d => `<li>${escapeHtml(d)}</li>`).join('');
  const breakdown = Object.entries(player.activityBreakdown || {})
    .filter(([, value]) => Number(value) > 0)
    .sort((a, b) => Number(b[1]) - Number(a[1]))
    .slice(0, 4)
    .map(([label, value]) => `<span><b>${Number(value).toLocaleString()}</b>${escapeHtml(label)}</span>`).join('');
  return `<article class="activity-card">
    <div class="activity-card-top"><div class="player-heading"><div class="avatar small">${initials(player.name)}</div><div><h4>${escapeHtml(player.name)}</h4>${state}</div></div><span class="activity-label">${escapeHtml(player.currentActivity)}</span></div>
    <p class="activity-headline">${escapeHtml(player.headline)}</p>
    <ul>${detail}</ul>
    <div class="mini-stats"><span><b>${player.blocksPlaced}</b> placed</span><span><b>${player.blocksBroken}</b> broken</span><span><b>${player.pvpEvents}</b> PvP</span></div>
    ${breakdown ? `<div class="activity-breakdown">${breakdown}</div>` : ''}
    <div class="card-actions"><button onclick="openProfile('${escapeJs(player.name)}')">Profile</button><button class="subtle" onclick="openHistory('${escapeJs(player.name)}')">History</button><button class="subtle" onclick="openModeration('${escapeJs(player.name)}')">Actions</button><button class="subtle" onclick="loadLogs('${escapeJs(player.name)}')">Raw log</button></div>
  </article>`;
}

function renderFeed() {
  const feed = activityData?.feed || [];
  const categories = ['All', ...new Set(feed.map(item => item.category))];
  $('feedFilters').innerHTML = categories.map(category => `<button class="filter-chip ${activeFeedFilter === category ? 'active' : ''}" onclick="setFeedFilter('${escapeJs(category)}')">${escapeHtml(category)}</button>`).join('');
  const filtered = activeFeedFilter === 'All' ? feed : feed.filter(item => item.category === activeFeedFilter);
  $('activityFeed').innerHTML = feedRows(filtered, false);
}

function setFeedFilter(category) {
  activeFeedFilter = category;
  renderFeed();
}

function feedRows(items, compact) {
  if (!items.length) return '<div class="empty-state compact">No grouped activity is available yet.</div>';
  return items.map(item => {
    const tone = categoryTone[item.category] || 'slate';
    return `<button class="feed-row ${compact ? 'compact' : ''}" onclick="openEvidence('${escapeJs(item.evidenceType)}','${escapeJs(item.evidenceId)}')">
      <span class="feed-marker ${tone}"></span>
      <span class="feed-time">${escapeHtml(relativeTime(item.time))}<small>${escapeHtml(item.timeLabel)}</small></span>
      <span class="feed-content"><strong>${escapeHtml(item.title)}</strong><small>${escapeHtml(item.detail)}</small></span>
      <span class="feed-category ${tone}">${escapeHtml(item.category)}</span><span class="chevron">›</span>
    </button>`;
  }).join('');
}

function renderPlayers() {
  const filter = ($('playerFilter').value || '').trim().toLowerCase();
  const activityByName = new Map((activityData?.players || []).map(p => [p.name.toLowerCase(), p]));
  const names = new Set([...playersData.map(p => p.name), ...(activityData?.players || []).map(p => p.name)]);
  const rows = [...names].filter(name => name.toLowerCase().includes(filter)).sort((a, b) => a.localeCompare(b));

  $('playerProfiles').innerHTML = rows.length ? rows.map(name => {
    const activity = activityByName.get(name.toLowerCase());
    const file = playersData.find(p => p.name.toLowerCase() === name.toLowerCase());
    const current = activity?.currentActivity || 'No current activity';
    const online = activity?.online;
    return `<article class="profile-card">
      <div class="profile-card-head"><div class="avatar">${initials(name)}</div><div><h4>${escapeHtml(name)}</h4><span class="presence ${online ? 'online' : 'offline'}">${online ? 'ONLINE' : 'OFFLINE'}</span></div></div>
      <span class="activity-label">${escapeHtml(current)}</span>
      <p>${escapeHtml(activity?.headline || `${file?.lines || 0} raw log lines available`)}</p>
      <div class="profile-quick-stats"><span><b>${activity?.blocksPlaced || 0}</b> placed</span><span><b>${activity?.blocksBroken || 0}</b> broken</span><span><b>${activity?.pvpEvents || 0}</b> PvP</span></div>
      <div class="card-actions">${activity ? `<button onclick="openProfile('${escapeJs(name)}')">Profile</button>` : ''}<button class="subtle" onclick="openHistory('${escapeJs(name)}')">History</button><button class="subtle" onclick="openModeration('${escapeJs(name)}')">Actions</button><button class="subtle" onclick="loadLogs('${escapeJs(name)}')">Raw log</button></div>
    </article>`;
  }).join('') : '<div class="empty-state">No players match that filter.</div>';

  $('playerLogs').innerHTML = playersData.length ? playersData.map(player => `<button class="player-log-card" onclick="loadLogs('${escapeJs(player.name)}')">
    <div class="avatar small">${initials(player.name)}</div><div><h4>${escapeHtml(player.name)}</h4><p>${Number(player.lines).toLocaleString()} lines • ${escapeHtml(player.size)}</p><small>${player.uuid ? escapeHtml(player.uuid) : 'UUID not indexed yet'}</small></div><span>Open ›</span>
  </button>`).join('') : '<div class="empty-state">No username log files exist yet.</div>';
}

async function openProfile(name) {
  $('profileModalTitle').textContent = name;
  $('profileModalBody').innerHTML = '<div class="empty-state">Loading profile…</div>';
  $('profileModal').showModal();
  try {
    const profile = await apiFetch('/api/profile?player=' + encodeURIComponent(name));
    const s = profile.summary;
    const stats = profile.statistics || {};
    const breakdown = Object.entries(s.activityBreakdown || {}).filter(([, v]) => Number(v) > 0)
      .sort((a, b) => Number(b[1]) - Number(a[1]));
    const max = Math.max(...breakdown.map(([, v]) => Number(v)), 1);
    const combat = combatData.filter(c => (c.participants || []).some(p => p.toLowerCase() === name.toLowerCase())).slice(0, 8);
    $('profileModalBody').innerHTML = `
      <section class="profile-hero"><div class="avatar xl">${initials(name)}</div><div><span class="presence ${s.online ? 'online' : 'offline'}">${s.online ? 'ONLINE' : 'OFFLINE'}</span><h3>${escapeHtml(s.currentActivity)}</h3><p>${escapeHtml(s.headline)}</p></div><div class="profile-hero-actions"><button onclick="openModeration('${escapeJs(name)}')">Moderation actions</button><button onclick="loadLogs('${escapeJs(name)}')">Open raw log</button></div></section>
      <div class="profile-stat-grid">${profileStat('Events', stats.totalEvents)}${profileStat('Placed', stats.blocksPlaced)}${profileStat('Broken', stats.blocksBroken)}${profileStat('PvP', stats.pvpEvents)}${profileStat('PvE', stats.pveEvents)}${profileStat('Areas', stats.activeAreas)}</div>
      <div class="profile-layout">
        <section class="surface"><div class="surface-heading"><div><span class="eyebrow">ACTIVITY MIX</span><h3>Last ${activityData?.windowMinutes || 60} minutes</h3></div></div><div class="bar-chart">${breakdown.map(([label, value]) => barRow(label, value, max)).join('') || '<div class="empty-state compact">No classified activity.</div>'}</div></section>
        <section class="surface"><div class="surface-heading"><div><span class="eyebrow">DETAILS</span><h3>Current summary</h3></div></div><ul class="detail-list">${(s.details || []).map(d => `<li>${escapeHtml(d)}</li>`).join('') || '<li>No additional details.</li>'}</ul></section>
      </div>
      <section class="surface modal-section"><div class="surface-heading"><div><span class="eyebrow">RECENT ACTIVITY</span><h3>Grouped evidence timeline</h3></div></div><div class="feed-list">${feedRows(profile.recentActivity || [], true)}</div></section>
      <section class="surface modal-section"><div class="surface-heading"><div><span class="eyebrow">TOP AREAS</span><h3>Frequently active chunks</h3></div></div><div class="compact-area-list">${compactAreaRows(profile.topAreas || [])}</div></section>
      <section class="surface modal-section"><div class="surface-heading"><div><span class="eyebrow">COMBAT HISTORY</span><h3>Recent linked incidents</h3></div></div>${combat.length ? combat.map(c => combatRow(c, true)).join('') : '<div class="empty-state compact">No linked PvP incidents.</div>'}</section>
      <section class="surface modal-section"><div class="surface-heading"><div><span class="eyebrow">PROFILE ALERTS</span><h3>Review signals</h3></div></div>${alertRows(combinedAlerts.filter(a => a.status === 'OPEN' && String(a.player || '').toLowerCase().includes(name.toLowerCase())), true)}</section>`;
  } catch (error) {
    $('profileModalBody').innerHTML = `<div class="empty-state">${escapeHtml(error.message)}</div>`;
  }
}

function profileStat(label, value) { return `<div><span>${escapeHtml(label)}</span><strong>${Number(value || 0).toLocaleString()}</strong></div>`; }

function renderHistoryPlayerOptions() {
  const select = $('historyPlayerSelect');
  const current = activeHistoryPlayer || select.value;
  const names = [...new Set(playersData.map(p => p.name))].sort((a, b) => a.localeCompare(b));
  select.innerHTML = '<option value="">Choose a player…</option>' + names
    .map(name => `<option value="${escapeHtml(name)}" ${name === current ? 'selected' : ''}>${escapeHtml(name)}</option>`).join('');
}

function openHistory(player) {
  activeHistoryPlayer = player;
  switchTab('history');
  renderHistoryPlayerOptions();
  $('historyPlayerSelect').value = player;
  loadHistoryIndex(player);
}

async function loadHistoryIndex(player) {
  activeHistoryPlayer = player || '';
  activeHistoryDate = '';
  historyIndexData = null;
  if (!player) {
    $('historyDays').innerHTML = '<div class="empty-state compact">Choose a player to load history.</div>';
    $('historyDayDetail').innerHTML = '<div class="empty-state">Select a day to see what the player was doing.</div>';
    return;
  }
  $('historyDays').innerHTML = '<div class="empty-state compact">Reading player log…</div>';
  $('historyDayDetail').innerHTML = '<div class="empty-state">Loading daily summaries…</div>';
  try {
    historyIndexData = await apiFetch('/api/history?player=' + encodeURIComponent(player));
    const days = historyIndexData.days || [];
    $('historyDays').innerHTML = days.length ? days.map(historyDayButton).join('')
      : '<div class="empty-state compact">No timestamped history is available.</div>';
    if (days.length) loadHistoryDay(player, days[0].date);
    else $('historyDayDetail').innerHTML = '<div class="empty-state">No activity days were found in this player log.</div>';
  } catch (error) {
    $('historyDays').innerHTML = `<div class="empty-state compact">${escapeHtml(error.message)}</div>`;
    $('historyDayDetail').innerHTML = '<div class="empty-state">History could not be loaded.</div>';
  }
}

function historyDayButton(day) {
  const categories = Object.entries(day.activities || {}).slice(0, 3)
    .map(([label, count]) => `${escapeHtml(label)} ${Number(count).toLocaleString()}`).join(' • ');
  return `<button class="history-day ${activeHistoryDate === day.date ? 'active' : ''}" onclick="loadHistoryDay('${escapeJs(activeHistoryPlayer)}','${escapeJs(day.date)}')">
    <span><strong>${escapeHtml(day.date)}</strong><small>${escapeHtml(day.firstSeen)}–${escapeHtml(day.lastSeen)}</small></span>
    <span><b>${Number(day.totalEvents).toLocaleString()}</b><small>${escapeHtml(day.dominantActivity)}</small></span>
    <em>${categories}</em>
  </button>`;
}

async function loadHistoryDay(player, date) {
  if (!player || !date) return;
  activeHistoryDate = date;
  if (historyIndexData) $('historyDays').innerHTML = (historyIndexData.days || []).map(historyDayButton).join('');
  $('historyDayDetail').innerHTML = '<div class="empty-state">Analyzing selected day…</div>';
  try {
    const data = await apiFetch('/api/history?player=' + encodeURIComponent(player) + '&date=' + encodeURIComponent(date));
    renderHistoryDay(data);
  } catch (error) {
    $('historyDayDetail').innerHTML = `<div class="empty-state">${escapeHtml(error.message)}</div>`;
  }
}

function renderHistoryDay(data) {
  const summary = data.summary || {};
  const activities = Object.entries(summary.activities || {}).sort((a, b) => Number(b[1]) - Number(a[1]));
  const max = Math.max(...activities.map(([, value]) => Number(value)), 1);
  const groups = data.groups || [];
  const highlights = summary.highlights || [];
  const evidence = data.evidence || [];
  $('historyDayDetail').innerHTML = `
    <div class="history-hero"><div><span class="eyebrow">${escapeHtml(data.player)} • ${escapeHtml(summary.date)}</span><h3>${escapeHtml(summary.dominantActivity || 'Activity summary')}</h3><p>${Number(summary.totalEvents || 0).toLocaleString()} events from ${escapeHtml(summary.firstSeen || '')} to ${escapeHtml(summary.lastSeen || '')}</p></div><div class="history-actions"><button class="danger-button" onclick="deleteHistoryDay('${escapeJs(data.player)}','${escapeJs(summary.date)}')">Delete this day</button><button class="secondary-button" onclick="loadLogs('${escapeJs(data.player)}')">Open full raw log</button></div></div>
    <div class="history-stat-grid">${profileStat('Events', summary.totalEvents)}${profileStat('Categories', activities.length)}${profileStat('First', summary.firstSeen)}${profileStat('Last', summary.lastSeen)}</div>
    <div class="history-detail-grid">
      <section><div class="surface-heading"><div><span class="eyebrow">ACTIVITY MIX</span><h3>What they were doing</h3></div></div><div class="bar-chart">${activities.map(([label, value]) => barRow(label, value, max)).join('') || '<div class="empty-state compact">No classified activity.</div>'}</div></section>
      <section><div class="surface-heading"><div><span class="eyebrow">NOTABLE EVIDENCE</span><h3>Review highlights</h3></div></div><div class="history-highlights">${highlights.length ? highlights.map(item => `<div>${escapeHtml(item)}</div>`).join('') : '<div class="empty-state compact">No notable events were automatically highlighted.</div>'}</div></section>
    </div>
    <div class="surface-heading history-section-heading"><div><span class="eyebrow">DAILY BREAKDOWN</span><h3>Activity groups</h3></div></div>
    <div class="history-groups">${groups.map(group => `<article><div><strong>${escapeHtml(group.category)}</strong><b>${Number(group.count).toLocaleString()}</b></div><small>${escapeHtml(group.firstTime)}–${escapeHtml(group.lastTime)}</small><ul>${(group.examples || []).map(example => `<li>${escapeHtml(example)}</li>`).join('')}</ul></article>`).join('') || '<div class="empty-state">No activity groups.</div>'}</div>
    <div class="surface-heading history-section-heading"><div><span class="eyebrow">RAW DAY EVIDENCE</span><h3>Selected log lines</h3></div></div>
    <div class="raw-log history-evidence">${evidence.map(row => `<div><time>${escapeHtml(row.time)}</time><span>${escapeHtml(row.action)}</span></div>`).join('') || '<div class="empty-state compact">No evidence lines.</div>'}</div>`;
}

function renderCombat() {
  const live = combatData.filter(c => c.status === 'LIVE');
  const loot = combatData.filter(c => c.status === 'LOOT WINDOW');
  const complete = combatData.filter(c => c.status === 'COMPLETE');
  const flagged = combatData.filter(c => (c.flags || []).length > 0);
  $('combatStats').innerHTML = [
    miniStat('Live', live.length, 'red'), miniStat('Loot windows', loot.length, 'amber'),
    miniStat('Completed', complete.length, 'green'), miniStat('Flagged', flagged.length, 'purple')
  ].join('');
  if (!combatData.length) {
    $('combatList').innerHTML = '<div class="empty-state">No PvP incident summaries are available yet.</div>';
    return;
  }
  $('combatList').innerHTML = `
    ${combatGroup('Active incidents', [...live, ...loot])}
    ${combatGroup('Recent completed incidents', complete)}`;
}

function miniStat(label, value, tone) { return `<div class="mini-stat ${tone}"><span>${escapeHtml(label)}</span><strong>${value}</strong></div>`; }
function combatGroup(title, rows) { return `<section class="combat-group"><div class="combat-group-title"><h4>${escapeHtml(title)}</h4><span>${rows.length}</span></div>${rows.length ? rows.map(c => combatRow(c, false)).join('') : '<div class="empty-state compact">None.</div>'}</section>`; }

function combatRow(c, compact) {
  const participants = (c.participants || []).join(' vs ');
  const statusClass = c.status === 'LIVE' ? 'live' : c.status === 'LOOT WINDOW' ? 'loot' : 'complete';
  const health = c.health && Object.keys(c.health).length
    ? Object.entries(c.health).map(([name, hp]) => `<span>${escapeHtml(name)} <b>${escapeHtml(hp)}</b></span>`).join('') : '';
  return `<button class="combat-row ${compact ? 'compact-row' : ''}" onclick="openCombat('${escapeJs(c.id)}')">
    <span class="status-badge ${statusClass}">${escapeHtml(c.status)}</span>
    <span class="combat-main"><strong>${escapeHtml(participants || c.id)}</strong><small>${escapeHtml(c.started)} • ${escapeHtml(c.location)}</small></span>
    <span class="combat-result">${escapeHtml(c.result)}</span><span class="health-strip">${health}</span><span class="chevron">›</span>
  </button>`;
}

async function openCombat(id) {
  if ($('profileModal').open) $('profileModal').close();
  $('combatModalTitle').textContent = id;
  $('combatModalBody').innerHTML = '<div class="empty-state">Loading incident…</div>';
  $('combatModal').showModal();
  try {
    const detail = await apiFetch('/api/combat/log?id=' + encodeURIComponent(id));
    const s = detail.summary;
    const flags = (s.flags || []).length
      ? `<div class="flag-list">${s.flags.map(f => `<div><span>!</span><p>${escapeHtml(f)}</p></div>`).join('')}</div>`
      : '<div class="no-flags">No automated review flags.</div>';
    $('combatModalBody').innerHTML = `
      <div class="incident-summary">${summaryField('Type', s.type)}${summaryField('Started', s.started)}${summaryField('Ended', s.ended || 'In progress')}${summaryField('Location', s.location)}${summaryField('Probable initiator', s.probableInitiator)}${summaryField('Confidence', s.initiatorConfidence || 'Low')}${summaryField('Reason', s.initiatorReason || 'First recorded damaging player')}${summaryField('Participants', (s.participants || []).join(', '))}${summaryField('Result', s.result)}</div>
      <div class="context-note"><strong>Context limitation:</strong> The plugin cannot know voice-chat warnings, verbal permission, or all off-log context.</div>
      <h3 class="modal-section-title">Review signals</h3>${flags}
      <h3 class="modal-section-title">Raw timeline</h3><div class="timeline">${(detail.timeline || []).map(line => `<div>${escapeHtml(line)}</div>`).join('') || '<div class="empty-state compact">No timeline lines.</div>'}</div>`;
  } catch (error) {
    $('combatModalBody').innerHTML = `<div class="empty-state">${escapeHtml(error.message)}</div>`;
  }
}

function summaryField(label, value) { return `<div><span>${escapeHtml(label)}</span><strong>${escapeHtml(value || '')}</strong></div>`; }

function severityRank(s) { return s === 'HIGH' ? 3 : s === 'MEDIUM' ? 2 : 1; }

function renderAlerts() {
  const filters = ['OPEN', 'HIGH', 'MEDIUM', 'LOW', 'CLOSED'];
  $('alertFilters').innerHTML = filters.map(f => `<button class="filter-chip ${activeAlertFilter === f ? 'active' : ''}" onclick="setAlertFilter('${f}')">${f === 'OPEN' ? 'Open alerts' : f === 'CLOSED' ? 'Closed' : f}</button>`).join('');
  const filtered = combinedAlerts.filter(a => {
    if (activeAlertFilter === 'CLOSED') return a.status === 'CLOSED';
    if (activeAlertFilter === 'OPEN') return a.status === 'OPEN';
    return a.status === 'OPEN' && a.severity === activeAlertFilter;
  });
  const counts = alertSnapshot.counts || {};
  $('alertSummaryCards').innerHTML = [
    miniStat('Open', counts.open || 0, 'amber'), miniStat('High', counts.high || 0, 'red'),
    miniStat('Medium', counts.medium || 0, 'purple'), miniStat('Closed', counts.closed || 0, 'green')
  ].join('');
  $('alertsList').innerHTML = alertRows(filtered, false);
  renderTemporaryBans();
}
function setAlertFilter(filter) { activeAlertFilter = filter; renderAlerts(); }
function alertRows(alerts, compact) {
  if (!alerts.length) return '<div class="empty-state compact">No alerts match this view.</div>';
  return alerts.map(a => {
    const open = a.status === 'OPEN';
    const firstPlayer = String(a.player || '').split(',')[0].trim();
    const controls = compact || !open ? '' : `<span class="alert-actions"><button onclick="event.stopPropagation();closeAlert('${escapeJs(a.id)}')">Mark closed</button>${firstPlayer ? `<button class="danger" onclick="event.stopPropagation();openModeration('${escapeJs(firstPlayer)}','tempban','${escapeJs('Under investigation: ' + a.title)}','${escapeJs(a.id)}')">Temporary ban</button>` : ''}</span>`;
    const statusLine = !open ? '<em>Closed</em>' : `<em>${escapeHtml(a.timeLabel || relativeTime(a.createdAt))}</em>`;
    return `<article class="alert-row ${String(a.severity).toLowerCase()} ${compact ? 'compact' : ''} ${open ? '' : 'closed'}" onclick="openEvidence('${escapeJs(a.evidenceType)}','${escapeJs(a.evidenceId)}')">
      <span class="alert-severity">${escapeHtml(a.severity)}</span><span class="alert-copy"><strong>${escapeHtml(a.title)}</strong><small>${escapeHtml(a.detail)}</small>${statusLine}</span><span class="alert-category">${escapeHtml(a.status || a.category)}</span>${controls}<span class="chevron">›</span>
    </article>`;
  }).join('');
}

async function closeAlert(id) {
  try {
    await apiPost('/api/alerts/close', {id});
    await refreshAll();
  } catch (error) { alert(error.message); }
}

function renderTemporaryBans() {
  const bans = moderationState.temporaryBans || [];
  $('temporaryBans').innerHTML = bans.length ? bans.map(ban => `<div class="temporary-ban-row"><div><strong>${escapeHtml(ban.player)}</strong><small>${escapeHtml(ban.reason)} • expires ${escapeHtml(ban.expiresLabel)}</small></div><b data-expiry="${Number(ban.expiresAt)}">${countdown(ban.expiresAt)}</b><button onclick="openModeration('${escapeJs(ban.player)}','pardon','Temporary ban ended early')">Pardon</button></div>`).join('') : '<div class="empty-state compact">No active temporary bans.</div>';
}

function countdown(epoch) {
  const seconds = Math.max(0, Math.floor((Number(epoch) - Date.now()) / 1000));
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return days > 0 ? `${days}d ${hours}h` : hours > 0 ? `${hours}h ${minutes}m` : `${minutes}m`;
}

function openModeration(player, action = 'kick', reason = '', alertId = '') {
  $('moderationPlayer').value = player;
  $('moderationAlertId').value = alertId;
  $('moderationModalTitle').textContent = `Action for ${player}`;
  $('moderationAction').value = action;
  $('moderationReason').value = reason;
  $('moderationModerator').value = localStorage.getItem('playerlogger.moderator') || '';
  $('moderationResult').textContent = '';
  updateModerationDurationVisibility();
  $('moderationModal').showModal();
}

function updateModerationDurationVisibility() {
  $('durationField').style.display = $('moderationAction').value === 'tempban' ? 'grid' : 'none';
}

async function submitModerationAction(event) {
  event.preventDefault();
  const values = {
    action: $('moderationAction').value,
    player: $('moderationPlayer').value,
    reason: $('moderationReason').value,
    moderator: $('moderationModerator').value,
    durationMinutes: $('moderationDuration').value,
    alertId: $('moderationAlertId').value
  };
  localStorage.setItem('playerlogger.moderator', values.moderator.trim());
  $('moderationResult').textContent = 'Applying action…';
  try {
    const result = await apiPost('/api/moderation/action', values);
    $('moderationResult').textContent = result.message || 'Action completed.';
    await refreshAll();
  } catch (error) { $('moderationResult').textContent = error.message; }
}

async function deleteHistoryDay(player, date) {
  const moderator = prompt('Moderator name:', localStorage.getItem('playerlogger.moderator') || '');
  if (moderator === null) return;
  if (!confirm(`Permanently delete all ${player} log lines from ${date}? This cannot be undone.`)) return;
  localStorage.setItem('playerlogger.moderator', moderator.trim());
  try {
    const result = await apiPost('/api/history/delete', {player, date, moderator});
    alert(`Removed ${result.removedLines} log lines from ${date}.`);
    await loadHistoryIndex(player);
    await refreshAll();
  } catch (error) { alert(error.message); }
}

function renderInsights() {
  const s = activityData?.statistics || {};
  $('statisticsCards').innerHTML = [
    statCard('Tracked events', s.totalEvents || 0, 'Grouped in the rolling memory window', 'blue', '≋'),
    statCard('Block changes', (s.blocksPlaced || 0) + (s.blocksBroken || 0), `${s.blocksPlaced || 0} placed • ${s.blocksBroken || 0} broken`, 'purple', '▦'),
    statCard('Combat events', (s.pvpEvents || 0) + (s.pveEvents || 0), `${s.pvpEvents || 0} PvP • ${s.pveEvents || 0} PvE`, 'red', '⚔'),
    statCard('Active areas', s.activeAreas || 0, 'Ranked chunks above the score threshold', 'green', '⌖')
  ].join('');

  const activityTotals = s.activityTotals || activityData?.totals || {};
  renderBarChart('activityChart', activityTotals);
  renderBarChart('eventChart', {
    'Blocks placed': s.blocksPlaced || 0, 'Blocks broken': s.blocksBroken || 0,
    'PvP events': s.pvpEvents || 0, 'PvE events': s.pveEvents || 0,
    'Farming / animals': s.farmingEvents || 0, 'Fishing': s.fishingEvents || 0,
    'Crafting / utility': s.craftingEvents || 0, 'Items / storage': s.inventoryEvents || 0,
    'Environmental': s.environmentalEvents || 0, 'Chat / commands': s.chatCommandEvents || 0,
    'Travel checkpoints': s.chunkTransitions || 0
  });
  renderActiveAreas(activityData?.activeAreas || []);
}

function renderBarChart(id, values) {
  const rows = Object.entries(values).filter(([, v]) => Number(v) > 0).sort((a, b) => Number(b[1]) - Number(a[1]));
  const max = Math.max(...rows.map(([, v]) => Number(v)), 1);
  $(id).innerHTML = rows.length ? rows.map(([label, value]) => barRow(label, value, max)).join('') : '<div class="empty-state compact">No data yet.</div>';
}
function barRow(label, value, max) {
  const width = Math.max(3, Math.round(Number(value) / max * 100));
  return `<div class="bar-row"><div class="bar-label"><span>${escapeHtml(label)}</span><b>${Number(value).toLocaleString()}</b></div><div class="bar-track"><i style="width:${width}%"></i></div></div>`;
}

function renderActiveAreas(areas) {
  $('activeAreas').innerHTML = areas.length ? areas.map((area, index) => areaCard(area, index, areas[0]?.score || 1)).join('') : '<div class="empty-state">No area has enough recent activity to rank.</div>';
}
function areaCard(area, index, maxScore) {
  const width = Math.max(8, Math.round(Number(area.score) / Math.max(Number(maxScore), 1) * 100));
  return `<article class="area-card"><div class="area-rank">${index + 1}</div><div class="area-main"><div class="area-title"><strong>${escapeHtml(area.world)} chunk (${area.chunkX}, ${area.chunkZ})</strong><span>${escapeHtml(area.primaryActivity)}</span></div><div class="area-meter"><i style="width:${width}%"></i></div><div class="area-meta"><span>${area.visits} visits</span><span>${area.workEvents} work</span><span>${area.blockChanges} blocks</span><span>${area.combatEvents} combat</span></div><small>Players: ${escapeHtml((area.players || []).join(', ') || 'Unknown')}</small></div><div class="area-score">${area.score}</div></article>`;
}
function renderCompactAreas(areas) { $('overviewAreas').innerHTML = compactAreaRows(areas); }
function compactAreaRows(areas) {
  if (!areas.length) return '<div class="empty-state compact">No ranked active areas yet.</div>';
  return areas.map((a, index) => `<div class="compact-area-row"><span class="area-rank">${index + 1}</span><div><strong>${escapeHtml(a.world)} (${a.chunkX}, ${a.chunkZ})</strong><small>${escapeHtml(a.primaryActivity)} • ${(a.players || []).join(', ')}</small></div><b>${a.score}</b></div>`).join('');
}

function openEvidence(type, id) {
  if (type === 'combat') openCombat(id);
  else loadLogs(id);
}

async function loadLogs(player) {
  if ($('profileModal').open) $('profileModal').close();
  if ($('combatModal').open) $('combatModal').close();
  $('logModalTitle').textContent = player === '_server' ? 'Server log' : player;
  $('logModalBody').innerHTML = '<div class="empty-state">Loading log…</div>';
  $('logModal').showModal();
  try {
    const rows = await apiFetch('/api/logs?player=' + encodeURIComponent(player));
    $('logModalBody').innerHTML = rows.length
      ? `<div class="raw-log">${rows.map(row => `<div><time>${escapeHtml(row.time)}</time><span>${escapeHtml(row.action)}</span></div>`).join('')}</div>`
      : '<div class="empty-state">No log lines.</div>';
  } catch (error) {
    $('logModalBody').innerHTML = `<div class="empty-state">${escapeHtml(error.message)}</div>`;
  }
}

async function performSearch() {
  const terms = ['term1', 'term2', 'term3'].map(id => $(id).value.trim()).filter(Boolean);
  if (!terms.length) { $('searchResults').innerHTML = '<div class="empty-state">Enter at least one search term.</div>'; return; }
  const params = new URLSearchParams();
  terms.forEach((term, index) => params.set('term' + (index + 1), term));
  $('searchResults').innerHTML = '<div class="empty-state">Searching evidence…</div>';
  try {
    const results = await apiFetch('/api/search?' + params);
    $('searchResults').innerHTML = results.length ? results.map(result => `
      <section class="surface search-result-card"><div class="surface-heading"><div><span class="eyebrow">EVIDENCE FILE</span><h3>${escapeHtml(result.player)}</h3><p>${result.matches.length} matching lines</p></div></div>
      ${result.matches.map(match => `<div class="search-match"><b>Line ${match.line}</b><code>${escapeHtml(match.content)}</code></div>`).join('')}</section>`).join('') : '<div class="empty-state">No matching evidence found.</div>';
  } catch (error) { $('searchResults').innerHTML = `<div class="empty-state">${escapeHtml(error.message)}</div>`; }
}

function relativeTime(epoch) {
  const seconds = Math.max(0, Math.floor((Date.now() - Number(epoch || 0)) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}
function initials(name) { return String(name || '?').slice(0, 2).toUpperCase(); }
function showError(id, error) { const el = $(id); if (el) el.innerHTML = `<div class="empty-state">${escapeHtml(error.message || 'Unable to load data.')}</div>`; }
function escapeHtml(value) { return String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#039;'); }
function escapeJs(value) { return String(value ?? '').replaceAll('\\', '\\\\').replaceAll("'", "\\'"); }

refreshAll();
setInterval(refreshAll, 15000);
setInterval(() => document.querySelectorAll('[data-expiry]').forEach(el => { el.textContent = countdown(el.dataset.expiry); }), 30000);
