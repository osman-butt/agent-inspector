import {clearExchanges, getExchange, getSession, listExchanges, listSessions} from './api.js';
import {renderConversation} from './render/conversation.js';
import {renderList} from './render/list.js';
import {renderRequest, renderResponse} from './render/raw.js';
import {renderSessionDetail, renderSessionList} from './render/sessions.js';
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
    if (!state.selected) {
        detailEl.innerHTML = emptyDetail;
    } else if (state.selected.kind === 'session') {
        detailEl.innerHTML = renderSessionDetail(await getSession(state.selected.id));
    } else {
        detailEl.innerHTML = renderExchange(await getExchange(state.selected.id));
    }
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
            <h2>${escapeHtml(parsed?.model ?? `${exchange.request.method} ${exchange.request.path || '/'}`)}</h2>
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
    source.addEventListener('cleared', () => {
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

detailEl.addEventListener('click', event => {
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

document.getElementById('clear').addEventListener('click', () => clearExchanges());

connect();
