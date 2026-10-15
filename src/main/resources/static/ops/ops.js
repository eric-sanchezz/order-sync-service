// Operator page for the order-sync admin API. No dependencies, no build step.
// The key the operator types lives only in sessionStorage (this tab) and travels only in the X-API-Key header.
// Everything that comes from the API is written with textContent / createElement, never parsed as HTML.
'use strict';

const KEY_STORAGE = 'ordersync.apiKey';
const PAGE_SIZE = 20;
const ERROR_PREVIEW = 60;

const $ = (id) => document.getElementById(id);

// ---- key ----

function storedKey() {
	return sessionStorage.getItem(KEY_STORAGE);
}

function showKeyState() {
	$('key-state').textContent = storedKey() ? 'Key set for this tab' : 'No key set';
}

// ---- messages ----

function showMessage(text, isError) {
	const message = $('message');
	message.textContent = text;
	message.classList.toggle('error', Boolean(isError));
	message.hidden = false;
}

function clearMessage() {
	const message = $('message');
	message.textContent = '';
	message.hidden = true;
}

// ---- API ----

// Returns the parsed JSON body, or null after showing why the call failed.
async function api(method, path) {
	const headers = { Accept: 'application/json' };
	const key = storedKey();
	if (key) {
		headers['X-API-Key'] = key;
	}
	let response;
	try {
		response = await fetch(path, { method, headers, cache: 'no-store' });
	}
	catch (networkError) {
		showMessage('Cannot reach the service. Check that it is running and try again.', true);
		return null;
	}
	if (response.ok) {
		return response.json();
	}
	if (response.status === 401) {
		showMessage('Missing or wrong API key.', true);
	}
	else if (response.status === 404) {
		showMessage('Not found: ' + path, true);
	}
	else if (response.status === 409) {
		const body = await response.json().catch(() => ({}));
		showMessage('Conflict: ' + (body.error || 'the item changed meanwhile') + '.', true);
	}
	else {
		showMessage('Request failed with HTTP ' + response.status + '.', true);
	}
	return null;
}

// ---- rendering helpers (textContent only) ----

function text(value) {
	return value === null || value === undefined || value === '' ? '-' : String(value);
}

function time(value) {
	return value ? String(value).replace('T', ' ').replace(/\.\d+Z$/, 'Z') : '-';
}

function statusBadge(status) {
	const badge = document.createElement('span');
	badge.className = 'status status-' + status;
	badge.textContent = text(status);
	return badge;
}

function cell(row, content) {
	const td = document.createElement('td');
	if (content instanceof Node) {
		td.appendChild(content);
	}
	else {
		td.textContent = text(content);
	}
	row.appendChild(td);
	return td;
}

// A value of any length (event id, ERP order id, aggregate id): may break anywhere.
function longCell(row, value) {
	cell(row, value).className = 'long';
}

// Date and time each stay on one line; a narrow column breaks only between them.
function timeCell(row, value) {
	const td = cell(row, '');
	td.className = 'time';
	td.textContent = '';
	time(value).split(' ').forEach((part, index) => {
		if (index > 0) {
			td.appendChild(document.createTextNode(' '));
		}
		const span = document.createElement('span');
		span.textContent = part;
		td.appendChild(span);
	});
}

function errorCell(row, lastError) {
	const full = text(lastError);
	const td = cell(row, full.length > ERROR_PREVIEW ? full.slice(0, ERROR_PREVIEW) + '...' : full);
	td.className = 'error-cell';
	td.title = full;
}

function clear(element) {
	while (element.firstChild) {
		element.removeChild(element.firstChild);
	}
}

function placeholder(element, message) {
	clear(element);
	const span = document.createElement('span');
	span.className = 'muted';
	span.textContent = message;
	element.appendChild(span);
}

// ---- stats ----

async function loadStats() {
	const stats = await api('GET', '/admin/stats');
	if (!stats) {
		return;
	}
	const box = $('stats');
	clear(box);
	const label = document.createElement('strong');
	label.textContent = 'Jobs by status:';
	box.appendChild(label);
	for (const [status, count] of Object.entries(stats.byStatus || {})) {
		const stat = document.createElement('span');
		stat.className = 'stat';
		stat.dataset.status = status;
		stat.appendChild(statusBadge(status));
		const number = document.createElement('span');
		number.className = 'count';
		number.textContent = text(count);
		stat.appendChild(number);
		box.appendChild(stat);
	}
	const latency = stats.endToEndMs || {};
	const span = document.createElement('span');
	span.className = 'muted';
	span.textContent = 'End-to-end latency of DONE jobs (ms): p50 ' + text(latency.p50) + ', p95 ' + text(latency.p95)
		+ ', max ' + text(latency.max);
	box.appendChild(span);
}

// ---- lists, details and replay: jobs and outbox share the code, each with its own paths and columns ----

const jobs = {
	prefix: 'job',
	ids: { filter: 'jobs-filter', table: 'jobs-table', prev: 'jobs-prev', next: 'jobs-next', page: 'jobs-page' },
	listPath: '/admin/jobs',
	noun: 'job',
	page: 0,
	listRequest: 0,
	openId: null,
	row: (tr, item) => {
		longCell(tr, item.eventId);
		cell(tr, statusBadge(item.status));
		cell(tr, item.attempts);
		longCell(tr, item.erpOrderId);
		errorCell(tr, item.lastError);
		timeCell(tr, item.createdAt);
		timeCell(tr, item.completedAt);
	},
	fields: ['id', 'eventId', 'status', 'attempts', 'lastError', 'erpOrderId', 'nextAttemptAt', 'lockedUntil',
		'createdAt', 'updatedAt', 'completedAt'],
};

const outbox = {
	prefix: 'outbox',
	ids: { filter: 'outbox-filter', table: 'outbox-table', prev: 'outbox-prev', next: 'outbox-next',
		page: 'outbox-page' },
	listPath: '/admin/outbox',
	noun: 'message',
	page: 0,
	listRequest: 0,
	openId: null,
	row: (tr, item) => {
		cell(tr, item.destination);
		longCell(tr, item.aggregateId);
		cell(tr, item.eventType);
		cell(tr, statusBadge(item.status));
		cell(tr, item.attempts);
		errorCell(tr, item.lastError);
		timeCell(tr, item.createdAt);
		timeCell(tr, item.deliveredAt);
	},
	fields: ['id', 'aggregateType', 'aggregateId', 'eventType', 'destination', 'status', 'attempts', 'lastError',
		'nextAttemptAt', 'lockedUntil', 'createdAt', 'updatedAt', 'deliveredAt'],
};

// Only the latest request of a section is painted: a double click on Next, or a filter change while a page is still
// loading, sends two requests whose answers can arrive in any order. section.page follows the page on screen.
async function loadList(section, page = section.page) {
	const request = ++section.listRequest;
	const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
	const status = $(section.ids.filter).value;
	if (status) {
		query.set('status', status);
	}
	const result = await api('GET', section.listPath + '?' + query);
	if (!result || request !== section.listRequest) {
		return;
	}
	section.page = result.page;
	const body = $(section.ids.table).tBodies[0];
	clear(body);
	for (const item of result.items) {
		const tr = document.createElement('tr');
		tr.classList.toggle('selected', item.id === section.openId);
		const open = document.createElement('button');
		open.type = 'button';
		open.className = 'link';
		open.textContent = String(item.id);
		open.addEventListener('click', () => openDetail(section, item.id));
		cell(tr, open);
		section.row(tr, item);
		body.appendChild(tr);
	}
	if (result.items.length === 0) {
		const tr = document.createElement('tr');
		const td = cell(tr, 'Nothing here.');
		td.colSpan = $(section.ids.table).tHead.rows[0].cells.length;
		td.className = 'muted';
		body.appendChild(tr);
	}
	const pages = Math.max(1, Math.ceil(result.totalElements / PAGE_SIZE));
	$(section.ids.page).textContent = 'Page ' + (result.page + 1) + ' of ' + pages + ' (' + result.totalElements
		+ ' total)';
	$(section.ids.prev).disabled = result.page <= 0;
	$(section.ids.next).disabled = (result.page + 1) * PAGE_SIZE >= result.totalElements;
}

async function loadDetail(section) {
	const id = section.openId;
	const replay = $(section.prefix + '-replay');
	const box = $(section.prefix + '-detail');
	const detail = await api('GET', section.listPath + '/' + encodeURIComponent(id));
	if (id !== section.openId) {
		return; // another item was opened (or the key forgotten) meanwhile
	}
	if (!detail) {
		replay.hidden = true;
		return;
	}
	clear(box);
	const list = document.createElement('dl');
	for (const field of section.fields) {
		const dt = document.createElement('dt');
		dt.textContent = field;
		const dd = document.createElement('dd');
		if (field === 'status') {
			dd.appendChild(statusBadge(detail.status));
		}
		else {
			dd.textContent = text(detail[field]);
		}
		list.appendChild(dt);
		list.appendChild(dd);
	}
	box.appendChild(list);
	const payload = document.createElement('pre');
	payload.textContent = detail.payload === null || detail.payload === undefined ? '-'
		: JSON.stringify(detail.payload, null, 2);
	box.appendChild(payload);
	replay.hidden = detail.status !== 'DEAD';
}

async function openDetail(section, id) {
	clearMessage();
	section.openId = id;
	for (const tr of $(section.ids.table).tBodies[0].rows) {
		tr.classList.toggle('selected', tr.cells[0].textContent === String(id));
	}
	await loadDetail(section);
}

async function replay(section) {
	const id = section.openId;
	if (id === null || !window.confirm('Replay ' + section.noun + ' ' + id + '?')) {
		return;
	}
	clearMessage();
	const result = await api('POST', section.listPath + '/' + encodeURIComponent(id) + '/replay');
	if (result) {
		showMessage('Replay requested for ' + section.noun + ' ' + id + ': now ' + result.status + '.', false);
	}
	// Whatever the answer (replayed, 409 because it changed meanwhile, 404), show the real state now.
	await Promise.all([loadDetail(section), loadList(section), loadStats()]);
}

function wireSection(section) {
	$(section.ids.filter).addEventListener('change', () => {
		clearMessage();
		section.page = 0;
		loadList(section);
	});
	$(section.ids.prev).addEventListener('click', () => {
		clearMessage();
		loadList(section, Math.max(0, section.page - 1));
	});
	$(section.ids.next).addEventListener('click', () => {
		clearMessage();
		loadList(section, section.page + 1);
	});
	$(section.prefix + '-replay').addEventListener('click', () => replay(section));
}

// ---- page ----

async function refreshAll() {
	const loads = [loadStats(), loadList(jobs), loadList(outbox)];
	for (const section of [jobs, outbox]) {
		if (section.openId !== null) {
			loads.push(loadDetail(section));
		}
	}
	await Promise.all(loads);
}

function forgetData() {
	placeholder($('stats'), 'Set a key and press Refresh.');
	for (const section of [jobs, outbox]) {
		clear($(section.ids.table).tBodies[0]);
		$(section.ids.page).textContent = '';
		$(section.ids.prev).disabled = true;
		$(section.ids.next).disabled = true;
		section.page = 0;
		section.listRequest += 1; // drops a list answer still in flight
		section.openId = null;
		$(section.prefix + '-replay').hidden = true;
	}
	placeholder($('job-detail'), 'Pick a job by its ID.');
	placeholder($('outbox-detail'), 'Pick a message by its ID.');
}

document.addEventListener('DOMContentLoaded', () => {
	$('key-form').addEventListener('submit', (event) => {
		event.preventDefault();
		const input = $('api-key');
		const key = input.value.trim();
		input.value = '';
		if (!key) {
			showMessage('Type the API key first.', true);
			return;
		}
		sessionStorage.setItem(KEY_STORAGE, key);
		showKeyState();
		clearMessage();
		refreshAll();
	});
	$('forget-key').addEventListener('click', () => {
		sessionStorage.removeItem(KEY_STORAGE);
		showKeyState();
		forgetData();
		showMessage('Key forgotten.', false);
	});
	$('refresh').addEventListener('click', () => {
		clearMessage();
		refreshAll();
	});
	wireSection(jobs);
	wireSection(outbox);
	showKeyState();
	if (storedKey()) {
		refreshAll();
	}
});
