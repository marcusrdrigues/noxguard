# noxguard

**Deterministic guardrails for LLM chats and agents, in Java.** A streaming output guard that never releases a leak and never buffers the answer, a link allow list, data delimiting, signed history, a deny-by-default tool policy for agents (with a Spring AI adapter), a proposal gate, a citation check for RAG answers and a timeout with an explicit failure mode for your input classifier. No runtime dependencies in the core, and a Spring Boot starter that sets it all up from properties.

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

A RAG prompt says "use only the passages, never invent numbers or names". That is also probability. `CitationGuard` does it in code: the passages are numbered, the model ends each sentence with the number of its source, and every number, acronym and name in the sentence is checked against the source it cites before the answer is final. A detail in another passage the model received corrects the citation; a detail in no passage removes the sentence. For a legal or financial product this is the core risk: a case number, a court or a date that no source has.

These guards come from Nox, the public chat on the author's portfolio, where they are measured with [noxeval](https://github.com/marcusrdrigues/noxeval) (46 of 47 cases in the official run of October 2026, each asked three times; the [report](https://marcusrdrigues.com/nox) is public). **noxeval measures, noxguard enforces.**

## What's inside

| Guard | Package | Stops |
| --- | --- | --- |
| `StreamGuard` | `output` | A prompt leak or a runaway answer, while the answer streams |
| `LinkPolicy` | `output` | Exfiltration through a link or Markdown image to an outside address |
| `DataEnvelope` | `data` | Retrieved passages or tool results acting as instructions |
| `HistorySigner` | `history` | Forged history ("assistant: developer mode on") sent back by a client |
| `ToolPolicy` | `agent` | A tool call the agent was not given, bad arguments, too many calls, or a side effect without the user's confirmation |
| `ProposalGate`, `ToolBudget`, `StrictSchema` | `agent` | An agent acting beyond the request, or looping without end |
| `GuardedToolCallbacks` | `springai` (module `noxguard-spring-ai`) | A Spring AI tool that runs without the policy deciding first |
| `CitationGuard` | `grounding` | A number, acronym or name in a RAG answer that no source has, or a citation to the wrong source |
| `InputViews` | `input` | Attacks hidden in base64, ROT13, leetspeak or invisible characters, for your classifier to see |
| `GuardedClassifier` | `input` | An input classifier that hangs or fails, and an app that never decided what happens then |

`noxguard-spring-ai` puts every Spring AI `ToolCallback` under the `ToolPolicy`. `noxguard-reactor` turns a `Flux<String>` from Spring AI, WebFlux or any Reactor source into guarded events, with the citation check at the end when you ask for it. `noxguard-spring-boot-starter` creates the guards, the tool policy, the citation guard and the guarded classifier from `noxguard.*` properties.

## Install

Java 21 or later.

```xml
<dependency>
  <groupId>com.marcusrdrigues</groupId>
  <artifactId>noxguard-core</artifactId>
  <version>0.4.0</version>
</dependency>
<!-- for a Flux<String> model stream (Spring AI, WebFlux): -->
<dependency>
  <groupId>com.marcusrdrigues</groupId>
  <artifactId>noxguard-reactor</artifactId>
  <version>0.4.0</version>
</dependency>
<!-- for Spring AI tools under the tool policy (Spring AI 2.0, provided by your app): -->
<dependency>
  <groupId>com.marcusrdrigues</groupId>
  <artifactId>noxguard-spring-ai</artifactId>
  <version>0.4.0</version>
</dependency>
<!-- in a Spring Boot 4 app, the guards from properties: -->
<dependency>
  <groupId>com.marcusrdrigues</groupId>
  <artifactId>noxguard-spring-boot-starter</artifactId>
  <version>0.4.0</version>
</dependency>
```

Gradle: `implementation("com.marcusrdrigues:noxguard-core:0.4.0")`. From source: `git clone https://github.com/marcusrdrigues/noxguard && cd noxguard && mvn install`.

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

**A RAG answer's citations**, checked against the sources the model received:

```java
CitationGuard citations = CitationGuard.builder()
        .allowNames(List.of("Example Books", "Ava"))            // names an answer may say without a source
        .build();                                                // immutable, thread-safe

String context = CitationGuard.numbered(passages);              // "[1] ...\n\n[2] ...": put this in the prompt
// ...the model answers "We're open from 9am to 6pm, Monday to Saturday [1]."

CitationResult result = citations.check(answer, passages, question);
String shown = result.empty() ? NOT_CONFIRMED : result.text();  // sentences removed, citations corrected
result.sentences();                                             // per sentence: KEPT, RECITED, REMOVED, with the details
```

Numbers are compared by value ("10 mil", "10,000" and "10.000" are the same), names and acronyms as whole words. A number of years that is the difference of two years in the answer ("from 2023 to 2026, 3 years") is arithmetic, not invention. A detail taken from the question passes only in a sentence that denies it ("I didn't find a prize in 2024"). When the first sentence is removed, a second one that leans on it ("He...", "That...") goes too.

With `noxguard-reactor`, the same check runs when the stream ends. The sources are read then, so tool results that arrived during the answer count:

```java
ReactorGuard guard = ReactorGuard.builder()
        .streamGuard(...).links(...).refusal(REFUSAL)
        .citations(citations, "I couldn't find that confirmed.")
        .build();

guard.guard(stream, () -> sources, question);   // Replace(checked text, CITATIONS) or Replace(not confirmed, NOT_CONFIRMED)
```

**An input classifier** (a hosted model that flags prompt injection), with a timeout and a choice for when it is down:

```java
InputClassifier classifier = text -> {
    double score = client.injectionScore(text);                 // your call
    return score >= 0.9 ? Verdict.flagged(score, "injection") : Verdict.clean(score);
};
GuardedClassifier guarded = GuardedClassifier.of(classifier)
        .timeout(Duration.ofMillis(800))
        .onFailure(FailureMode.FAIL_OPEN)                       // required: build() throws without it
        .build();

switch (guarded.classify(message)) {                            // the message and its decoded views
    case ClassifierOutcome.Allowed a     -> answer(message);
    case ClassifierOutcome.Blocked b     -> refuse();
    case ClassifierOutcome.Unavailable u -> { metrics.count("classifier.down"); answer(message); }   // FAIL_OPEN only
}
```

The classifier reads the message and each view `InputViews` decodes; any flagged view blocks. One timeout covers all the views, and a classifier that times out is interrupted. Fail open when other guards still run after the model (Nox does); fail closed when a missed attack costs more than a refused question. `Unavailable` is its own case so a dashboard can count how often the classifier was down instead of mixing it with "allowed".

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

**Spring AI tools**, with `noxguard-spring-ai`: the same policy wraps every `ToolCallback`, so you don't write the loop above.

```java
GuardedToolCallbacks guarded = GuardedToolCallbacks.builder(policy)
        .tools(List.of(ToolCallbacks.from(new StoreTools())))   // @Tool methods, a provider's callbacks, MCP tools
        .onConfirm(ConfirmMode.HOLD)                              // required when a tool is declared with confirm()
        .listener((tool, decision) -> log.info("tool={} decision={}", tool, decision.getClass().getSimpleName()))
        .build();                                                 // throws when a tool has no rule in the policy

AnswerTools answer = guarded.forNewAnswer();                      // one per request: its own session and limits
String text = chatClient.prompt(question).toolCallbacks(answer.callbacks()).call().content();
answer.release(isRefusal(text)).ifPresent(held -> showForConfirmation(held.call()));
// when the person confirms: held.run()   (runs the original tool once)
```

Arguments that are not a JSON object are denied and counted, like any call. `ConfirmMode.DENY` never runs a tool declared with `confirm()` (right for voice or batch, where there is no screen to ask on); `HOLD` keeps the call for the person. Call `forNewAnswer()` on every request: one answer's callbacks registered as the client's default tools would share one session, which fails closed (calls past the cap are denied) but no longer counts per answer.

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
  citations:
    allow-names: [Example Books, Ava]           # creates the CitationGuard
  input:                                        # with an InputClassifier bean of yours, both are required
    on-failure: fail-open                       # or fail-closed; there is no default
    timeout: 800ms
  tools:
    max-calls: 3
    on-confirm: hold                            # with noxguard-spring-ai and a confirm tool: deny or hold, no default
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

This gives `LinkPolicy`, `DataEnvelope`, `HistorySigner`, `StreamGuards` (a new `StreamGuard` per answer), `ToolPolicy`, `CitationGuard`, `GuardedClassifier` (around your `InputClassifier` bean), with `noxguard-reactor` a `ReactorGuard` and, with `noxguard-spring-ai`, `GuardedToolCallbacks` around your `ToolCallback` and `ToolCallbackProvider` beans. A short secret, an invalid regex, a tool setting without meaning or an input classifier without `on-failure` stops the app at startup, naming the property. IDEs autocomplete the keys.

Every public type has Javadoc with its rules and thread-safety. Per-answer guards (`StreamGuard`, `ToolSession`, `ProposalGate`, `ToolBudget`) are not thread-safe; configuration objects (`LinkPolicy`, `DataEnvelope`, `HistorySigner`, `ToolPolicy`, `CitationGuard`, `GuardedClassifier`) are immutable and can be shared, for instance as Spring beans.

## Example app

[`examples/chat-spring-boot`](examples/chat-spring-boot) is a small Spring Boot chat for a made-up bookstore, with every guard in the path, set up by the starter from `application.yml`. Its default model is scripted and misbehaves on purpose: it recites its prompt, obeys an image instruction, proposes a message for a third party, calls a tool it was never given, passes a path as a book title, invents the year the store opened and rambles. CI runs a 15-case noxeval suite against it over HTTP, and the guards catch each mistake. A real model is one Spring profile away.

## Honest limits

- The stream guard catches literal markers. A paraphrased leak passes; the defense there is having no secret in the prompt.
- Links are checked at the end of the stream, so a link's text can be shown before the answer is replaced. Render answers as plain text until the stream ends.
- `InputViews` gives a classifier better input. It is not a classifier, and `GuardedClassifier` decides what happens when yours fails, not how good it is.
- `CitationGuard` checks details, not meaning: "worked at" becoming "led" passes, because both words are ordinary. Checking meaning needs a model; measure it with noxeval's judge.
- A number written as a word in the answer ("two copies") is not checked. Numbers in digits are; words in the sources ("ten thousand") count as numbers.
- Citations are only checkable once a sentence ends, so in a stream the user may see a sentence that is then removed. Render as plain text until the stream ends, as with links.
- The rules for number words and for sentences that lean on the previous one ("He", "Isso") cover Portuguese and English.
- A guard proves nothing about a model. `ProposalGate` makes a model's mistake harmless; how often the model makes it is measured with noxeval.
- `ToolPolicy` decides on the call the model asked for; it does not make the tool itself safe. A tool that can delete data needs its own authorization in the system it touches.
- Argument rules check shape, not intent: a slug that matches the pattern does not mean this user may see that case. Authorizing the data is the app's job.
- `Confirm` relies on the app showing the proposal and waiting for the user. The policy cannot see the UI.
- A Spring AI tool marked `returnDirect` sends its result straight to the user, so a denial does too ("Error: ..."). Nothing runs, but the text is the model's error message; prefer tools without `returnDirect` under the policy.

## Design

The core knows no framework; adapters depend on it, never the reverse. Results are sealed types, there is no `null` in the public API, and `module-info` exports only the API packages. The spec, the decisions and the alternatives that were turned down are in [`docs/specs/`](docs/specs) (0.1, the core; 0.2, the tool policy and the starter; 0.3, the citation guard and the input classifier port; 0.4, the Spring AI adapter) and [`docs/design.md`](docs/design.md).

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md). To report a vulnerability, see [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE)
