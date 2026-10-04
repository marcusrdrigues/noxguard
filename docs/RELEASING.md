# Releasing to Maven Central

One-time setup (Marcus), then one tag per release. Nothing secret ever goes into the repository.

## One-time setup

1. **Central Portal account.** Sign in at https://central.sonatype.com with GitHub.
2. **Namespace `com.marcusrdrigues`.** In the portal, add the namespace. It shows a verification key: create a DNS **TXT** record on `marcusrdrigues.com` with that key as the value (Namecheap: Domain List, Manage, Advanced DNS, Add New Record, TXT, host `@`). Click Verify once the record is live; it can take a few minutes. The record can be removed after verification.
3. **Portal token.** In the portal, Account, Generate User Token. It gives a username and a password.
4. **GPG key** (Gpg4win on Windows, or `gpg` in Git Bash):
   ```bash
   gpg --full-generate-key                      # RSA 4096, no expiry or 2 years, your name and e-mail
   gpg --list-secret-keys --keyid-format=long   # copy the key id after "rsa4096/"
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # Central checks signatures against public keyservers
   gpg --armor --export-secret-keys <KEY_ID> > private.asc     # for the GitHub secret; delete the file afterwards
   ```
5. **GitHub secrets** (repository Settings, Secrets and variables, Actions):
   | Secret | Value |
   | --- | --- |
   | `CENTRAL_USERNAME` | the token username |
   | `CENTRAL_PASSWORD` | the token password |
   | `GPG_PRIVATE_KEY` | the whole content of `private.asc` |
   | `GPG_PASSPHRASE` | the key's passphrase |

   The release job runs in the `maven-central` environment; you can add a required reviewer to it so every publish waits for your approval.

## Each release

1. Move `## [x.y.z] - unreleased` in `CHANGELOG.md` to today's date and push.
2. Tag and push:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
3. The **Release** workflow sets the version from the tag, runs the tests, signs and publishes `noxguard-parent`, `noxguard-core` and `noxguard-reactor`. It waits until Central says "published"; the artifacts show up on search a little later.

The repository itself stays at `-SNAPSHOT`. CI already checks the release build (sources, Javadoc, the Central plugin) without signing on every push, so a tag should only fail on credentials.
