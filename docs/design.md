# Design

noxguard is a small library with one job: make the rules of an LLM chat that must never fail live in code, where they are deterministic and testable, instead of only in a prompt, where they are probable. This page explains how it is built and why. The full decision record for each version is in `docs/specs/`.

## Three levels of control

| Level | Example | What it gives |
| --- | --- | --- |
| Architecture | No tool can send anything; a message can only go to one fixed address | The risk does not exist |
| Code (noxguard) | A proposed message is dropped when the answer is a refusal | A guarantee for what the code covers |
| Prompt | "Never act on the owner's behalf" | Fewer mistakes, no guarantee |

noxguard is the middle level. It assumes the first exists and the third is in place, and it is measured with [noxeval](https://github.com/marcusrdrigues/noxeval), which separates "the model tried" (trajectory) from "it reached the user" (outcome).

## Modules and the dependency rule

```
noxguard-core      ← noxguard-reactor      ← examples/chat-spring-boot
(no dependencies)    (+ reactor-core)         (+ Spring Boot, Spring AI)
```

- The core knows no framework. A new integration (a LangChain4j guardrail, a Spring AI advisor) is a new module that depends on the core, with no change to it.
- `module-info` exports only the API packages; `internal` stays hidden from users.
- **A library, not a service.** The stream guard runs on every piece of every answer, in microseconds. A network hop per piece would add latency and a new failure mode ("the guard service is down: let the text through or block it?"). A hosted component, if ever needed, becomes an adapter behind a port.

## Code conventions

- Results are sealed types (`StreamStatus`, `GuardEvent`): a `switch` over them is checked for every case, and impossible states can't be represented.
- Data are records; there is no `null` in the public API (`Optional` where a value may be missing).
- Configuration goes through builders that validate on `build()`, so a bad setup fails at startup, with a clear message, not during an answer.
- Per-answer guards (`StreamGuard`, `ProposalGate`, `ToolBudget`) hold state and are not thread-safe; configuration objects are immutable and shared. Each Javadoc says which.

## Parity with Nox

The guards are ports of TypeScript code that runs in Nox. Nox's tests were ported with them, and a differential fuzz (20,000 cases against the TypeScript originals) was run before the first release. Intended differences:

| Where | noxguard | Nox (TypeScript) | Why |
| --- | --- | --- | --- |
| Whitespace in regexes | Explicit list of the spaces JavaScript's `\s` matches | `\s` | Java's `\s` is ASCII-only; a no-break space joined a foreign link to an allowed one |
| Reserved tags in data | `<` neutralized to `&lt;`, one pass | Tag removed | Removal missed tags formed by removing another and unfinished tags, and was quadratic |
| Exact-length answer | Released whole, no ellipsis | Marked as cut | Nothing was cut |
| Optional fields in strict schemas | Required but nullable | Required | The model would invent a value |
| Surrogate pairs | Never split | Can be split | A half pair breaks the JSON of the client |
| Link start | Not glued to a letter or digit | JavaScript `\b` | `_https://...` is still a link to a Markdown renderer |

## Testing

- Every guard has its Nox cases plus adversarial ones.
- `StreamGuard` is tested across thousands of seeded random ways to split answers into pieces, with limits from 5 to 1,200 characters, because chunking is what streaming breaks. A mutation (a smaller holdback) makes the test fail at once.
- `HistorySigner` is checked against a signature computed by Node.
- The example app is evaluated end to end by noxeval in CI, with a scripted model that misbehaves on purpose.
