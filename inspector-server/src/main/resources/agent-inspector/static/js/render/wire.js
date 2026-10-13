import {escapeHtml, formatBody, formatBytes, statusClass} from '../util.js';

const utf8 = new TextEncoder();
const byteLength = text => text ? utf8.encode(text).length : 0;

/**
 * The request and response bodies of one round-trip, exactly as they went over the wire,
 * folded until clicked. Given the full exchange, the bodies render right away; given only
 * its id and sizes (the session timeline), they are fetched when opened, see {@link fillWire}.
 */
export function renderWire(id, exchange = null, sizes = {}) {
    const requestBytes = exchange ? byteLength(exchange.request.body) : sizes.requestBytes;
    const responseBytes = exchange
        ? (exchange.response ? byteLength(exchange.response.body) : null)
        : sizes.responseBytes;
    return `
        <div class="wire">
            ${renderPart(id, 'request', '→ Request JSON sent to the model', requestBytes,
                exchange ? requestBody(exchange) : null)}
            ${renderPart(id, 'response', '← Response JSON received', responseBytes,
                exchange ? responseBody(exchange) : null)}
        </div>`;
}

/** Fills a lazily loaded accordion once its exchange has been fetched. */
export function fillWire(details, exchange) {
    const request = details.dataset.part === 'request';
    details.querySelector('.wire-body').innerHTML = request ? requestBody(exchange) : responseBody(exchange);
    if (request || exchange.response) {
        details.dataset.loaded = 'true'; // a pending response is fetched again next time
    }
}

function renderPart(id, part, label, bytes, body) {
    return `
        <details class="wire-part" data-key="${escapeHtml(id)}:${part}" data-exchange-id="${escapeHtml(id)}"
                 data-part="${part}" ${body ? 'data-loaded="true"' : ''}>
            <summary>${label}${bytes != null ? ` <span class="muted">· ${formatBytes(bytes)}</span>` : ''}</summary>
            <div class="wire-body">${body ?? '<span class="muted">Loading…</span>'}</div>
        </details>`;
}

function requestBody(exchange) {
    return `
        <div class="wire-line">${escapeHtml(exchange.request.method)} ${escapeHtml(exchange.request.url)}</div>
        <pre class="box">${formatBody(exchange.request.body)}</pre>`;
}

function responseBody(exchange) {
    const response = exchange.response;
    if (!response) {
        return '<span class="muted">Waiting for the response…</span>';
    }
    return `
        <div class="wire-line ${statusClass(response.status, response.error)}">
            ${response.status} ${escapeHtml(response.headers?.['content-type'] ?? '')}
        </div>
        ${response.error ? `<div class="error">${escapeHtml(response.error)}</div>` : ''}
        <pre class="box">${formatBody(response.body)}</pre>`;
}
