import {clearExchanges, getExchange, listExchanges} from './api.js';
import {renderList} from './render/list.js';
import {renderRequest, renderResponse} from './render/raw.js';
import {escapeHtml, formatDuration, statusClass} from './util.js';

const TABS = {
    request: {label: 'Request', render: renderRequest},
    response: {label: 'Response', render: renderResponse},
};

const state = {
    exchanges: [],
    selectedId: null,
    tab: 'request',
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
    detailEl.innerHTML = `
        <div class="detail-head">
            <h2>${escapeHtml(exchange.request.method)} ${escapeHtml(exchange.request.path || '/')}</h2>
            <div class="url">${escapeHtml(exchange.request.url)}</div>
            <div class="chips">
                <span class="badge">${escapeHtml(exchange.route)}</span>
                <span class="badge ${statusClass(response?.status)}">${response?.status ?? 'pending…'}</span>
                ${response ? `<span class="badge">${formatDuration(response.durationMs)}</span>` : ''}
            </div>
        </div>
        <div class="tabs" role="tablist">
            ${Object.entries(TABS).map(([key, tab]) => `
                <button type="button" role="tab" class="tab ${key === state.tab ? 'active' : ''}" data-tab="${key}">
                    ${tab.label}
                </button>`).join('')}
        </div>
        <div>${TABS[state.tab].render(exchange)}</div>`;
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
