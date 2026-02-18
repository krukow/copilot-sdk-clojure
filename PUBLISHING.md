# Publishing

This SDK is published to both Maven Central and Clojars with different coordinates.

## Versioning

This project follows the upstream [github/copilot-sdk](https://github.com/github/copilot-sdk) release versioning with a 4-segment scheme:

```
UPSTREAM_MAJOR.UPSTREAM_MINOR.UPSTREAM_PATCH.CLJ_PATCH
```

- The first 3 segments match the upstream copilot-sdk release version (e.g., `0.1.22`)
- The 4th segment is a Clojure-specific patch counter (starts at `0`)
- Example: `0.1.22.0` = first release tracking upstream `v0.1.22`; `0.1.22.1` = Clojure-only bugfix

### Syncing to a new upstream release

```bash
# Set version to match upstream release (resets clj-patch to 0)
clj -T:build sync-version :upstream '"0.1.23"'
```

### Bumping for Clojure-specific changes

```bash
# Increment clj-patch: 0.1.22.0 -> 0.1.22.1
clj -T:build bump-version

# With SNAPSHOT suffix
clj -T:build bump-version :snapshot true
```

## Coordinates

| Repository | Group ID | Artifact ID |
|------------|----------|-------------|
| Maven Central | `io.github.copilot-community-sdk` | `copilot-sdk-clojure` |

> **Note:** The Clojars artifact `net.clojars.krukow/copilot-sdk` is deprecated.
> Use the Maven Central coordinates above.

## Build Commands

| Command | Description |
|---------|-------------|
| `clj -T:build jar` | Build JAR |
| `clj -T:build install` | Install to local Maven repo |
| `clj -T:build deploy-central` | Deploy to Maven Central |
| `clj -T:build bundle` | Create bundle zip (manual upload) |

## Deploy to Maven Central

### Prerequisites

1. **Sonatype Central Portal account**: https://central.sonatype.com/ (sign in with GitHub)
2. **User token**: Generate at https://central.sonatype.com/account
3. **GPG key**: For signing artifacts

### Configure Credentials

Add to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_TOKEN_USERNAME</username>
      <password>YOUR_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

Or use environment variables: `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`.

### Configure GPG

```bash
gpg --gen-key
gpg --list-secret-keys --keyid-format LONG
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

### Deploy

```bash
clj -T:build deploy-central
```

Uses version from `build.clj`. Override with `:version '"X.Y.Z"'` if needed.

Publishes to `io.github.copilot-community-sdk/copilot-sdk-clojure`.

## GitHub Actions release workflow

Trigger the **Release** workflow manually in GitHub Actions (restricted to the repository maintainer). Inputs:

| Input | Type | Description |
|-------|------|-------------|
| `version_strategy` | choice | `none` (use current), `sync-upstream`, `bump-clj-patch`, or `set-version` |
| `upstream_version` | string | Required for `sync-upstream`; 3-segment version (e.g., `0.1.23`) |
| `explicit_version` | string | Required for `set-version`; full version (e.g., `0.1.23.1` or `0.1.23.1-SNAPSHOT`) |
| `snapshot` | boolean | Append `-SNAPSHOT` for `sync-upstream`/`bump-clj-patch` |

When `version_strategy` is not `none`, the workflow:

1. Bumps the version in `build.clj` and `README.md`
2. Updates the README git SHA
3. Opens a PR to `main` with auto-merge enabled
4. Waits for CI to pass and the PR to merge
5. Deploys to Maven Central
6. Tags the release (`vX.Y.Z.N`)
7. Creates a [GitHub release](https://github.com/copilot-community-sdk/copilot-sdk-clojure/releases) with auto-generated notes and attached JAR/bundle artifacts

SNAPSHOT versions are marked as pre-release.

### Prerequisites

- Enable **Allow auto-merge** in repository settings (Settings → General → Pull Requests)
- Required status check `ci` must be configured in branch protection rules

### Required secrets

Secrets should be configured as **environment secrets** on the `release` environment
(Settings → Environments → `release`), not as repository-level secrets.
This ensures they are only available to the release workflow.

| Secret | Description |
|--------|-------------|
| `RELEASE_TOKEN` | Fine-grained PAT with `contents: write` and `pull-requests: write` scopes — used to create the release PR (so CI triggers) and the GitHub release |
| `CENTRAL_USERNAME` | Sonatype Central Portal token username |
| `CENTRAL_PASSWORD` | Sonatype Central Portal token password |
| `GPG_PRIVATE_KEY` | ASCII-armored GPG private key for artifact signing (required by Maven Central) |
| `GPG_PASSPHRASE` | Passphrase for the GPG key (optional — omit if key has no passphrase) |

#### Environment setup

1. Go to **Settings → Environments → New environment**, name it `release`
2. Add the secrets listed above as environment secrets
3. Optionally add protection rules:
   - **Required reviewers** — require approval before the release job runs
   - **Deployment branches** — restrict to `main` only

## Build Attestation

Release artifacts are attested with [SLSA build provenance](https://slsa.dev/) using [`actions/attest-build-provenance`](https://github.com/actions/attest-build-provenance). Both the JAR and bundle zip receive attestations.

View attestations: https://github.com/copilot-community-sdk/copilot-sdk-clojure/attestations

### Verifying an attestation

Use the [GitHub CLI](https://cli.github.com/) to verify a downloaded artifact against its attestation:

```bash
# Verify the JAR from a specific release
gh attestation verify copilot-sdk-clojure-0.1.23.0.jar \
  --repo copilot-community-sdk/copilot-sdk-clojure

# Verify a JAR from your local Maven cache
gh attestation verify ~/.m2/repository/io/github/copilot-community-sdk/copilot-sdk-clojure/0.1.23.0/copilot-sdk-clojure-0.1.23.0.jar \
  --repo copilot-community-sdk/copilot-sdk-clojure
```

A successful verification confirms the artifact was built by the GitHub Actions release workflow in this repository and has not been tampered with.

## Local Testing

```bash
clj -T:build install
```
