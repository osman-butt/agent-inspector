import {clearExchanges, getExchange, listExchanges} from './api.js';
import {renderConversation} from './render/conversation.js';
import {renderList} from './render/list.js';
import {renderRequest, renderResponse} from './render/raw.js';
import {escapeHtml, formatDuration, formatTokens, statusClass} from './util.js';

const TABS = {
    conversation: {label: 'Conversation', render: renderConversation, available: exchange => exchange.parsed != null},
    request: {label: 'Request', render: renderRequest, available: () => true},
    response: {label: 'Response', render: renderResponse, available: () => true},
};

const state = {
    exchanges: [],
    selectedId: null,
    tab: 'conversation',
};

const listEl = document.getElementById('list');
const detailEl = document.getElementById('detail');
const countEl = document.getElementById('count');
const liveEl = document.getElementById('live');
const emptyDetail = detailEl.innerHTML;

function renderAll() {
    countEl.textContent = `${state.exchanges.length} captured`;
    renderList(listEl, state.exchanges, state.selectedId);
}

async function refreshList() {
    state.exchanges = await listExchanges();
    renderAll();
}

async function showDetail() {
    if (!state.selectedId) {
        detailEl.innerHTML = emptyDetail;
        return;
    }
    const exchange = await getExchange(state.selectedId);
    const response = exchange.response;
    const parsed = exchange.parsed;
    const tabs = Object.entries(TABS).filter(([, tab]) => tab.available(exchange));
    const tab = tabs.some(([key]) => key === state.tab) ? state.tab : tabs[0][0];
    const chip = (text, className = '') => text ? `<span class="badge ${className}">${escapeHtml(text)}</span>` : '';
    detailEl.innerHTML = `
        <div class="detail-head">
            <h2>${escapeHtml(parsed?.model ?? `${exchange.request.method} ${exchange.request.path || '/'}`)}</h2>
            <div class="url">${escapeHtml(exchange.request.method)} ${escapeHtml(exchange.request.url)}</div>
            <div class="chips">
                ${chip(exchange.route)}
                ${chip(parsed?.provider)}
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

/** Adds a new exchange at the top, or updates one in place when its response arrives. */
function upsert(summary) {
    const index = state.exchanges.findIndex(exchange => exchange.id === summary.id);
    if (index === -1) {
        state.exchanges.unshift(summary);
    } else {
        state.exchanges[index] = summary;
    }
    renderAll();
    if (summary.id === state.selectedId) {
        showDetail();
    }
}

function connect() {
    const source = new EventSource('api/stream');
    source.addEventListener('open', () => {
        liveEl.classList.add('on');
        liveEl.textContent = 'live';
        refreshList(); // catch up on anything missed while disconnected
    });
    source.addEventListener('error', () => {
        liveEl.classList.remove('on');
        liveEl.textContent = 'reconnecting…';
    });
    source.addEventListener('exchange', event => upsert(JSON.parse(event.data)));
    source.addEventListener('cleared', () => {
        state.exchanges = [];
        state.selectedId = null;
        renderAll();
        showDetail();
    });
}

listEl.addEventListener('click', event => {
    const row = event.target.closest('.row');
    if (!row) {
        return;
    }
    state.selectedId = row.dataset.id;
    renderAll();
    showDetail();
});

detailEl.addEventListener('click', event => {
    const tab = event.target.closest('.tab');
    if (tab) {
        state.tab = tab.dataset.tab;
        showDetail();
    }
});

document.getElementById('clear').addEventListener('click', () => clearExchanges());

connect();
