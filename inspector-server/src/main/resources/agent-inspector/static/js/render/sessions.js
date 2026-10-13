import {escapeHtml, formatDuration, formatTime, formatTokens, statusClass} from '../util.js';
import {renderAnswer, renderMessage, toolNames} from './conversation.js';
import {renderWire} from './wire.js';

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

function renderTurn(turn, index, names, executions) {
    const exchange = turn.exchange;
    const options = {names, executions};
    const answer = turn.output
        ? renderAnswer(turn.output, {...options, streamed: exchange.streamed, key: `${exchange.id}:answer`})
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
                <span class="spacer"></span>
                <button type="button" class="copy-curl" data-exchange-id="${escapeHtml(exchange.id)}">Copy as cURL</button>
            </div>
            ${renderWire(exchange.id, null, exchange)}
            ${turn.resent ? `<div class="resent-note muted">↺ ${plural(turn.resent, 'earlier message')} re-sent</div>` : ''}
            ${turn.newMessages.map((message, i) => renderMessage(message, {
                ...options, extraClass: turn.resent ? 'new' : '', key: `${exchange.id}:msg:${turn.resent + i}`,
            })).join('')}
            ${answer}
        </li>`;
}

/** Spring AI's last advisor, the one that sends the prompt to the model (named "call" or "stream"). */
const MODEL_ADVISOR_ORDER = 2147483647;

const messageKey = message => `${message.role}\u0000${message.text}`;

/** Messages in {@code after} that are not in {@code before}, counting duplicates. */
function added(after, before) {
    const remaining = new Map();
    before.forEach(message => remaining.set(messageKey(message), (remaining.get(messageKey(message)) ?? 0) + 1));
    return after.filter(message => {
        const count = remaining.get(messageKey(message)) ?? 0;
        remaining.set(messageKey(message), count - 1);
        return count <= 0;
    });
}

function renderPlainMessage(message, extraClass = '') {
    return `<div class="message role-${escapeHtml(message.role)} ${extraClass}">
                <div class="message-role">${escapeHtml(message.role)}</div>
                <div class="part-text">${escapeHtml(message.text)}</div>
            </div>`;
}

/**
 * One hop through the advisor chain. What changed since the previous hop was added by the
 * nearest advisor before it in the chain (lower order): an advisor that loops, like the
 * tool-calling one, calls the rest of the chain again with more messages.
 */
function renderAdvisorStep(step, index, steps, call, modelCalls) {
    const before = index === 0 ? call.messages : steps[index - 1].messages;
    const newMessages = added(step.messages, before);
    const by = steps.slice(0, index).reverse().find(other => other.order < step.order)?.name;
    const isModel = step.order === MODEL_ADVISOR_ORDER;
    const label = isModel ? `${escapeHtml(step.name)} → model (round-trip ${modelCalls.get(step)})` : escapeHtml(step.name);
    return `
        <li class="advisor ${isModel ? 'model' : ''}">
            <div class="advisor-head">
                <b>${label}</b>
                <span class="muted">order ${step.order} · ${formatDuration(step.durationMs)} · receives ${plural(step.messages.length, 'message')}</span>
            </div>
            ${newMessages.length ? `
                <div class="advisor-added muted">+ ${plural(newMessages.length, 'message')} added${by ? ` by ${escapeHtml(by)}` : ''}</div>
                ${newMessages.map(message => renderPlainMessage(message, 'new')).join('')}` : ''}
        </li>`;
}

/** The Spring AI side of a ChatClient call: the prompt as the app wrote it and the advisor chain. */
function renderChatClientCall(call) {
    let round = 0;
    const modelCalls = new Map(call.advisors.filter(step => step.order === MODEL_ADVISOR_ORDER).map(step => [step, ++round]));
    return `
        <li class="turn layer">
            <div class="turn-head">
                <span class="layer-title">Spring AI · ChatClient call</span>
                <span class="badge">${escapeHtml(call.app)}</span>
                <span class="badge">${formatDuration(call.durationMs)}</span>
                ${call.stream ? '<span class="badge">stream</span>' : ''}
                ${call.parentId ? `<span class="badge">inside call ${escapeHtml(call.parentId)}</span>` : ''}
                ${call.error ? `<span class="badge status-bad">${escapeHtml(call.error)}</span>` : ''}
            </div>
            <details class="app-prompt" data-key="${escapeHtml(call.id)}:prompt">
                <summary>Prompt as the app wrote it (${plural(call.messages.length, 'message')})</summary>
                ${call.messages.map(message => renderPlainMessage(message)).join('')}
            </details>
            <ol class="advisors">${call.advisors.map((step, index) => renderAdvisorStep(step, index, call.advisors, call, modelCalls)).join('')}</ol>
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
        <ol class="timeline">${renderTimeline(session, names)}</ol>`;
}

/** Round-trips in order, each ChatClient call's panel placed before the first round-trip it made. */
function renderTimeline(session, names) {
    const calls = new Map((session.chatClientCalls ?? []).map(call => [call.id, call]));
    let previousCall = null;
    return session.turns.map((turn, index) => {
        const call = turn.chatClientCallId !== previousCall ? calls.get(turn.chatClientCallId) : null;
        previousCall = turn.chatClientCallId;
        return (call ? renderChatClientCall(call) : '') + renderTurn(turn, index, names, session.toolExecutions ?? {});
    }).join('');
}
