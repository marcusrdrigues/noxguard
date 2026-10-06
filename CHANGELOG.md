# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.4.0] - unreleased

### Added

- `noxguard-spring-ai`: Spring AI tools under a `ToolPolicy`. `GuardedToolCallbacks` wraps every `ToolCallback`; `forNewAnswer()` gives each request an `AnswerTools` whose callbacks share one session. A tool without a rule in the policy, or a tool that needs confirmation without a `ConfirmMode`, stops `build()`. `ConfirmMode.DENY` never runs such a tool; `HOLD` keeps the call until the answer ends and gives it as a `HeldCall` to run once, after the person confirms, only when the answer is not a refusal. A `ToolDecisionListener` hears every decision. Spring AI is a `provided` dependency, compiled against 2.0.1. It comes from the `GuardedToolCallback` of [dio-spring-boot-learning-track](https://github.com/marcusrdrigues/dio-spring-boot-learning-track/tree/main/05-spring-ai).
- `ToolSession.invalidArguments(tool)`: denies a call whose arguments are not a JSON object, counted against the answer's total and never against the tool's own cap.
- Starter: with `noxguard-spring-ai`, a `ToolPolicy` and the app's `ToolCallback` or `ToolCallbackProvider` beans, a `GuardedToolCallbacks` bean. `noxguard.tools.on-confirm` (`deny` or `hold`) is required when one of those tools is declared with `confirm: true`.

## [0.3.0] - 2026-10-06

### Added

- `CitationGuard` (in `noxguard-core`, package `grounding`): checks a RAG answer's `[n]` citations against the sources the model received. A number, acronym or name in another received source corrects the citation (`RECITED`); one in no source removes the sentence (`REMOVED`), and a following sentence that leans on it goes too (`REMOVED_WITH_PREVIOUS`). `CitationResult.empty()` tells the app to show its "not confirmed" text. `CitationGuard.numbered(sources)` builds the numbered context for the prompt. `GroundingDetails` exposes the detail extractor on its own. Both are ports of Nox's checker, verified against the TypeScript original on 1,500 generated cases from the site's content.
- `GuardedClassifier` (package `input`): wraps an `InputClassifier` with a timeout and a required `FailureMode` (`FAIL_OPEN` or `FAIL_CLOSED`, no default). It classifies the message and its `InputViews`; any flagged view blocks. The outcome is a sealed `ClassifierOutcome`: `Allowed`, `Blocked` or `Unavailable` (the classifier failed and the app fails open).
- `ReactorGuard.Builder.citations(guard, notConfirmed)` and `ReactorGuard.guard(stream, sources, question)`: the citation check when the stream ends. The sources are a `Supplier`, read then, so tool results that arrived during the answer count. `GuardEvent.Reason` gains `CITATIONS` and `NOT_CONFIRMED`.
- Starter: `noxguard.citations.allow-names` creates a `CitationGuard`; an `InputClassifier` bean of the app's gets a `GuardedClassifier` from `noxguard.input.on-failure` and `noxguard.input.timeout`, and the app does not start without them.
- `InputViews.withDefaults()`.

### Changed

- A `switch` over `GuardEvent.Reason` without a `default` needs the two new cases.
- `examples/chat-spring-boot` numbers its passages and tool results, its scripted model cites them, and the citation check runs on every answer. The model now also invents the year the store opened; the guard removes it. The noxeval suite has 15 cases.

## [0.2.0] - 2026-10-06

### Added

- `ToolPolicy` (in `noxguard-core`, package `agent`): decides each tool call an agent asks for, deny by default. A `ToolSession` per answer returns a sealed `ToolDecision`: `Run`, `Confirm` (a tool with a side effect, for a `ProposalGate` and the user) or `Deny` with a reason (`UNKNOWN_TOOL`, `ARGUMENT`, `LIMIT`) and a message for the model that never echoes what the model sent. `ArgRule` checks arguments in code (`required`, `matches`, `maxLength`, `oneOf`, `type`, or an app rule that fails closed). Limits per tool and per answer; only the arguments listed in `logArgs` reach logs.
- `noxguard-spring-boot-starter`: Spring Boot 4 auto-configuration. `noxguard.*` properties create `LinkPolicy`, `DataEnvelope`, `HistorySigner`, `StreamGuards`, `ToolPolicy` and, with `noxguard-reactor`, `ReactorGuard`; each backs off when the app defines its own. A history secret under 32 bytes, an invalid regex or a tool setting without meaning stops the app at startup, naming the property. Configuration metadata is generated for IDEs.

### Changed

- `examples/chat-spring-boot` uses the starter, and its tool calls go through the `ToolPolicy`: a read-only tool runs, the message tool only proposes, anything else is denied. The noxeval suite has 14 cases.

## [0.1.0] - 2026-10-04

### Added

- `noxguard-core`, with no runtime dependencies:
  - `StreamGuard`: holds back the last (longest marker - 1) characters of a streamed answer so a leak marker is always seen whole; caps the length; never splits a surrogate pair.
  - `LinkPolicy`: links and Markdown images outside an allow list; a link ends at any Unicode whitespace.
  - `DataEnvelope`: wraps untrusted text as data, neutralizing every reserved tag (unfinished and space-prefixed ones included) in one pass.
  - `HistorySigner`: HMAC-SHA256 signatures for assistant turns, byte-compatible with Node's `digest("base64url")`; `keepSigned` drops forged turns.
  - `ProposalGate`, `ToolBudget`, `StrictSchema`: an agent's proposed action is released only when the answer is not a refusal; tool calls are capped; JSON Schemas are made strict, with optional fields nullable.
  - `InputViews`: normalization and the base64, ROT13 and leetspeak versions of a message for an input classifier.
- `noxguard-reactor`: `ReactorGuard` turns a `Flux<String>` into `GuardEvent`s (`Delta`, `Replace`, `Done`), cancelling the model stream on a leak.
- `examples/chat-spring-boot`: a Spring Boot chat with every guard, a scripted model that misbehaves on purpose, and a noxeval suite run in CI.
