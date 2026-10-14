// Run with: node --test 'inspector-server/src/test/js/*.test.mjs'
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {renderSteps, sessionSteps} from '../../main/resources/agent-inspector/static/js/render/steps.js';

const text = value => ({type: 'text', text: value});
const toolCall = (id, name) => ({type: 'tool_call', id, name, arguments: '{}'});
const toolResult = (toolCallId, content, error = false) => ({type: 'tool_result', toolCallId, content, error});
const message = (role, ...parts) => ({role, parts});

const turn = ({newMessages, output = null, error = null, status = 200, durationMs = 1200, resent = 0}) => ({
    exchange: {id: `x${Math.random()}`, status, durationMs, inputTokens: 500, outputTokens: 30},
    resent, newMessages, output, error, finishReason: null, chatClientCallId: null,
});

/** The sample's weather question: the model calls two tools, then answers. */
const toolLoop = (overrides = {}) => ({
    summary: {},
    turns: [
        turn({
            newMessages: [message('system', text('You are a helpful assistant.')), message('user', text('Weather in Copenhagen?'))],
            output: message('assistant', toolCall('call_1', 'weather'), toolCall('call_2', 'currentTime')),
        }),
        turn({
            resent: 3, durationMs: 800,
            newMessages: [message('tool', toolResult('call_1', '{"temp":1}')), message('tool', toolResult('call_2', '"14:05"'))],
            output: message('assistant', text('Windy,   1 °C\nand 14:05 there.')),
        }),
    ],
    toolExecutions: {
        call_1: {toolCallId: 'call_1', name: 'weather', durationMs: 3, error: null},
        call_2: {toolCallId: 'call_2', name: 'currentTime', durationMs: 1, error: null},
    },
    mcpTools: {weather: 'weather-server'},
    ...overrides,
});

test('a tool loop reads as question, tool calls, tool runs and answer', () => {
    const steps = sessionSteps(toolLoop());

    assert.deepEqual(steps.map(step => step.kind), ['user', 'model', 'tools', 'model']);
    assert.equal(steps[0].text, 'Weather in Copenhagen?');
    assert.deepEqual(steps[1].calls, ['weather', 'currentTime']);
    assert.equal(steps[1].durationMs, 1200);
    assert.deepEqual(steps[2].tools.map(tool => [tool.name, tool.state, tool.durationMs, tool.mcp]),
        [['weather', 'ok', 3, 'weather-server'], ['currentTime', 'ok', 1, null]]);
    assert.equal(steps[3].text, 'Windy, 1 °C and 14:05 there.');
    assert.equal(steps[3].turn, 1);
});

test('earlier turns re-sent from chat memory are one context step, not questions', () => {
    const session = toolLoop();
    session.turns[0].newMessages = [
        message('system', text('You are a helpful assistant.')),
        message('user', text('Weather in Copenhagen?')),
        message('assistant', text('Windy, 1 °C.')),
        message('user', text('And in Oslo?')),
    ];

    const steps = sessionSteps(session);

    assert.deepEqual(steps.slice(0, 2), [{kind: 'history', turn: 0, count: 2}, {kind: 'user', turn: 0, text: 'And in Oslo?'}]);
});

test('Anthropic tool results arrive as user messages and are not questions', () => {
    const session = toolLoop();
    session.turns[1].newMessages = [message('user', toolResult('call_1', 'windy'), toolResult('call_2', '14:05'))];

    assert.deepEqual(sessionSteps(session).map(step => step.kind), ['user', 'model', 'tools', 'model']);
});

test('without the starter, tool state comes from the results sent back to the model', () => {
    const session = toolLoop({toolExecutions: {}, mcpTools: undefined});
    session.turns[1].newMessages[0] = message('tool', toolResult('call_1', 'Unknown city', true));

    const tools = sessionSteps(session)[2].tools;

    assert.deepEqual(tools.map(tool => [tool.state, tool.durationMs, tool.mcp]), [['failed', undefined, null], ['ok', undefined, null]]);
});

test('a session still running shows the model waiting and its tools running', () => {
    // The model asked for tools; the round-trip with their results hasn't happened yet.
    const session = toolLoop({toolExecutions: {}});
    session.turns = [session.turns[0]];

    const steps = sessionSteps(session);

    assert.deepEqual(steps.map(step => step.kind), ['user', 'model', 'tools']);
    assert.deepEqual(steps[2].tools.map(tool => tool.state), ['running', 'running']);

    const waiting = sessionSteps({...session, turns: [turn({newMessages: [message('user', text('Hi'))], status: null})]});
    assert.equal(waiting[1].state, 'waiting');
});

test('a failed round-trip says why', () => {
    const steps = sessionSteps({turns: [turn({newMessages: [message('user', text('Hi'))], error: 'HTTP 500: model overloaded'})]});

    assert.equal(steps[1].state, 'failed');
    assert.equal(steps[1].error, 'HTTP 500: model overloaded');
});

test('the rendered steps escape what the model and user wrote and label MCP tools', () => {
    const session = toolLoop();
    session.turns[0].newMessages[1] = message('user', text('<script>alert(1)</script>'));

    const html = renderSteps(sessionSteps(session));

    assert.ok(!html.includes('<script>'));
    assert.ok(html.includes('&lt;script&gt;'));
    assert.ok(html.includes('MCP · weather-server'));
    assert.ok(html.includes('data-scroll-turn="1"'));
});
