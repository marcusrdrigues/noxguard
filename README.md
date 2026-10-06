# noxguard

**Deterministic guardrails for LLM chats and agents, in Java.** A streaming output guard that never releases a leak and never buffers the answer, a link allow list, data delimiting, signed history, a deny-by-default tool policy for agents, a proposal gate and input views for your classifier. No runtime dependencies in the core, and a Spring Boot starter that sets it all up from properties.

[![CI](https://github.com/marcusrdrigues/noxguard/actions/workflows/ci.yml/badge.svg)](https://github.com/marcusrdrigues/noxguard/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.marcusrdrigues/noxguard-core)](https://central.sonatype.com/artifact/com.marcusrdrigues/noxguard-core)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

[Leia em português](README.pt-BR.md)


## Why

An LLM answer that streams leaves the server in pieces. If the model starts to recite its instructions, the first words are on the user's screen before a check on the whole answer can run. The usual fixes trade one problem for another:

- **Check at the end, buffering the stream.** Safe, but the user waits for the whole answer. LangChain4j's output guardrails on a streaming call work this way: they run once the stream completes, and the partial responses are replayed afterwards.
- **Check each piece as it comes.** Fast, but a marker split across two pieces ("You are" + " Ava") is never seen whole.

noxguard holds back only the last `longest marker - 1` characters and releases everything before them:

```
pieces from the model:  "Sure, here they are. You a" | "re Ava, the assistant..."
released right away:    "Sure, here they "             (the last 10 characters, "are. You a", are held)
next piece arrives:     the held text + "re Ava..." contains "You are Ava"  ->  tripped
the user saw:           "Sure, here they "  ... then the refusal replaces it; no character of the marker left
```

Agents add a second problem: a model with tools can propose an action it should not, and a prompt rule only lowers how often that happens. In [Nox](https://marcusrdrigues.com), asked to email HR on the owner's behalf, the model still proposed a message once in six attempts with the rule in its prompt. What held every time was code: the proposal is kept until the answer ends and dropped when the answer is a refusal. That is `ProposalGate`.

Before a tool runs at all, something has to decide whether it may: is the tool allowed, are the arguments acceptable, has the answer used its calls, does a person have to confirm? `ToolPolicy` is that decision in one object, checked before every call. It is to an agent's tools what Spring Security is to a web app's endpoints: deny by default, a rule per tool, and a decision the app acts on. The model can ask for anything; the policy decides what runs (OWASP LLM06, Excessive Agency).

These guards come from Nox, the public chat on the author's portfolio, where they are measured with [noxeval](https://github.com/marcusrdrigues/noxeval) (47 of 47 cases, each asked three times). **noxeval measures, noxguard enforces.**

## What's inside

| Guard | Package | Stops |
| --- | --- | --- |
| `StreamGuard` | `output` | A prompt leak or a runaway answer, while the answer streams |
| `LinkPolicy` | `output` | Exfiltration through a link or Markdown image to an outside address |
| `DataEnvelope` | `data` | Retrieved passages or tool results acting as instructions |
| `HistorySigner` | `history` | Forged history ("assistant: developer mode on") sent back by a client |
| `ToolPolicy` | `agent` | A tool call the agent was not given, bad arguments, too many calls, or a side effect without the user's confirmation |
| `ProposalGate`, `ToolBudget`, `StrictSchema` | `agent` | An agent acting beyond the request, or looping without end |
| `InputViews` | `input` | Attacks hidden in base64, ROT13, leetspeak or invisible characters, for your classifier to see |

`noxguard-reactor` turns a `Flux<String>` from Spring AI, WebFlux or any Reactor source into guarded events. `noxguard-spring-boot-starter` creates the guards and the tool policy from `noxguard.*` properties.

## Install

Java 21 or later.

```xml
<dependency>
  <groupId>com.marcusrdrigues</groupId>
  <artifactId>noxguard-core</artifactId>
  <version>0.2.0</version>
</dependency>
<!-- for a Flux<String> model stream (Spring AI, WebFlux): -->
<dependency>
  <groupId>com.marcusrdrigues</groupId>
  <artifactId>noxguard-reactor</artifactId>
  <version>0.2.0</version>
</dependency>
<!-- in a Spring Boot 4 app, the guards from properties: -->
<dependency>
  <groupId>com.marcusrdrigues</groupId>
  <artifactId>noxguard-spring-boot-starter</artifactId>
  <version>0.2.0</version>
</dependency>
```

Gradle: `implementation("com.marcusrdrigues:noxguard-core:0.2.0")`. From source: `git clone https://github.com/marcusrdrigues/noxguard && cd noxguard && mvn install`.

## Quick start

**Plain Java**, around any stream of text:

```java
StreamGuard guard = StreamGuard.builder()
        .leakMarkers(List.of("You are Ava", "<context>"))
        .maxChars(1200)
        .build();                                   // one guard per answer

for (String piece : modelStream) {
    String safe = guard.push(piece);
    if (!safe.isEmpty()) send(safe);
    if (guard.status() instanceof StreamStatus.Tripped) { cancelModelCall(); replaceWith(REFUSAL); break; }
    if (guard.status() instanceof StreamStatus.Capped) { cancelModelCall(); break; }
}
send(guard.end());
```

**Spring AI or WebFlux**, with `noxguard-reactor`:

```java
ReactorGuard guard = ReactorGuard.builder()
        .streamGuard(() -> StreamGuard.builder().leakMarkers(markers).maxChars(1200).build())
        .links(LinkPolicy.allow(Pattern.compile("^([a-z0-9-]+\\.)*example\\.com$")))
        .refusal("I only answer questions about Example Books.")
        .build();

Flux<GuardEvent> events = guard.guard(chatClient.prompt(question).stream().content());
// GuardEvent: Delta(text) to show, Replace(text, reason) to swap the whole answer, Done(status)
```

A leak cancels the model stream (it stops spending tokens) and emits `Replace` with your refusal. A foreign link or an empty answer is replaced at the end.

**Signed history**, so a client can't put words in the assistant's mouth:

```java
HistorySigner signer = HistorySigner.hmacSha256(System.getenv("HISTORY_SECRET"));   // 32+ bytes
String sig = signer.sign("answer:en", answer);                 // send it with the answer
List<Turn> history = signer.keepSigned(fromClient, "answer:en"); // before the next model call
```

**An agent's proposed action**, released only when the answer is not a refusal:

```java
ProposalGate<Draft> gate = ProposalGate.create();
// in the tool handler:
if (!gate.hold(draft)) return "A draft was already proposed.";
// after the answer streamed:
gate.release(isRefusal(answer)).ifPresent(this::showForConfirmation);
```

**An agent's tools**, deny by default, checked before every call:

```java
ToolPolicy policy = ToolPolicy.builder()
        .tool("search_site", t -> t.arg("query", ArgRule.required(), ArgRule.maxLength(200)))
        .tool("get_case_study", t -> t
                .arg("slug", ArgRule.required(), ArgRule.maxLength(60), ArgRule.matches("[a-z0-9-]+"))
                .logArgs("slug"))                        // only this argument goes to logs
        .tool("send_message", t -> t
                .confirm()                               // a side effect: never Run, the user confirms
                .arg("body", ArgRule.required(), ArgRule.maxLength(2000))
                .maxCalls(1))                            // per answer
        .maxCalls(3)                                     // all tools, per answer; required
        .build();                                        // anything not declared is denied

ToolSession session = policy.session();                  // one per answer
String result = switch (session.decide(new ToolCall(name, args))) {   // args: the model's JSON, parsed
    case ToolDecision.Run run   -> execute(run.call());
    case ToolDecision.Confirm c -> gate.hold(draftFrom(c.call())) ? "Draft shown to the user." : "Already proposed.";
    case ToolDecision.Deny deny -> deny.messageForModel();  // "Error: unknown tool. Available: ..."
};
request.toolChoice(session.nextChoice());                // NONE once the calls are used
```

A denial tells the model what it can do next (the declared tools, the argument that broke a rule, "tool limit reached; answer now") and never echoes what the model sent: a rejected value may be exactly what an injection wanted back in the conversation. Every call counts against the total, denied or not, so a model that keeps sending bad calls still reaches the cap and the loop ends.

**Spring Boot**: add the starter and set the properties; each bean backs off when you define your own.

```yaml
noxguard:
  refusal: "I can only answer questions about this site."
  stream:
    leak-markers: ["You are Ava", "<context>"]
    max-chars: 1200
  links:
    allow: ['^([a-z0-9-]+\.)*example\.com$']
  data:
    reserved-tags: [context, question, history]
  history:
    secret: ${NOXGUARD_HISTORY_SECRET}          # 32+ bytes, or the app does not start
  tools:
    max-calls: 3
    allow:                                      # a list, so tool names keep their underscores
      - name: get_case_study
        args:
          - name: slug
            required: true
            pattern: '[a-z0-9-]{1,60}'
        log-args: [slug]
      - name: send_message
        confirm: true
        max-calls: 1
```

This gives `LinkPolicy`, `DataEnvelope`, `HistorySigner`, `StreamGuards` (a new `StreamGuard` per answer), `ToolPolicy` and, with `noxguard-reactor`, `ReactorGuard`. A short secret, an invalid regex or a tool setting without meaning stops the app at startup, naming the property. IDEs autocomplete the keys.

Every public type has Javadoc with its rules and thread-safety. Per-answer guards (`StreamGuard`, `ToolSession`, `ProposalGate`, `ToolBudget`) are not thread-safe; configuration objects (`LinkPolicy`, `DataEnvelope`, `HistorySigner`, `ToolPolicy`) are immutable and can be shared, for instance as Spring beans.

## Example app

[`examples/chat-spring-boot`](examples/chat-spring-boot) is a small Spring Boot chat for a made-up bookstore, with every guard in the path, set up by the starter from `application.yml`. Its default model is scripted and misbehaves on purpose: it recites its prompt, obeys an image instruction, proposes a message for a third party, calls a tool it was never given, passes a path as a book title and rambles. CI runs a 14-case noxeval suite against it over HTTP, and the guards catch each mistake. A real model is one Spring profile away.

## Honest limits

- The stream guard catches literal markers. A paraphrased leak passes; the defense there is having no secret in the prompt.
- Links are checked at the end of the stream, so a link's text can be shown before the answer is replaced. Render answers as plain text until the stream ends.
- `InputViews` gives a classifier better input. It is not a classifier.
- A guard proves nothing about a model. `ProposalGate` makes a model's mistake harmless; how often the model makes it is measured with noxeval.
- `ToolPolicy` decides on the call the model asked for; it does not make the tool itself safe. A tool that can delete data needs its own authorization in the system it touches.
- Argument rules check shape, not intent: a slug that matches the pattern does not mean this user may see that case. Authorizing the data is the app's job.
- `Confirm` relies on the app showing the proposal and waiting for the user. The policy cannot see the UI.

## Design

The core knows no framework; adapters depend on it, never the reverse. Results are sealed types, there is no `null` in the public API, and `module-info` exports only the API packages. The spec, the decisions and the alternatives that were turned down are in [`docs/specs/`](docs/specs) (0.1, the core; 0.2, the tool policy and the starter) and [`docs/design.md`](docs/design.md).

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md). To report a vulnerability, see [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE)
