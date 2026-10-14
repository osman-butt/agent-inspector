import {escapeHtml, formatDuration, formatTokens} from '../util.js';

/**
 * A session at a glance, worked out from the session detail: what the user asked, what the
 * model did in each round-trip, and how the tools it called went.
 */

const PREVIEW_LENGTH = 120;

function preview(text) {
    const flat = text.replace(/\s+/g, ' ').trim();
    return flat.length > PREVIEW_LENGTH ? `${flat.slice(0, PREVIEW_LENGTH)}…` : flat;
}

function textOf(message) {
    return message.parts.filter(part => part.type === 'text' && part.text).map(part => part.text).join('\n');
}

/** Anthropic sends tool results as user messages; they are not something the user said. */
const isToolResults = message => message.parts.length > 0 && message.parts.every(part => part.type === 'tool_result');

const saidByUser = message => message.role === 'user' && !isToolResults(message) && textOf(message) !== '';

function modelStep(turn, index) {
    const exchange = turn.exchange;
    const step = {
        kind: 'model', turn: index, calls: [], text: '',
        durationMs: exchange.durationMs, inputTokens: exchange.inputTokens, outputTokens: exchange.outputTokens,
    };
    if (turn.output) {
        step.calls = turn.output.parts.filter(part => part.type === 'tool_call').map(part => part.name);
        step.text = preview(textOf(turn.output));
        step.state = 'ok';
    } else if (turn.error) {
        step.state = 'failed';
        step.error = preview(turn.error);
    } else {
        step.state = exchange.status == null ? 'waiting' : 'empty';
    }
    return step;
}

/** How each tool the model called went: from the starter's report, else from the result sent back. */
function toolsStep(turn, index, session, results) {
    const executions = session.toolExecutions ?? {};
    const isLast = index === session.turns.length - 1;
    const tools = turn.output.parts.filter(part => part.type === 'tool_call').map(call => {
        const tool = {id: call.id, name: call.name};
        const execution = executions[call.id];
        const result = results.get(call.id);
        if (execution) {
            tool.state = execution.error ? 'failed' : 'ok';
            tool.durationMs = execution.durationMs;
            tool.error = execution.error;
        } else if (result) {
            tool.state = result.error ? 'failed' : 'ok';
        } else {
            tool.state = isLast ? 'running' : 'unknown';
        }
        return tool;
    });
    return {kind: 'tools', turn: index, tools};
}

export function sessionSteps(session) {
    const results = new Map();
    for (const turn of session.turns) {
        for (const message of turn.newMessages) {
            message.parts.filter(part => part.type === 'tool_result').forEach(part => results.set(part.toolCallId, part));
        }
    }
    const steps = [];
    session.turns.forEach((turn, index) => {
        let messages = turn.newMessages;
        if (index === 0) {
            // With chat memory the earlier turns of a conversation come first: they are context, not the question.
            const question = messages.findLastIndex(saidByUser);
            const history = messages.slice(0, Math.max(question, 0)).filter(message => message.role !== 'system').length;
            if (history) {
                steps.push({kind: 'history', turn: index, count: history});
            }
            messages = question === -1 ? [] : messages.slice(question);
        }
        messages.filter(saidByUser).forEach(message => steps.push({kind: 'user', turn: index, text: preview(textOf(message))}));
        const model = modelStep(turn, index);
        steps.push(model);
        if (model.calls.length) {
            steps.push(toolsStep(turn, index, session, results));
        }
    });
    return steps;
}

const TOOL_MARK = {ok: '✓', failed: '✗', running: '…', unknown: '?'};

function renderTool(tool) {
    const title = tool.error ? ` title="${escapeHtml(tool.error)}"` : tool.state === 'running' ? ' title="running…"' : '';
    return `
        <button type="button" class="step-tool ${tool.state}" data-tool-call-id="${escapeHtml(tool.id ?? '')}"${title}>
            ${TOOL_MARK[tool.state]} ${escapeHtml(tool.name)}
            ${tool.durationMs != null ? `<span class="muted">${formatDuration(tool.durationMs)}</span>` : ''}
        </button>`;
}

function describe(step) {
    switch (step.kind) {
        case 'history':
            return `<span class="muted">↺ ${step.count} earlier message${step.count === 1 ? '' : 's'} re-sent as context</span>`;
        case 'user':
            return escapeHtml(step.text);
        case 'model':
            if (step.state === 'failed') {
                return `<span class="status-bad">failed: ${escapeHtml(step.error)}</span>`;
            }
            if (step.state !== 'ok') {
                return `<span class="muted">${step.state === 'waiting' ? 'waiting…' : '(no answer)'}</span>`;
            }
            if (step.calls.length) {
                const calls = `calls ${step.calls.map(name => `<b>${escapeHtml(name)}</b>`).join(', ')}`;
                return step.text ? `${calls} <span class="muted">“${escapeHtml(step.text)}”</span>` : calls;
            }
            return `“${escapeHtml(step.text)}”`;
        case 'tools':
            return step.tools.map(renderTool).join('');
    }
}

function meta(step) {
    if (step.kind !== 'model') {
        return '';
    }
    return [formatDuration(step.durationMs), formatTokens(step.inputTokens, step.outputTokens)].filter(Boolean).join(' · ');
}

export function renderSteps(steps) {
    if (!steps.length) {
        return '';
    }
    return `
        <div class="section">
            <h3>Steps</h3>
            <ol class="steps">
                ${steps.map(step => `
                    <li class="step step-${step.kind}" data-scroll-turn="${step.turn}" title="Show round-trip ${step.turn + 1}">
                        <span class="step-kind">${step.kind === 'history' ? 'context' : step.kind}</span>
                        <span class="step-text">${describe(step)}</span>
                        <span class="step-meta muted">${meta(step)}</span>
                    </li>`).join('')}
            </ol>
        </div>`;
}
