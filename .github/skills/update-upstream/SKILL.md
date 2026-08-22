---
name: update-upstream
description: Use when syncing the Clojure Copilot SDK with upstream releases or recertifying parity with the official Node.js SDK.
compatibility: Requires authenticated copilot and gh CLIs, Clojure CLI, bb, and a local github/copilot-sdk checkout beside the primary checkout or specified by COPILOT_SDK_UPSTREAM.
---

# Update Upstream

Sync or recertify this repository against an exact upstream pin. Read
`AGENTS.md` at the repository root first, then read `references/PROJECT.md` for the authority map,
classification rules, contract matrix, and wire notes.

## 1. Establish the Inputs

1. Refresh `origin/main` without checking out `main` in a linked worktree:

   ```bash
   git fetch origin main
   git rev-list --left-right --count HEAD...origin/main
   ```

   Fast-forward only when the branch has no local commits. If it has diverged,
   use a fresh project session from current default or ask before an additive
   merge. Never rewrite history by default.

2. Resolve and refresh the upstream checkout:

   ```bash
   UPSTREAM_REPO="$(bash .github/skills/update-upstream/scripts/resolve-upstream.sh)" &&
   git -C "$UPSTREAM_REPO" fetch --prune --tags origin
   ```

   Re-resolve `UPSTREAM_REPO` in each shell call. Never hard-code an absolute or
   worktree-relative sibling path.

3. Record the exact upstream target commit and the current Clojure base before
   analysis. Use exact pins in evidence so later reviews are reproducible.

4. Locate any validated historical parity oracle. Keep its original pin,
   symbols, and source ranges intact. Do not retarget an old evidence file by
   replacing only its commit hash. Add a separate post-baseline delta inventory
   for a newer target.

## 2. Reconstruct the Stable Public Surface

Stable recertification is a complete public-surface inventory at the target pin,
not a changed-file grep or generated-schema scan.

1. Inventory the Node package root exports and public types.
2. Inventory `CopilotClient` constructors, builders, and methods.
3. Inventory `CopilotSession` methods and lifecycle behavior.
4. Trace extension and join paths, including any distinct builders.
5. Read relevant stable unit and end-to-end tests for behavior not explicit in
   types.
6. Use Python only as behavioral corroboration. Use CLI and generated protocol
   sources only to understand wire behavior; neither creates a stable parity
   requirement by itself.

Classify every discovered delta as:

- stable public;
- experimental;
- internal;
- generated-only; or
- language-specific.

Port stable public behavior. Experimental work requires an explicit maintainer
decision, accepted ADR, or direct user request. Record intentional exclusions
in durable evidence, docs, or an ADR so future audits do not rediscover them as
unresolved gaps.

## 3. Plan the End-to-End Contract

For each stable public delta, create one row in a contract matrix covering:

1. public Node export or type;
2. applicable create, resume, join, or method builder;
3. exact wire key, nesting, enum spelling, and omission/null semantics;
4. Clojure public name and idiomatic value shape;
5. closed-key/value specs and registered fdefs;
6. public API snapshot impact;
7. targeted tests;
8. docs, examples, and changelog.

Apply the optional-field proof in `references/PROJECT.md` to every applicable
builder and mutable update path. Keep separate create/resume/join builders
consistent without assuming their accepted options are identical.

Present the classified inventory and implementation plan for approval before
changing production behavior.

## 4. Implement Contract-First

1. Write the smallest failing table-driven, property, or real-protocol test that
   proves the missing behavior.
2. Confirm the focused test fails for the intended reason.
3. Implement the complete contract path, including specs, fdefs, API snapshot,
   wire conversion, and every applicable builder.
4. Re-run the focused gate before expanding scope.

Preserve the wire/idiom boundary described in `AGENTS.md`. Generated wire specs
remain schema-faithful and private; hand-curated specs define caller-facing
Clojure values; generated coercion bridges deliberate differences. Opaque JSON
requires recursive shape and key-preservation tests through every reachable
notification and response path.

For core.async work, prove ownership, cancellation, backpressure, bounded
blocking, cleanup, and error precedence. Never run arbitrary blocking or
user-supplied work on `go` dispatch. Replace sleeps with observable
synchronization. Keep cleanup failures visible without replacing the primary
failure.

Prefer a canonical idiomatic API and an explicit breaking change over an
unproven compatibility alias.

Keep pre-existing issues outside the sync diff unless they block the port.
Record and track them separately rather than expanding a parity change into
unrelated cleanup.

## 5. Regenerate Deterministically

Regenerate schemas, code, API snapshots, and docs only from their canonical
pinned inputs. Never hand-edit generated outputs.

When generator drift is possible:

1. run the owning generator;
2. review the complete generated diff;
3. run it again from identical inputs; and
4. require the second run to produce no diff.

## 6. Update Documentation and Examples

Invoke the `update-docs` skill for canonical docs, examples, regeneration, and
validation. Document stable behavior and intentional experimental exclusions
without copying temporary audit notes into evergreen docs. Add a concise
`[Unreleased]` changelog entry.

## 7. Validate and Review

Use the smallest targeted gates during iteration, then run:

```bash
COPILOT_UPSTREAM_VALIDATION=true bb test
bb ci:full
```

The first command validates committed exact-pin evidence against the resolved
local upstream checkout. Normal `bb test` and CI remain hermetic when that
external checkout is unavailable.

If authenticated end-to-end prerequisites are unavailable, run `bb ci` and
state the limitation. Review example output, generated diffs, and the
machine-readable parity inventory.

Obtain independent review focused on stable-surface completeness, exact wire
semantics, spec/fdef/API-snapshot coverage, Clojure idioms, concurrency and
resource ownership, tests, and accidental experimental exposure. Address valid
findings and explain false positives.

## 8. Publish and Converge Review

If available, invoke a PR-authoring skill before creating or editing the pull
request. Otherwise perform the same checks directly:

1. confirm the branch is based on current `origin/main`;
2. commit logical changes with a descriptive message that follows active
   repository and attribution rules;
3. push the current branch and create one focused pull request; and
4. include the contract changes, validation, exclusions, and review findings in
   the PR body.

If available, invoke the Copilot code-review workflow. Otherwise fetch exact
Copilot review threads directly. Triage each comment on its merits, address
valid findings, explain false positives, reply in the exact thread, resolve it,
re-request review, and repeat until a new review round has no comments. Stop and
ask the maintainer if convergence takes more than 10 rounds.

## 9. Keep This Skill Current

After a sync:

1. compare `AGENTS.md` project structure with the repository;
2. compare `references/PROJECT.md` with the upstream public source layout;
3. remove stale workflow steps and pitfalls;
4. add a pitfall only when it generalizes to a recurring class of error.

Single-occurrence details belong in commits, pull requests, audit evidence, or
ADRs, not in this skill.
