import {clearExchanges, getExchange, getSession, listExchanges, listSessions} from './api.js';
import {renderConversation} from './render/conversation.js';
import {renderList} from './render/list.js';
import {renderRequest, renderResponse} from './render/raw.js';
import {renderSessionDetail, renderSessionList} from './render/sessions.js';
import {fillWire} from './render/wire.js';
import {isTruncated, toCurl} from './curl.js';
import {escapeHtml, formatDuration, formatTokens, statusClass} from './util.js';

const TABS = {
    conversation: {label: 'Conversation', render: renderConversation, available: exchange => exchange.parsed != null},
    request: {label: 'Request', render: renderRequest, available: () => true},
    response: {label: 'Response', render: renderResponse, available: () => true},
};

const state = {
    view: 'sessions',       // what the sidebar lists: sessions or individual calls
    sessions: [],
    exchanges: [],
    selected: null,         // {kind: 'session' | 'exchange', id}
    backToSession: null,    // set when a round-trip was opened from a session timeline
    tab: 'conversation',
};

const listEl = document.getElementById('list');
const detailEl = document.getElementById('detail');
const countEl = document.getElementById('count');
const liveEl = document.getElementById('live');
const emptyDetail = detailEl.innerHTML;

const selectedId = kind => state.selected?.kind === kind ? state.selected.id : null;

/** Finished exchanges fetched for the JSON accordions in the session timeline. */
const exchangeCache = new Map();

async function loadExchange(id) {
    if (exchangeCache.has(id)) {
        return exchangeCache.get(id);
    }
    const exchange = await getExchange(id);
    if (exchange.response) {
        exchangeCache.set(id, exchange);
    }
    return exchange;
}

/** What is on screen, so a live re-render of the same item can restore open folds and scroll position. */
let shown = null;

function renderAll() {
    const count = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;
    countEl.textContent = `${count(state.sessions.length, 'session')} · ${count(state.exchanges.length, 'call')}`;
    if (state.view === 'sessions') {
        renderSessionList(listEl, state.sessions, selectedId('session'));
    } else {
        renderList(listEl, state.exchanges, selectedId('exchange'));
    }
    document.querySelectorAll('.list-tabs .tab').forEach(tab =>
        tab.classList.toggle('active', tab.dataset.view === state.view));
}

async function refreshLists() {
    [state.sessions, state.exchanges] = await Promise.all([listSessions(), listExchanges()]);
    renderAll();
}

async function showDetail() {
    let html = emptyDetail;
    if (state.selected?.kind === 'session') {
        html = renderSessionDetail(await getSession(state.selected.id));
    } else if (state.selected) {
        html = renderExchange(await getExchange(state.selected.id));
    }
    const item = state.selected ? `${state.selected.kind}:${state.selected.id}:${state.tab}` : null;
    const same = item === shown;
    const open = same ? new Set([...detailEl.querySelectorAll('details[open][data-key]')].map(fold => fold.dataset.key)) : new Set();
    const scroll = detailEl.scrollTop;
    detailEl.innerHTML = html;
    detailEl.querySelectorAll('details[data-key]').forEach(fold => {
        if (open.has(fold.dataset.key)) {
            fold.open = true;
        }
    });
    detailEl.scrollTop = same ? scroll : 0;
    shown = item;
}

function renderExchange(exchange) {
    const response = exchange.response;
    const parsed = exchange.parsed;
    const tabs = Object.entries(TABS).filter(([, tab]) => tab.available(exchange));
    const tab = tabs.some(([key]) => key === state.tab) ? state.tab : tabs[0][0];
    const chip = (text, className = '') => text ? `<span class="badge ${className}">${escapeHtml(text)}</span>` : '';
    const back = state.backToSession
        ? `<button type="button" class="back" data-session-id="${escapeHtml(state.backToSession)}">← Back to session</button>`
        : '';
    return `
        ${back}
        <div class="detail-head">
            <div class="head-line">
                <h2>${escapeHtml(parsed?.model ?? `${exchange.request.method} ${exchange.request.path || '/'}`)}</h2>
                <span class="spacer"></span>
                <button type="button" class="copy-curl" data-exchange-id="${escapeHtml(exchange.id)}">Copy as cURL</button>
            </div>
            <div class="url">${escapeHtml(exchange.request.method)} ${escapeHtml(exchange.request.url)}</div>
            <div class="chips">
                ${chip(exchange.route)}
                ${chip(parsed?.provider)}
                ${chip(exchange.session ? `round-trip ${exchange.session.turn}` : '')}
                ${chip(response ? String(response.status) : 'pending…', statusClass(response?.status, parsed?.error ?? response?.error))}
                ${chip(response ? formatDuration(response.durationMs) : '')}
                ${chip(parsed?.streamed ? 'streamed' : '')}
                ${chip(formatTokens(parsed?.usage?.inputTokens, parsed?.usage?.outputTokens))}
                ${chip(parsed?.usage?.cacheReadTokens ? `${parsed.usage.cacheReadTokens} cached` : '')}
                ${chip(parsed?.finishReason)}
            </div>
        </div>
        <div class="tabs" role="tablist">
            ${tabs.map(([key, {label}]) => `
                <button type="button" role="tab" class="tab ${key === tab ? 'active' : ''}" data-tab="${key}">
                    ${label}
                </button>`).join('')}
        </div>
        <div>${TABS[tab].render(exchange)}</div>`;
}

function select(kind, id, backToSession = null) {
    state.selected = {kind, id};
    state.backToSession = backToSession;
    renderAll();
    showDetail();
}

/** Adds an item at the top, or replaces it in place when it changes. */
function upsert(items, item) {
    const index = items.findIndex(existing => existing.id === item.id);
    if (index === -1) {
        items.unshift(item);
    } else {
        items[index] = item;
    }
}

function connect() {
    const source = new EventSource('api/stream');
    source.addEventListener('open', () => {
        liveEl.classList.add('on');
        liveEl.textContent = 'live';
        refreshLists(); // catch up on anything missed while disconnected
    });
    source.addEventListener('error', () => {
        liveEl.classList.remove('on');
        liveEl.textContent = 'reconnecting…';
    });
    source.addEventListener('exchange', event => {
        const exchange = JSON.parse(event.data);
        upsert(state.exchanges, exchange);
        renderAll();
        if (selectedId('exchange') === exchange.id) {
            showDetail();
        }
    });
    source.addEventListener('session', event => {
        const session = JSON.parse(event.data);
        upsert(state.sessions, session);
        renderAll();
        if (selectedId('session') === session.id) {
            showDetail();
        }
    });
    source.addEventListener('tool', event => {
        const execution = JSON.parse(event.data);
        if (detailEl.querySelector(`[data-tool-call-id="${CSS.escape(execution.toolCallId ?? '')}"]`)) {
            showDetail();
        }
    });
    source.addEventListener('chat-client-call', () => {
        if (state.selected?.kind === 'session') {
            showDetail(); // the report arrives when the call ends, after its round-trips
        }
    });
    source.addEventListener('cleared', () => {
        exchangeCache.clear();
        state.sessions = [];
        state.exchanges = [];
        state.selected = null;
        state.backToSession = null;
        renderAll();
        showDetail();
    });
}

listEl.addEventListener('click', event => {
    const row = event.target.closest('.row');
    if (row) {
        select(row.dataset.kind, row.dataset.id);
    }
});

document.querySelector('.list-tabs').addEventListener('click', event => {
    const tab = event.target.closest('[data-view]');
    if (tab) {
        state.view = tab.dataset.view;
        renderAll();
    }
});

/** Copies the call as a curl command and says so on the button for a moment. */
async function copyCurl(button) {
    const exchange = await loadExchange(button.dataset.exchangeId);
    try {
        await navigator.clipboard.writeText(toCurl(exchange, location.origin));
        button.textContent = isTruncated(exchange) ? 'Copied (body truncated!)' : 'Copied ✓';
    } catch {
        button.textContent = 'Copy failed';
    }
    setTimeout(() => button.textContent = 'Copy as cURL', 1500);
}

detailEl.addEventListener('click', event => {
    const copy = event.target.closest('.copy-curl');
    if (copy) {
        copyCurl(copy);
        return;
    }
    const tab = event.target.closest('.tab');
    const turn = event.target.closest('.turn-open');
    const back = event.target.closest('.back');
    if (tab) {
        state.tab = tab.dataset.tab;
        showDetail();
    } else if (turn) {
        select('exchange', turn.dataset.exchangeId, state.selected.id);
    } else if (back) {
        select('session', back.dataset.sessionId);
    }
});

// toggle doesn't bubble, so listen in the capture phase. Opening a timeline accordion loads its body.
detailEl.addEventListener('toggle', async event => {
    const fold = event.target;
    if (fold.open && fold.matches('.wire-part') && !fold.dataset.loaded) {
        fillWire(fold, await loadExchange(fold.dataset.exchangeId));
    }
}, true);

document.getElementById('clear').addEventListener('click', () => clearExchanges());

connect();
