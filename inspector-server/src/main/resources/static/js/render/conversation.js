import {escapeHtml, formatDuration, highlightJson, tryParseJson} from '../util.js';
import {renderWire} from './wire.js';

/** Tool call id -> tool name, so a result can say which tool it answers. */
export function toolNames(messages) {
    const names = new Map();
    for (const message of messages.filter(Boolean)) {
        for (const part of message.parts) {
            if (part.type === 'tool_call') {
                names.set(part.id, part.name);
            }
        }
    }
    return names;
}

/** JSON (like tool arguments and results) pretty-printed, anything else as plain text. */
function jsonOrText(text) {
    const json = tryParseJson(text);
    return json !== undefined && typeof json === 'object' ? highlightJson(json) : escapeHtml(text);
}

/** What the app reported about running a tool (needs the starter): duration, failure, result. */
function renderExecution(execution) {
    if (!execution) {
        return '';
    }
    const ran = `ran ${escapeHtml(formatDuration(execution.durationMs))} in ${escapeHtml(execution.app ?? 'the app')}`;
    return execution.error
        ? `<div class="tool-exec failed">✗ ${ran}, failed: ${escapeHtml(execution.error)}</div>`
        : `<div class="tool-exec">✓ ${ran}</div>`;
}

function renderPart(part, names, executions) {
    switch (part.type) {
        case 'text':
            return `<div class="part-text">${escapeHtml(part.text)}</div>`;
        case 'thinking':
            return `<details class="part-thinking"><summary>Thinking</summary><div class="part-text">${escapeHtml(part.text)}</div></details>`;
        case 'tool_call':
            return `
                <div class="tool-card call" data-tool-call-id="${escapeHtml(part.id)}">
                    <div class="tool-head"><span class="tool-arrow">→</span> calls <b>${escapeHtml(part.name)}</b>
                        <span class="muted tool-id">${escapeHtml(part.id ?? '')}</span></div>
                    <pre>${jsonOrText(part.arguments ?? '')}</pre>
                    ${renderExecution(executions[part.id])}
                </div>`;
        case 'tool_result': {
            const failed = part.error || executions[part.toolCallId]?.error;
            return `
                <div class="tool-card result ${failed ? 'failed' : ''}">
                    <div class="tool-head"><span class="tool-arrow">←</span> result of
                        <b>${escapeHtml(names.get(part.toolCallId) ?? 'tool')}</b>
                        ${failed ? '<span class="badge status-bad">error</span>' : ''}
                        <span class="muted tool-id">${escapeHtml(part.toolCallId ?? '')}</span></div>
                    <pre>${jsonOrText(part.content ?? '')}</pre>
                </div>`;
        }
        default:
            return `<div class="muted">[${escapeHtml(part.label ?? part.type)}]</div>`;
    }
}

/** The message exactly as it was on the wire, folded until clicked. */
function renderRawJson(message, key) {
    return message.raw == null ? '' : `
        <details class="raw-json" data-key="${escapeHtml(key)}:json">
            <summary>JSON</summary>
            <pre class="box">${highlightJson(message.raw)}</pre>
        </details>`;
}

/**
 * @param options.names      tool call id to tool name, for labelling results
 * @param options.executions tool executions reported by the starter, by tool call id
 * @param options.extraClass e.g. {@code answer} or {@code new}
 * @param options.key        stable id for the message's folds, so they stay open across live updates
 */
export function renderMessage(message, {names, executions = {}, extraClass = '', key}) {
    const parts = message.parts.length
        ? message.parts.map(part => renderPart(part, names, executions)).join('')
        : '<div class="muted">(empty)</div>';
    const role = escapeHtml(message.role);
    // System prompts are often long and the same every time: fold them, with a preview.
    if (message.role === 'system') {
        const text = message.parts.find(part => part.type === 'text')?.text ?? '';
        const preview = text.length > 90 ? `${text.slice(0, 90)}…` : text;
        return `
            <details class="message role-system ${extraClass}" data-key="${escapeHtml(key)}">
                <summary><span class="message-role">system</span> <span class="muted">${escapeHtml(preview)}</span></summary>
                ${parts}
                ${renderRawJson(message, key)}
            </details>`;
    }
    return `
        <div class="message role-${role} ${extraClass}">
            <div class="message-role">${role}</div>
            ${parts}
            ${renderRawJson(message, key)}
        </div>`;
}

/** A streamed answer is rebuilt from many events, so it has no JSON of its own to show. */
export function renderAnswer(output, {streamed, ...options}) {
    const note = streamed && output.raw == null
        ? '<div class="muted raw-note">Streamed: assembled from the events under “Response JSON”.</div>'
        : '';
    return renderMessage(output, {...options, extraClass: 'answer'}) + note;
}

function renderTools(tools, key) {
    if (!tools.length) {
        return '';
    }
    return `
        <details class="section tools-offered" data-key="${escapeHtml(key)}:tools">
            <summary><h3>Tools offered to the model (${tools.length})</h3></summary>
            ${tools.map(tool => `
                <div class="box tool-spec">
                    <b>${escapeHtml(tool.name)}</b>
                    <div class="muted">${escapeHtml(tool.description ?? '')}</div>
                    ${tool.schema ? `<pre>${highlightJson(tool.schema)}</pre>` : ''}
                </div>`).join('')}
        </details>`;
}

/** Messages already sent in earlier round-trips of the session are folded away, so the new ones stand out. */
function renderSent(messages, newFrom, options, key) {
    const message = (msg, index, extraClass = '') =>
        renderMessage(msg, {...options, extraClass, key: `${key}:msg:${index}`});
    if (!newFrom) {
        return messages.map((msg, index) => message(msg, index)).join('');
    }
    return `
        <details class="resent" data-key="${escapeHtml(key)}:resent">
            <summary>↺ ${newFrom} message${newFrom === 1 ? '' : 's'} re-sent from earlier round-trips</summary>
            ${messages.slice(0, newFrom).map((msg, index) => message(msg, index)).join('')}
        </details>
        ${messages.slice(newFrom).map((msg, index) => message(msg, newFrom + index, 'new')).join('')}`;
}

export function renderConversation(exchange) {
    const parsed = exchange.parsed;
    const options = {names: toolNames([...parsed.messages, parsed.output]), executions: exchange.toolExecutions ?? {}};
    const answer = parsed.output
        ? renderAnswer(parsed.output, {...options, streamed: parsed.streamed, key: `${exchange.id}:answer`})
        : parsed.error
            ? `<div class="box error"><pre>${escapeHtml(parsed.error)}</pre></div>`
            : '<p class="muted">Waiting for the model…</p>';
    return `
        ${renderWire(exchange.id, exchange)}
        ${renderTools(parsed.tools, exchange.id)}
        <div class="section">
            <h3>Sent to the model (${parsed.messages.length} messages)</h3>
            ${renderSent(parsed.messages, exchange.session?.newFrom ?? 0, options, exchange.id)}
        </div>
        <div class="section">
            <h3>Model answer${parsed.finishReason ? ` · <span class="finish">${escapeHtml(parsed.finishReason)}</span>` : ''}</h3>
            ${answer}
        </div>`;
}
