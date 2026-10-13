// Run with: node --test inspector-server/src/test/js/
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {isTruncated, toCurl} from '../../main/resources/agent-inspector/static/js/curl.js';

const exchange = (overrides = {}) => ({
    route: 'dmr',
    request: {
        method: 'POST',
        url: 'http://localhost:12434/engines/v1/chat/completions?api-version=1',
        path: '/chat/completions',
        headers: {
            'authorization': 'Bearer …(redacted)',
            'content-type': 'application/json',
            'user-agent': 'spring-ai-openai',
            'x-stainless-lang': 'java',
            'x-inspector-session': 'run-1',
        },
        body: '{"messages":[{"role":"user","content":"It\'s $HOME, isn\'t it?"}]}',
        ...overrides,
    },
});

/** The arguments curl would receive, as bash parses the command, with OPENAI_API_KEY set. */
function curlArguments(command) {
    const result = spawnSync('bash', ['-c', `curl() { printf '%s\\0' "$@"; }\n${command}`], {
        env: {...process.env, OPENAI_API_KEY: 'test-key'},
        encoding: 'utf8',
    });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.split('\0').slice(0, -1);
}

test('goes through the proxy route, keeping the query', () => {
    const args = curlArguments(toCurl(exchange(), 'http://localhost:7070'));
    assert.equal(args[0], 'http://localhost:7070/proxy/dmr/chat/completions?api-version=1');
});

test('passes the body through the shell unchanged', () => {
    const args = curlArguments(toCurl(exchange(), 'http://localhost:7070'));
    assert.equal(args[args.indexOf('--data-raw') + 1], exchange().request.body);
    assert.ok(!args.includes('-X'), 'POST with a body is curl\'s default');
});

test('reads the API key from the environment instead of the redacted value', () => {
    const command = toCurl(exchange(), 'http://localhost:7070');
    assert.ok(!command.includes('redacted'));
    assert.ok(curlArguments(command).includes('authorization: Bearer test-key'));
});

test('drops headers that only add noise', () => {
    const args = curlArguments(toCurl(exchange(), 'http://localhost:7070'));
    assert.ok(args.includes('content-type: application/json'));
    assert.ok(!args.some(arg => /user-agent|x-stainless|x-inspector-session/.test(arg)));
});

test('adds -X for other methods and -N for streams', () => {
    const get = toCurl(exchange({method: 'GET', body: null, url: 'http://x/v1/models', path: '/models'}), 'http://i');
    assert.ok(!get.includes('-X') && !get.includes('--data-raw'));
    const del = toCurl(exchange({method: 'DELETE', body: null}), 'http://i');
    assert.ok(del.includes('-X DELETE'));
    const streamed = toCurl({...exchange(), parsed: {streamed: true}}, 'http://i');
    assert.ok(streamed.includes('-N'));
});

test('notices bodies cut off at the size limit', () => {
    assert.equal(isTruncated(exchange({body: '{"a":\n…[truncated after 5 bytes]'})), true);
    assert.equal(isTruncated(exchange()), false);
});
