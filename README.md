# Agent Inspector

See what your AI apps do under the hood: the exact requests and responses sent to the model,
tool calls and their results, token usage and timing, how one agent run spreads over several
round-trips, and, for Spring AI apps, what each advisor adds to the prompt.

It works with **OpenAI**, **Claude (Anthropic)** and **Docker Model Runner** (and other
OpenAI-compatible servers). Built with Spring Boot 4.1, Spring AI 2.0 and Java 25.

```
your app ──(base-url = http://localhost:7070/proxy/<route>)──► inspector ──► OpenAI / Anthropic / Docker Model Runner
   │                                                              │
   └─ starter (optional): routes the base-url automatically,      ├─ records each round-trip, parses it, groups sessions
      reports tool runs and ChatClient/advisor activity ─────────►└─ web UI at http://localhost:7070, updated live
```

## Quick start

```bash
./mvnw install                                  # builds everything, including the starter
./mvnw -pl inspector-server spring-boot:run     # the inspector, on http://localhost:7070
```

Try it with the sample app, a Spring AI app with a clock tool, a weather tool and chat memory,
talking to Docker Model Runner (`docker model pull ai/qwen3:4b-instruct-2507-q4_K_M`):

```bash
./mvnw -pl inspector-sample spring-boot:run
curl 'localhost:8081/ask?q=What%20is%20the%20weather%20in%20Copenhagen?&conversation=trip'
curl 'localhost:8081/ask?q=And%20in%20Oslo?&conversation=trip'
curl 'localhost:8081/ask/stream?q=What%20time%20is%20it%20in%20Tokyo?'
```

Open http://localhost:7070. **Sessions** lists each agent run; open one to see its timeline:

- **Spring AI panel**: the prompt as the app wrote it, then every advisor it passed through and
  the messages each one added (chat memory inserting earlier turns, the tool-calling advisor
  sending tool results back).
- **Round-trips**: what was sent to the model (messages re-sent from earlier round-trips are
  folded away), the tools offered, the model's answer, tool calls with their arguments and how
  long each tool ran, finish reason, tokens and latency.
- **The JSON on the wire**, folded until you click: every message has a *JSON* fold with the message
  exactly as it was sent or received, and every round-trip has *Request JSON* and *Response JSON*
  folds with the full bodies (streamed answers event by event). Folds stay open while new traffic
  comes in.
- **Copy as cURL** puts the call on your clipboard as a `curl` command. It goes through the
  inspector, so re-running it is recorded too, and it reads the API key from `$OPENAI_API_KEY`
  or `$ANTHROPIC_API_KEY`, because the recorded key is redacted.
- **Round-trip ↗** opens one call with its raw request and response, headers included
  (API keys redacted).

**Calls** lists every HTTP call through the proxy, including ones the inspector can't parse.

## Use it from your own apps

### Option 1: point the base-url at the proxy (any app, any language)

Change only the model base URL. The inspector forwards every call and records it.

| Your app talks to | Set its base URL to | Forwards to |
|---|---|---|
| Docker Model Runner | `http://localhost:7070/proxy/dmr` | `http://localhost:12434/engines/v1` |
| OpenAI | `http://localhost:7070/proxy/openai` | `https://api.openai.com/v1` |
| Anthropic | `http://localhost:7070/proxy/anthropic` | `https://api.anthropic.com` |

For a Spring AI app that's one property, e.g. `spring.ai.openai.base-url=http://localhost:7070/proxy/dmr`
or `spring.ai.anthropic.base-url=http://localhost:7070/proxy/anthropic`. Other clients usually
read `OPENAI_BASE_URL` or `ANTHROPIC_BASE_URL`:

```bash
curl http://localhost:7070/proxy/dmr/chat/completions -H 'Content-Type: application/json' \
  -d '{"model": "ai/qwen3:4b-instruct-2507-q4_K_M", "messages": [{"role": "user", "content": "Hi"}]}'
```

Add your own routes in `inspector-server/src/main/resources/application.properties`
(`agent.inspector.routes.<name>=<upstream base URL>`); the path after `/proxy/<name>` is appended
to the upstream URL.

### Option 2: add the starter (Spring AI apps)

```xml
<dependency>
    <groupId>ek.osnb</groupId>
    <artifactId>agent-inspector-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Nothing else changes in the app. When the app starts and the inspector is running, the starter:

- registers a route `<spring.application.name>-<provider>` for each Spring AI provider on the
  classpath (OpenAI, which also covers Docker Model Runner, and Anthropic). The route forwards to
  the base-url the app was configured with, and the starter points the base-url at that route.
  Azure OpenAI and GitHub Models are left alone, because Spring AI recognizes them by their URL;
- reports every tool execution: duration, result, and whether the tool threw. Spring AI turns a
  tool's exception into a result for the model, so without this a failed tool looks like any
  other answer;
- reports every `ChatClient` call with its advisor chain, from the observations Spring AI already
  emits. No advisor is added to your app.

If the inspector isn't running, the app talks to the model directly, as before.

| Property | Default | |
|---|---|---|
| `agent.inspector.enabled` | `true` | `false` to opt out, e.g. in tests: `@SpringBootTest(properties = "agent.inspector.enabled=false")` |
| `agent.inspector.url` | `http://localhost:7070` | where the inspector runs |

**Apps that build their own `ToolCallingManager`** (like `spring-ai-harness-demo`) must hand it the
observation registry, or tool runs aren't reported. Their model traffic is recorded either way:

```java
ToolCallingManager.builder()
        .observationRegistry(observationRegistry)   // inject io.micrometer.observation.ObservationRegistry
        .build();
```

Apps that call `ChatModel` directly instead of `ChatClient` don't get the Spring AI panel; there
are no advisors to show.

## Sessions

Model APIs are stateless, so an agent re-sends the whole conversation on every call. A request
whose messages start with an earlier round-trip's messages plus that round-trip's answer continues
the same session. To group calls yourself, send an `X-Inspector-Session: <id>` header; the proxy
records it and doesn't forward it.

## Inspector settings

| Property | Default | |
|---|---|---|
| `server.port` | `7070` | |
| `server.address` | `127.0.0.1` | local only: captured traffic contains prompts and tool results |
| `agent.inspector.routes.<name>` | `openai`, `anthropic`, `dmr` | upstream base URL per proxy route |
| `agent.inspector.max-exchanges` | `500` | round-trips kept in memory, oldest dropped first |
| `agent.inspector.max-body-size` | `5MB` | longer bodies are stored truncated (but forwarded in full) |

## REST API

| | |
|---|---|
| `GET /api/sessions`, `GET /api/sessions/{id}` | sessions and their timelines |
| `GET /api/exchanges`, `GET /api/exchanges/{id}`, `DELETE /api/exchanges` | raw and parsed round-trips; delete clears everything |
| `GET /api/stream` | server-sent events: `exchange`, `session`, `tool`, `chat-client-call`, `cleared` |
| `GET /api/ping`, `GET`/`POST /api/routes` | used by the starter |
| `POST /api/tool-executions`, `POST /api/chat-client-calls` | reports from the starter |

## Modules

| Module | |
|---|---|
| `inspector-server` | the inspector: recording proxy, parsers for the OpenAI and Anthropic wire formats, sessions, REST API and the web UI (plain HTML, CSS and ES modules in `src/main/resources/static`, no build step) |
| `inspector-spring-boot-starter` | the optional starter for Spring AI apps |
| `inspector-sample` | a small Spring AI app to try it with |

Run all tests with `./mvnw verify`. The OpenAI parser tests use traffic recorded from Docker Model
Runner with the sample app. The UI's curl builder has its own tests: `node --test inspector-server/src/test/js/`.

## Limitations

- Everything is kept in memory and gone when the inspector restarts.
- Routes the starter registers live in the inspector too: after restarting the inspector, restart
  the apps that use the starter, or their model calls fail with 404 "Unknown route".
- Understood wire formats: OpenAI Chat Completions (`/chat/completions`) and Anthropic Messages
  (`/v1/messages`), with and without streaming. Everything else through the proxy (embeddings,
  the OpenAI Responses API, ...) is recorded and shown raw. The Anthropic parser is tested against
  the documented format, not yet against live traffic.
- A round-trip is matched to its ChatClient call by app and time. Two ChatClient calls running at
  the same time in the same app may be mixed up.
- Failed tools are recognized through the app's `ToolExecutionExceptionProcessor` bean. A
  `ToolCallingManager` built by hand uses Spring AI's default processor, so there a failing tool
  is reported with its error message as the result, not as a failure.
