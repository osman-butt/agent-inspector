import {escapeHtml, formatDuration, formatTime, formatTokens, statusClass} from '../util.js';

function title(exchange) {
    return exchange.model
        ? `<span class="model">${escapeHtml(exchange.model)}</span>`
        : `<span class="path">${escapeHtml(exchange.method)} ${escapeHtml(exchange.path || '/')}</span>`;
}

function toolCalls(exchange) {
    return exchange.toolCalls?.length
        ? `<span class="tools">→ ${exchange.toolCalls.map(escapeHtml).join(', ')}</span>`
        : '';
}

export function renderList(container, exchanges, selectedId) {
    if (exchanges.length === 0) {
        container.innerHTML = '<p class="muted" style="padding: 12px">Nothing captured yet.</p>';
        return;
    }
    container.innerHTML = exchanges.map(exchange => `
        <button type="button" class="row ${exchange.id === selectedId ? 'selected' : ''}" data-kind="exchange" data-id="${escapeHtml(exchange.id)}">
            <div class="line">
                <span class="badge">${escapeHtml(exchange.route)}</span>
                ${title(exchange)}
            </div>
            <div class="line meta">
                <span class="${statusClass(exchange.status, exchange.error)}">${exchange.status ?? 'pending…'}</span>
                <span>${formatDuration(exchange.durationMs)}</span>
                <span>${formatTokens(exchange.inputTokens, exchange.outputTokens)}</span>
                ${toolCalls(exchange)}
                <span class="spacer"></span>
                <span>${formatTime(exchange.startedAt)}</span>
            </div>
        </button>`).join('');
}
