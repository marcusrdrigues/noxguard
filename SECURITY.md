# Security

noxguard is a set of guards, not a guarantee. Each guard documents what it does not catch (see "Honest limits" in the README); use them as layers, together with a design that gives the model as little power as possible.

Keep the `HistorySigner` secret (32 bytes or more) out of the repository, in an environment variable or a secret manager, and rotate it if it leaks.

To report a vulnerability, especially a way to make `StreamGuard` release part of a marker or to get a reserved tag past `DataEnvelope`, use [GitHub's private vulnerability reporting](https://github.com/marcusrdrigues/noxguard/security/advisories/new) instead of a public issue.
