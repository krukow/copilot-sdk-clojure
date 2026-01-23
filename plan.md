# Concurrency & Resource Exhaustion Mitigation Plan

## Goal
Reduce risk of deadlocks, backpressure stalls, unbounded queues, and thread exhaustion in async messaging, event routing, and tool execution.

## Scope
Targeted fixes in:
- `src/krukow/copilot_sdk/session.clj` (send-async, <send!, event dispatch)
- `src/krukow/copilot_sdk/protocol.clj` (notification queue + read loop)
- `src/krukow/copilot_sdk/client.clj` (router queue + notification routing)
- `src/krukow/copilot_sdk/helpers.clj` (query-seq lifecycle)
- tests/docs as needed

---

## Work Plan (Implementation Details)

### 1) Harden async send flow to avoid lock leaks
- **Problem:** `send-async*` writes to `out-ch` with `>!`; if consumer stops/slow, go-loop blocks and never releases `send-lock`.
- **Fix:**
  - Change `<send!` implementation to **drain events until `:session.idle`** even after first `:assistant.message` is seen.
  - Use a **non-blocking output channel** for `<send!` (e.g., `chan (sliding-buffer 1)`) so delivering the result can’t block.
  - Add an optional `:timeout-ms` to `<send!` and `send-async` to **force cleanup/untap/release** if `:session.idle` never arrives.
  - Ensure **always** `untap`, `close!`, and `release-lock!` in every exit path.

**Files:** `session.clj` (send-async*, <send!)

---

### 2) Add backpressure-safe event delivery
- **Problem:** `event-chan` + `event-mult` can back up if any subscriber is slow; router go-loop can stall.
- **Fix:**
  - Use a **sliding buffer** for session `event-chan` (size **4096**).
  - If `offer!` fails due to full buffer, **log and drop** to avoid blocking the router.
  - Update `subscribe-events`/`events->chan` docstring to highlight lifecycle + buffer semantics.

**Files:** `session.clj`

---

### 3) Bound notification/router queues
- **Problem:** `LinkedBlockingQueue` is unbounded; if router/dispatcher stalls, memory can grow without bound.
- **Fix:**
  - Replace `LinkedBlockingQueue` with a **bounded queue (size 4096)**.
  - Use `offer` with timeout; on full, **drop + log** (include queue size).
  - For `protocol` notification dispatcher, never block the read loop on queue writes.

**Files:** `protocol.clj`, `client.clj`

---

### 4) Control tool execution concurrency
- **Problem:** `handle-tool-call!` uses `async/thread` and can create many threads; channel-returning tools block with `<!!`.
- **Fix:**
  - Route tool execution through a **bounded executor** or `pipeline-blocking` with fixed parallelism.
  - Add a **tool timeout** (default **120000ms**) for channel-returning tools; return failure if exceeded.
  - Ensure exceptions/timeouts always return a normalized failure without leaking resources.

**Files:** `session.clj`, `client.clj` (config), possibly `tools.clj`

---

### 5) Helper lifecycle safety
- **Problem:** `helpers/query-seq` leaks sessions if consumer stops early.
- **Fix:**
  - Add explicit **`query-seq!`** (or similar) that accepts `:timeout-ms` / `:max-events` and **guarantees cleanup**.
  - Update docs to explain the lifecycle and recommend `query-chan` for async use.

**Files:** `helpers.clj`, docs

---

### 6) Tests & Verification
- Add tests for:
  - `send-async`/`<send!` releasing `send-lock` even if consumer stops early
  - Queue overflow behavior (drop + log)
  - Tool handler concurrency limit + timeout
- Run `bb test` and `./run-all-examples.sh`

---

## Notes / Open Decisions (Resolved)
- **Buffers/queues:** Sliding buffers, size **4096**, with drop+log on overflow.
- **Timeouts:** `<send!` default **300000ms**, tool timeout **120000ms**.
- **API exposure:** Expose these options publicly in APIs and docs.
