import {escapeHtml, highlightJson, tryParseJson} from '../util.js';

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

function renderPart(part, names) {
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
                </div>`;
        case 'tool_result':
            return `
                <div class="tool-card result ${part.error ? 'failed' : ''}">
                    <div class="tool-head"><span class="tool-arrow">←</span> result of
                        <b>${escapeHtml(names.get(part.toolCallId) ?? 'tool')}</b>
                        ${part.error ? '<span class="badge status-bad">error</span>' : ''}
                        <span class="muted tool-id">${escapeHtml(part.toolCallId ?? '')}</span></div>
                    <pre>${jsonOrText(part.content ?? '')}</pre>
                </div>`;
        default:
            return `<div class="muted">[${escapeHtml(part.label ?? part.type)}]</div>`;
    }
}

export function renderMessage(message, names, extraClass = '') {
    const parts = message.parts.length
        ? message.parts.map(part => renderPart(part, names)).join('')
        : '<div class="muted">(empty)</div>';
    const role = escapeHtml(message.role);
    // System prompts are often long and the same every time: fold them, with a preview.
    if (message.role === 'system') {
        const text = message.parts.find(part => part.type === 'text')?.text ?? '';
        const preview = text.length > 90 ? `${text.slice(0, 90)}…` : text;
        return `
            <details class="message role-system ${extraClass}">
                <summary><span class="message-role">system</span> <span class="muted">${escapeHtml(preview)}</span></summary>
                ${parts}
            </details>`;
    }
    return `<div class="message role-${role} ${extraClass}"><div class="message-role">${role}</div>${parts}</div>`;
}

function renderTools(tools) {
    if (!tools.length) {
        return '';
    }
    return `
        <details class="section tools-offered">
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
function renderSent(messages, newFrom, names) {
    if (!newFrom) {
        return messages.map(message => renderMessage(message, names)).join('');
    }
    return `
        <details class="resent">
            <summary>↺ ${newFrom} message${newFrom === 1 ? '' : 's'} re-sent from earlier round-trips</summary>
            ${messages.slice(0, newFrom).map(message => renderMessage(message, names)).join('')}
        </details>
        ${messages.slice(newFrom).map(message => renderMessage(message, names, 'new')).join('')}`;
}

export function renderConversation(exchange) {
    const parsed = exchange.parsed;
    const names = toolNames([...parsed.messages, parsed.output]);
    const answer = parsed.output
        ? renderMessage(parsed.output, names, 'answer')
        : parsed.error
            ? `<div class="box error"><pre>${escapeHtml(parsed.error)}</pre></div>`
            : '<p class="muted">Waiting for the model…</p>';
    return `
        ${renderTools(parsed.tools)}
        <div class="section">
            <h3>Sent to the model (${parsed.messages.length} messages)</h3>
            ${renderSent(parsed.messages, exchange.session?.newFrom ?? 0, names)}
        </div>
        <div class="section">
            <h3>Model answer${parsed.finishReason ? ` · <span class="finish">${escapeHtml(parsed.finishReason)}</span>` : ''}</h3>
            ${answer}
        </div>`;
}
