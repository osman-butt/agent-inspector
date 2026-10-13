import {escapeHtml, formatDuration, formatTime, statusClass} from '../util.js';

export function renderList(container, exchanges, selectedId) {
    if (exchanges.length === 0) {
        container.innerHTML = '<p class="muted" style="padding: 12px">Nothing captured yet.</p>';
        return;
    }
    container.innerHTML = exchanges.map(exchange => `
        <button type="button" class="row ${exchange.id === selectedId ? 'selected' : ''}" data-id="${escapeHtml(exchange.id)}">
            <div class="line">
                <span class="badge">${escapeHtml(exchange.route)}</span>
                <span class="path">${escapeHtml(exchange.method)} ${escapeHtml(exchange.path || '/')}</span>
            </div>
            <div class="line meta">
                <span class="${statusClass(exchange.status)}">${exchange.status ?? 'pending…'}</span>
                <span>${formatDuration(exchange.durationMs)}</span>
                <span class="spacer"></span>
                <span>${formatTime(exchange.startedAt)}</span>
            </div>
        </button>`).join('');
}
