export function escapeHtml(text) {
    return String(text ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;');
}

const JSON_TOKEN = /("(\\u[a-fA-F0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(\.\d+)?([eE][+-]?\d+)?)/g;

/** Pretty-printed, syntax-highlighted JSON as HTML. */
export function highlightJson(value, indent = 2) {
    // Quotes stay unescaped so the token regex can find strings; the result goes inside <pre>.
    const json = JSON.stringify(value, null, indent)
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;');
    return json.replace(JSON_TOKEN, token => {
        let type = 'num';
        if (token.startsWith('"')) {
            type = token.endsWith(':') ? 'key' : 'str';
        } else if (token === 'true' || token === 'false') {
            type = 'bool';
        } else if (token === 'null') {
            type = 'null';
        }
        return `<span class="j-${type}">${token}</span>`;
    });
}

export function tryParseJson(text) {
    try {
        return JSON.parse(text);
    } catch {
        return undefined;
    }
}

/** A request or response body as HTML: JSON is highlighted, SSE streams are shown event by event. */
export function formatBody(text) {
    if (text == null || text === '') {
        return '<span class="muted">(empty)</span>';
    }
    const json = tryParseJson(text);
    if (json !== undefined) {
        return highlightJson(json);
    }
    if (/^(data|event):/m.test(text)) {
        return text.split('\n').map(line => {
            const match = line.match(/^(\w+):\s?(.*)$/);
            if (!match) {
                return escapeHtml(line);
            }
            const data = tryParseJson(match[2]);
            const value = data === undefined ? escapeHtml(match[2]) : highlightJson(data, 0);
            return `<span class="sse-field">${match[1]}:</span> ${value}`;
        }).join('\n');
    }
    return escapeHtml(text);
}

export function formatDuration(ms) {
    if (ms == null) {
        return '';
    }
    return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`;
}

export function formatBytes(bytes) {
    if (bytes == null) {
        return '';
    }
    if (bytes < 1024) {
        return `${bytes} B`;
    }
    return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function formatTime(iso) {
    return new Date(iso).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit', second: '2-digit'});
}

export function formatTokens(input, output) {
    if (input == null && output == null) {
        return '';
    }
    return `${input ?? '?'} → ${output ?? '?'} tok`;
}

/** A 200 can still carry an error, e.g. a stream that broke off halfway. */
export function statusClass(status, error) {
    if (status == null && !error) {
        return 'status-pending';
    }
    if (error && (status == null || status < 400)) {
        return 'status-bad';
    }
    if (status < 300) {
        return 'status-ok';
    }
    return status < 500 ? 'status-warn' : 'status-bad';
}
