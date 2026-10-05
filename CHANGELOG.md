# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

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
