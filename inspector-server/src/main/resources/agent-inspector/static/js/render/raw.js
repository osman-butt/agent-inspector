import {escapeHtml, formatBody} from '../util.js';

function headersTable(headers) {
    const entries = Object.entries(headers ?? {});
    if (entries.length === 0) {
        return '<span class="muted">(none)</span>';
    }
    return `<table class="headers">${entries.map(([name, value]) =>
        `<tr><td>${escapeHtml(name)}</td><td>${escapeHtml(value)}</td></tr>`).join('')}</table>`;
}

export function renderRequest(exchange) {
    const request = exchange.request;
    return `
        <div class="section"><h3>Headers</h3><div class="box">${headersTable(request.headers)}</div></div>
        <div class="section"><h3>Body</h3><div class="box"><pre>${formatBody(request.body)}</pre></div></div>`;
}

export function renderResponse(exchange) {
    const response = exchange.response;
    if (!response) {
        return '<p class="muted">Waiting for the response…</p>';
    }
    const error = response.error
        ? `<div class="section"><h3>Error</h3><div class="box error"><pre>${escapeHtml(response.error)}</pre></div></div>`
        : '';
    return `${error}
        <div class="section"><h3>Headers</h3><div class="box">${headersTable(response.headers)}</div></div>
        <div class="section"><h3>Body</h3><div class="box"><pre>${formatBody(response.body)}</pre></div></div>`;
}
