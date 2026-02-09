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

## Local Testing

```bash
clj -T:build install
```
