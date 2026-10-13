# Agent Inspector

See what your Spring AI app sends to the model and what comes back: requests, responses, tool
calls, tokens and timing, live in your browser. Works with OpenAI, Claude (Anthropic) and Docker
Model Runner.

## Use it in your app

**1. Add the starter** to your `pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>io.github.osman-butt</groupId>
        <artifactId>agent-inspector-spring-boot-starter</artifactId>
        <version>0.1.0</version>
    </dependency>
</dependencies>

<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/osman-butt/agent-inspector-starter</url>
    </repository>
</repositories>
```

**2. Log in to GitHub Packages** once, in `~/.m2/settings.xml`, with a GitHub personal access token
(classic) that has the `read:packages` scope:

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>your-github-username</username>
            <password>your-token</password>
        </server>
    </servers>
</settings>
```

**3. Run your app** and open http://localhost:7070. The app's log shows the address:

```
Agent Inspector UI: http://localhost:7070 (running in this app)
```

That's it. Nothing else in your app changes.

## What you see

- **Sessions**: each question your app handles, with every round-trip to the model.
- **Messages and tool calls**: what was sent, what the model answered, which tools it called and how
  long they ran.
- **Spring AI advisors**: what chat memory and other advisors added to your prompt.
- **The raw JSON** of every call, one click away, and **Copy as cURL** to run a call again.

## Settings

| Property | Default | |
|---|---|---|
| `agent.inspector.enabled` | `true` | `false` turns it off |
| `agent.inspector.url` | `http://localhost:7070` | where the inspector runs |
| `agent.inspector.embedded.enabled` | `true` | `false` to only use an inspector that is already running |

## Good to know

- It is for development: leave it out of production, or set `agent.inspector.enabled=false` there.
- A command-line app should set `spring.main.web-application-type=none` (a WebFlux app `reactive`),
  because the starter brings a web server for the inspector.
- If you build your own `ToolCallingManager`, pass it `.observationRegistry(observationRegistry)` to
  see how long tools take.
