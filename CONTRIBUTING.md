# Contributing

Thanks for helping. Issues, ideas and pull requests are all welcome.

## Setup

```bash
git clone https://github.com/marcusrdrigues/noxguard && cd noxguard
mvn verify          # build, tests and Javadoc for the library (Java 21 or later)
```

The example app needs Java 25 and the library installed first:

```bash
mvn install -DskipTests
mvn -f examples/chat-spring-boot/pom.xml spring-boot:run
```

## How the code is organized

```
noxguard-core/       the guards; pure Java, no runtime dependency, no framework
  output/ data/ history/ agent/ input/   one package per kind of guard (all exported)
  internal/                              helpers, not exported by module-info
noxguard-reactor/    adapter from a Flux<String> to guarded events; depends on the core and reactor-core
examples/            a Spring Boot app in layers (domain, application, infrastructure, web) and its noxeval suite
docs/specs/          one spec per version, written and approved before the code
```

Dependencies point inward: adapters depend on the core, never the reverse. See [docs/design.md](docs/design.md).

## Rules of the house

- **Spec first.** A new guard or a behavior change starts as a spec in `docs/specs/`, with the alternatives turned down and why.
- **No runtime dependencies in the core.** A new dependency anywhere needs a strong reason in the pull request.
- **Every behavior change comes with a test.** A guard gets an adversarial test too: the input an attacker would try, not only the happy path.
- **Never call a real model in tests**, and never commit secrets, API keys, `.env` files or real user data.
- **No `null` in the public API**, results as sealed types or records, and Javadoc on every public type that says whether it is thread-safe.
- Code, comments, commits and docs in English (the Portuguese README mirrors the English one). Comments explain why, not what.

## Commits and pull requests

[Conventional Commits](https://www.conventionalcommits.org/) with a scope: `feat(output): ...`, `fix(history): ...`, `docs: ...`. One topic per pull request, CI green on Java 21 and 25.
