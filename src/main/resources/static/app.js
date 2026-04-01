(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const state = { auth: null, me: null, tenants: [], tenant: null, section: 'overview', jobs: [], runs: [], dead: [], overview: null };
  const sectionCopy = {
    overview: ['Control room', 'The state of every scheduled trip job, in one place.'],
    jobs: ['Job registry', 'Create, inspect, and trigger your scheduled work.'],
    runs: ['Run history', 'The latest 100 attempts across this tenant.'],
    dead: ['Dead letters', 'Inspect failed runs and replay them when ready.']
  };
  const encode = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
  const short = value => value ? `${String(value).slice(0, 8)}…${String(value).slice(-4)}` : '—';
  const date = value => value ? new Date(value).toLocaleString(undefined, {dateStyle:'medium',timeStyle:'short'}) : '—';
  const fullDate = value => value ? new Date(value).toLocaleString() : '—';
  const canWrite = () => state.me && state.me.role !== 'EMPLOYEE';
  const tenantPath = path => `/api/tenants/${encodeURIComponent(state.tenant.id)}${path}`;
  let toastTimer;

  function notify(message, error = false) {
    const toast = $('toast'); toast.textContent = message; toast.classList.toggle('error', error); toast.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { toast.hidden = true; }, 4200);
  }
  function pageError(message) { const el = $('page-error'); el.textContent = message; el.hidden = !message; }
  async function request(path, options = {}) {
    const response = await fetch(path, {
      ...options,
      cache: 'no-store',
      headers: { 'Authorization': `Basic ${state.auth}`, ...(options.body ? {'Content-Type':'application/json'} : {}), ...(options.headers || {}) }
    });
    if (response.status === 401) throw new Error('Invalid username or password.');
    if (!response.ok) {
      const detail = await response.json().catch(() => ({}));
      throw new Error(detail.error || (response.status === 403 ? 'You do not have permission for this action.' : `Request failed (${response.status}).`));
    }
    const raw = await response.text();
    return raw ? JSON.parse(raw) : null;
  }
  async function login(event) {
    event.preventDefault(); $('login-error').hidden = true;
    const button = event.submitter; button.disabled = true; button.textContent = 'Connecting…';
    const user = $('login-user').value.trim(); const password = $('login-password').value;
    state.auth = btoa(unescape(encodeURIComponent(`${user}:${password}`)));
    try {
      state.me = await request('/api/me');
      state.tenants = await request('/api/tenants');
      $('login-password').value = '';
      $('login-view').hidden = true; $('app-view').hidden = false;
      $('user-badge').textContent = `${state.me.username} / ${state.me.role}`;
      $('new-job-button').hidden = !canWrite(); $('pause-button').hidden = !canWrite(); $('pause-mobile-button').hidden = !canWrite();
      $('new-tenant-button').hidden = state.me.role !== 'ADMIN';
      document.querySelector('[data-section="dead"]').hidden = !canWrite();
      fillTenants();
      await refresh();
    } catch (error) {
      state.auth = null; state.me = null;
      $('login-error').textContent = error.message; $('login-error').hidden = false;
    } finally { button.disabled = false; button.innerHTML = 'Enter control room <span aria-hidden="true">↗</span>'; }
  }
  function logout() {
    state.auth = null; state.me = null; state.tenants = []; state.tenant = null;
    $('app-view').hidden = true; $('login-view').hidden = false; $('login-password').value = '';
    $('login-user').focus();
  }
  function fillTenants(prefer) {
    const select = $('tenant-select');
    select.replaceChildren();
    for (const tenant of state.tenants) {
      const option = document.createElement('option'); option.value = tenant.id; option.textContent = tenant.name;
      select.append(option);
    }
    if (prefer && state.tenants.some(t => t.id === prefer)) select.value = prefer;
    state.tenant = state.tenants.find(t => t.id === select.value) || null;
    updateTenantChrome();
  }
  function updateTenantChrome() {
    const tenant = state.tenant;
    $('tenant-zone').textContent = tenant ? tenant.zone : 'No tenant';
    $('tenant-cap').textContent = tenant ? `CAP ${tenant.concurrencyCap}` : '';
    $('tenant-state').textContent = tenant ? (tenant.paused ? 'Paused' : 'Active') : '';
    $('tenant-state').classList.toggle('paused', !!tenant?.paused);
    $('rail-tenant').textContent = tenant?.name || 'No tenant selected';
    $('rail-tenant-copy').textContent = tenant ? `${tenant.zone} · ${tenant.concurrencyCap} concurrent runs allowed.` : 'Create a tenant through the API to get started.';
    $('pause-button').textContent = tenant?.paused ? 'Resume tenant' : 'Pause tenant';
    $('pause-button').disabled = !tenant;
    $('pause-mobile-button').textContent = tenant?.paused ? 'Resume tenant' : 'Pause tenant';
    $('pause-mobile-button').disabled = !tenant;
  }
  function setSection(name) {
    if (!sectionCopy[name]) return;
    state.section = name;
    document.querySelectorAll('.nav-item').forEach(item => item.classList.toggle('active', item.dataset.section === name));
    document.querySelectorAll('.section').forEach(item => item.classList.toggle('active', item.id === `section-${name}`));
    $('section-title').innerHTML = `${sectionCopy[name][0]}<span class="accent-period">.</span>`;
    $('section-subtitle').textContent = sectionCopy[name][1];
    $('breadcrumb-current').textContent = name.toUpperCase();
  }
  async function refresh() {
    if (!state.auth) return;
    pageError('');
    try {
      state.tenants = await request('/api/tenants');
      fillTenants(state.tenant?.id);
      if (!state.tenant) { state.overview = null; state.jobs = []; state.runs = []; state.dead = []; render(); return; }
      const [overview, jobs, runs, dead] = await Promise.all([
        request(tenantPath('/overview')),
        request(tenantPath('/jobs')),
        request(tenantPath('/runs')),
        canWrite() ? request(tenantPath('/dead')) : Promise.resolve([])
      ]);
      state.overview = overview; state.jobs = jobs; state.runs = runs; state.dead = dead;
      render();
    } catch (error) { pageError(error.message); }
  }
  function render() {
    const overview = state.overview || {};
    for (const key of ['jobs','running','retry','dead']) $(`stat-${key}`).textContent = overview[key] ?? '—';
    $('jobs-count').textContent = `${state.jobs.length} SHOWN`;
    $('node-status').textContent = `${overview.nodes ?? 0} node${overview.nodes === 1 ? '' : 's'} online`;
    $('cluster-copy').textContent = `${overview.nodes ?? 0} worker node${overview.nodes === 1 ? '' : 's'} reporting to the cluster`;
    renderActivity(); renderQueue(); renderJobs(); renderRuns(); renderDead();
  }
  function empty(title, detail, cols = 1) { return `<tr><td colspan="${cols}"><div class="empty"><strong>${encode(title)}</strong>${encode(detail)}</div></td></tr>`; }
  function pill(status) { return `<span class="status-pill ${encode(String(status || '').toLowerCase())}">${encode(status || 'UNKNOWN')}</span>`; }
  function renderActivity() {
    const list = $('overview-runs');
    if (!state.runs.length) { list.innerHTML = '<div class="empty"><strong>No runs yet</strong>Trigger a job to see activity here.</div>'; return; }
    list.innerHTML = state.runs.slice(0, 5).map(run => `<div class="activity-row"><span class="activity-dot ${encode(run.state.toLowerCase())}"></span><div><strong>${encode(run.state)} · ${encode(short(run.id))}</strong><small>ATTEMPT ${encode(run.attempt)} · FENCE ${encode(run.fence)}</small></div><time>${encode(date(run.scheduledAt))}</time></div>`).join('');
  }
  function renderQueue() {
    const values = [['READY',state.overview?.ready || 0],['RETRY',state.overview?.retry || 0],['DEAD',state.overview?.dead || 0]];
    const max = Math.max(1,...values.map(v => v[1]));
    $('queue-visual').innerHTML = values.map(([label,count]) => `<div class="queue-row"><span>${label}</span><div class="queue-track"><div class="queue-fill" style="width:${Math.max(0,Math.min(100, count/max*100))}%"></div></div><strong>${encode(count)}</strong></div>`).join('');
  }
  function renderJobs() {
    $('jobs-body').innerHTML = state.jobs.length ? state.jobs.map(job => `<tr><td title="${encode(job.id)}">${encode(short(job.id))}</td><td>${encode(job.kind === 'CRON' ? job.cron : 'One-off')}</td><td>${encode(date(job.nextFireAt))}</td><td>${encode(job.misfire)}</td><td>${pill(job.enabled ? 'ACTIVE' : 'DISABLED')}</td><td><button class="row-action" data-action="next" data-id="${encode(job.id)}">Next ↗</button>${canWrite() ? ` <button class="row-action" data-action="trigger" data-id="${encode(job.id)}">Trigger ↗</button>` : ''}</td></tr>`).join('') : empty('No jobs yet',canWrite()?'Create the first job to begin scheduling.':'No jobs are visible for this tenant.',6);
  }
  function renderRuns() {
    $('runs-body').innerHTML = state.runs.length ? state.runs.map(run => `<tr><td title="${encode(run.id)}">${encode(short(run.id))}</td><td>${encode(date(run.scheduledAt))}</td><td>${encode(run.attempt)}</td><td>${encode(run.fence)}</td><td>${pill(run.state)}</td><td><button class="row-action" data-action="effect" data-id="${encode(run.id)}">Effect ↗</button></td></tr>`).join('') : empty('No runs yet','Scheduled and manual runs appear here.',6);
  }
  function renderDead() {
    $('dead-body').innerHTML = state.dead.length ? state.dead.map(run => `<tr><td title="${encode(run.id)}">${encode(short(run.id))}</td><td>${encode(date(run.scheduledAt))}</td><td>${encode(run.attempt)}</td><td>${pill(run.state)}</td><td>${canWrite() ? `<button class="row-action" data-action="replay" data-id="${encode(run.id)}">Replay ↗</button>` : ''}</td></tr>`).join('') : empty('Queue is clear','No dead-letter runs need attention.',5);
  }
  async function rowAction(event) {
    const button = event.target.closest('[data-action]'); if (!button || !state.tenant) return;
    const {action,id} = button.dataset; button.disabled = true;
    try {
      if (action === 'trigger') { const result = await request(tenantPath(`/jobs/${id}/trigger`), {method:'POST'}); notify(`Run ${short(result.runId)} queued.`); await refresh(); setSection('runs'); }
      if (action === 'replay') { await request(tenantPath(`/dead/${id}/replay`), {method:'POST'}); notify(`Run ${short(id)} queued for replay.`); await refresh(); }
      if (action === 'effect') { const result = await request(tenantPath(`/runs/${id}/effects`)); notify(`${result.count} committed effect${result.count === 1 ? '' : 's'} for run ${short(id)}.`); }
      if (action === 'next') { const result = await request(tenantPath(`/jobs/${id}/next?count=3`)); notify(`Next: ${result.map(fullDate).join(' · ')}`); }
    } catch (error) { notify(error.message,true); } finally { button.disabled = false; }
  }
  function toggleKind() { const one = $('job-kind').value === 'ONE_OFF'; $('cron-field').hidden = one; $('at-field').hidden = !one; }
  function closeModal() { $('modal-backdrop').hidden = true; $('job-error').hidden = true; $('tenant-modal-backdrop').hidden = true; $('tenant-error').hidden = true; }
  async function createJob(event) {
    event.preventDefault();
    const button = event.submitter; button.disabled = true;
    const kind = $('job-kind').value;
    const body = {kind,payload:$('job-payload').value,priority:Number($('job-priority').value),maxRunSeconds:Number($('job-max').value),misfire:$('job-misfire').value};
    if (kind === 'CRON') body.cron = $('job-cron').value.trim();
    else { const at = $('job-at').value; if (!at) { $('job-error').textContent = 'Choose a fire time.'; $('job-error').hidden = false; button.disabled = false; return; } body.at = new Date(at).toISOString(); }
    try { await request(tenantPath('/jobs'),{method:'POST',body:JSON.stringify(body)}); closeModal(); notify('Job created.'); await refresh(); setSection('jobs'); }
    catch (error) { $('job-error').textContent = error.message; $('job-error').hidden = false; }
    finally { button.disabled = false; }
  }
  async function pauseTenant(event) {
    if (!state.tenant) return;
    const action = state.tenant.paused ? 'resume' : 'pause';
    const button = event.currentTarget; button.disabled = true;
    try { await request(tenantPath(`/${action}`),{method:'POST'}); notify(`Tenant ${action}d.`); await refresh(); }
    catch(error) { notify(error.message,true); } finally { button.disabled = false; }
  }
  async function createTenant(event) {
    event.preventDefault(); const button = event.submitter; button.disabled = true;
    try {
      const result = await request('/api/admin/tenants',{method:'POST',body:JSON.stringify({name:$('tenant-name').value.trim(),zone:$('tenant-timezone').value.trim(),concurrencyCap:Number($('tenant-concurrency').value)})});
      closeModal(); notify('Tenant added.'); state.tenants = await request('/api/tenants'); fillTenants(result.id); await refresh();
    } catch (error) { $('tenant-error').textContent = error.message; $('tenant-error').hidden = false; }
    finally { button.disabled = false; }
  }
  function tick() {
    const now = new Date(); $('rail-clock').textContent = now.toLocaleTimeString(undefined,{hour12:false});
    $('rail-date').textContent = now.toLocaleDateString(undefined,{weekday:'long',month:'long',day:'numeric',year:'numeric'});
    $('today-label').textContent = now.toLocaleDateString(undefined,{day:'2-digit',month:'short',year:'numeric'}).toUpperCase();
  }
  $('login-form').addEventListener('submit',login);
  $('logout-button').addEventListener('click',logout);
  $('refresh-button').addEventListener('click',refresh);
  $('tenant-select').addEventListener('change',() => { state.tenant = state.tenants.find(t => t.id === $('tenant-select').value) || null; updateTenantChrome(); refresh(); });
  document.querySelectorAll('[data-section]').forEach(button => button.addEventListener('click',() => setSection(button.dataset.section)));
  document.querySelectorAll('[data-go]').forEach(button => button.addEventListener('click',() => setSection(button.dataset.go)));
  document.querySelectorAll('tbody').forEach(body => body.addEventListener('click',rowAction));
  $('new-job-button').addEventListener('click',() => { if (!state.tenant) return notify('Create or select a tenant first.',true); $('modal-backdrop').hidden = false; $('job-kind').focus(); });
  $('job-kind').addEventListener('change',toggleKind);
  $('job-form').addEventListener('submit',createJob);
  $('modal-close').addEventListener('click',closeModal); $('modal-cancel').addEventListener('click',closeModal);
  $('modal-backdrop').addEventListener('click',event => { if (event.target === $('modal-backdrop')) closeModal(); });
  $('new-tenant-button').addEventListener('click',() => { $('tenant-modal-backdrop').hidden = false; $('tenant-name').focus(); });
  $('tenant-form').addEventListener('submit',createTenant);
  $('tenant-modal-close').addEventListener('click',closeModal); $('tenant-modal-cancel').addEventListener('click',closeModal);
  $('tenant-modal-backdrop').addEventListener('click',event => { if (event.target === $('tenant-modal-backdrop')) closeModal(); });
  document.addEventListener('keydown',event => { if (event.key === 'Escape') closeModal(); });
  $('pause-button').addEventListener('click',pauseTenant);
  $('pause-mobile-button').addEventListener('click',pauseTenant);
  tick(); setInterval(tick,1000); setInterval(() => { if (state.auth && !document.hidden) refresh(); },15000);
})();
