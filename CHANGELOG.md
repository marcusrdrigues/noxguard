# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

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
