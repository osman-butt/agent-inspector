/** Headers that only add noise to a hand-run request. */
const DROPPED = [/^x-stainless-/, /^user-agent$/, /^x-inspector-session$/];

/**
 * API keys are stored redacted, so the command reads them from the environment instead.
 * These headers are double-quoted so the shell expands the variable.
 */
const SECRETS = {
    'authorization': 'Bearer $OPENAI_API_KEY',
    'x-api-key': '$ANTHROPIC_API_KEY',
    'api-key': '$API_KEY',
    'x-goog-api-key': '$GOOGLE_API_KEY',
    'proxy-authorization': '$PROXY_AUTHORIZATION',
};

/** Single-quoted for the shell: nothing inside is expanded, a ' becomes '\''. */
const quote = text => `'${String(text).replaceAll("'", `'\\''`)}'`;

/**
 * The recorded request as a curl command. It goes through the inspector's proxy route
 * (at {@code origin}), so running it again is recorded too.
 */
export function toCurl(exchange, origin) {
    const request = exchange.request;
    const search = new URL(request.url).search;
    const args = [`curl ${quote(`${origin}/proxy/${exchange.route}${request.path}${search}`)}`];

    const hasBody = request.body != null && request.body !== '';
    if (!(request.method === 'POST' && hasBody) && !(request.method === 'GET' && !hasBody)) {
        args.push(`-X ${request.method}`);
    }
    if (exchange.parsed?.streamed) {
        args.push('-N'); // print streamed events as they arrive
    }
    for (const [name, value] of Object.entries(request.headers ?? {})) {
        if (DROPPED.some(pattern => pattern.test(name))) {
            continue;
        }
        args.push(SECRETS[name] ? `-H "${name}: ${SECRETS[name]}"` : `-H ${quote(`${name}: ${value}`)}`);
    }
    if (hasBody) {
        args.push(`--data-raw ${quote(request.body)}`);
    }
    return args.join(' \\\n  ');
}

/** True when the stored body was cut off at the inspector's size limit, so the command would send it incomplete. */
export function isTruncated(exchange) {
    return exchange.request.body?.includes('…[truncated after ') ?? false;
}
