const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/web/app.js'), 'utf8');
function setup() {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {open: false, value: '', textContent: '', innerHTML: '',
      scrollTop: 99, close() {this.open = false;}, showModal() {this.open = true;}});
    return elements.get(id);
  };
  const context = vm.createContext({$: element, encodeURIComponent, Date, Number, String});
  // Extract actual named function definitions; do not run dashboard startup/network polling.
  for (const name of ['openEvidence', 'openActivityEvidence', 'loadLogs', 'feedRows', 'relativeTime', 'escapeHtml', 'escapeJs']) {
    const start = source.search(new RegExp('(?:async )?function ' + name + '\\('));
    const rest = source.slice(start);
    const end = rest.search(/\n(?:async )?function /);
    vm.runInContext(end < 0 ? rest.split('\nrefreshAll();')[0] : rest.slice(0, end), context);
  }
  context.categoryTone = {};
  return {context, element};
}
test('overview and activity feed both route to the selected group', () => {
  const {context} = setup();
  const row = {evidenceType: 'activity', evidenceId: 'selected-group', time: Date.now(), category: 'Logout'};
  for (const compact of [true, false]) {
    const html = context.feedRows([row], compact);
    assert.match(html, /openEvidence\('activity','selected-group'\)/);
  }
  let selected;
  context.openActivityEvidence = id => {selected = id;};
  context.loadLogs = () => assert.fail('Activity click must not load the full log');
  context.openEvidence('activity', 'selected-group');
  assert.equal(selected, 'selected-group');
});
test('selected evidence renders exact records safely and resets scroll', async () => {
  const {context, element} = setup();
  element('profileModal').open = true;
  context.apiFetch = async url => {
    assert.equal(url, '/api/activity-evidence?id=selected%20group');
    return {item: {title: 'BirdmanAustin left the server', detail: '1 grouped event'},
      records: [{time: '2026-09-20 21:30:12', action: 'LOGOUT <script>unsafe</script>'}]};
  };
  await context.openActivityEvidence('selected group');
  assert.equal(element('logModalTitle').textContent, 'BirdmanAustin left the server');
  assert.match(element('logModalBody').innerHTML, /21:30:12/);
  assert.match(element('logModalBody').innerHTML, /&lt;script&gt;/);
  assert.equal(element('logModalBody').scrollTop, 0);
  assert.equal(element('profileModal').open, false);
  assert.equal(element('logModal').open, true);
});
test('expired evidence shows its error and does not fall back to an entire log', async () => {
  const {context, element} = setup();
  context.apiFetch = async () => {throw new Error('Activity expired');};
  context.loadLogs = () => assert.fail('No fallback to full player log');
  await context.openActivityEvidence('old-group');
  assert.match(element('logModalBody').innerHTML, /Activity expired/);
});
test('raw-log and combat navigation remain available', () => {
  const {context} = setup();
  let raw, combat;
  context.loadLogs = id => {raw = id;};
  context.openCombat = id => {combat = id;};
  context.openEvidence('player', 'BirdmanAustin');
  context.openEvidence('combat', 'fight-123');
  assert.equal(raw, 'BirdmanAustin');
  assert.equal(combat, 'fight-123');
});
