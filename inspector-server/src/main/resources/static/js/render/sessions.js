import {escapeHtml, formatDuration, formatTime, formatTokens, statusClass} from '../util.js';
import {renderMessage, toolNames} from './conversation.js';

function plural(count, word) {
    return `${count} ${word}${count === 1 ? '' : 's'}`;
}

function sessionState(session) {
    if (session.pending) {
        return '<span class="status-pending">running…</span>';
    }
    return session.failed
        ? '<span class="status-bad">failed</span>'
        : `<span>${formatDuration(session.durationMs)}</span>`;
}

export function renderSessionList(container, sessions, selectedId) {
    if (sessions.length === 0) {
        container.innerHTML = '<p class="muted" style="padding: 12px">No model calls yet.</p>';
        return;
    }
    container.innerHTML = sessions.map(session => `
        <button type="button" class="row ${session.id === selectedId ? 'selected' : ''}" data-kind="session" data-id="${escapeHtml(session.id)}">
            <div class="line"><span class="title">${escapeHtml(session.title)}</span></div>
            <div class="line meta">
                <span class="badge">${escapeHtml(session.route)}</span>
                <span>${plural(session.roundTrips, 'round-trip')}</span>
                ${session.toolCalls ? `<span class="tools">${plural(session.toolCalls, 'tool call')}</span>` : ''}
                ${sessionState(session)}
                <span class="spacer"></span>
                <span>${formatTime(session.startedAt)}</span>
            </div>
        </button>`).join('');
}

function renderTurn(turn, index, names) {
    const exchange = turn.exchange;
    const answer = turn.output
        ? renderMessage(turn.output, names, 'answer')
        : turn.error
            ? `<div class="box error"><pre>${escapeHtml(turn.error)}</pre></div>`
            : '<p class="muted">Waiting for the model…</p>';
    return `
        <li class="turn">
            <div class="turn-head">
                <button type="button" class="turn-open" data-exchange-id="${escapeHtml(exchange.id)}"
                        title="Open this round-trip">Round-trip ${index + 1} ↗</button>
                <span class="badge ${statusClass(exchange.status, exchange.error)}">${exchange.status ?? 'pending…'}</span>
                ${exchange.durationMs != null ? `<span class="badge">${formatDuration(exchange.durationMs)}</span>` : ''}
                ${exchange.inputTokens != null ? `<span class="badge">${formatTokens(exchange.inputTokens, exchange.outputTokens)}</span>` : ''}
                ${turn.finishReason ? `<span class="badge">${escapeHtml(turn.finishReason)}</span>` : ''}
            </div>
            ${turn.resent ? `<div class="resent-note muted">↺ ${plural(turn.resent, 'earlier message')} re-sent</div>` : ''}
            ${turn.newMessages.map(message => renderMessage(message, names, turn.resent ? 'new' : '')).join('')}
            ${answer}
        </li>`;
}

export function renderSessionDetail(session) {
    const {summary, turns} = session;
    const names = toolNames(turns.flatMap(turn => [...turn.newMessages, turn.output]));
    const chip = text => text ? `<span class="badge">${escapeHtml(text)}</span>` : '';
    return `
        <div class="detail-head">
            <h2 class="session-title">${escapeHtml(summary.title)}</h2>
            <div class="chips">
                ${chip(summary.route)}
                ${chip(summary.model)}
                ${chip(plural(summary.roundTrips, 'round-trip'))}
                ${chip(summary.toolCalls ? plural(summary.toolCalls, 'tool call') : '')}
                ${chip(formatTokens(summary.inputTokens, summary.outputTokens))}
                ${summary.pending ? '<span class="badge status-pending">running…</span>' : chip(formatDuration(summary.durationMs))}
                ${summary.failed ? '<span class="badge status-bad">failed</span>' : ''}
            </div>
        </div>
        <ol class="timeline">${turns.map((turn, index) => renderTurn(turn, index, names)).join('')}</ol>`;
}
