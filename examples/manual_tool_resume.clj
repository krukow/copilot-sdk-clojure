(ns manual-tool-resume
  "Manually resolve pending tool calls across resume (upstream PR #1308).

   A tool declared without a `:handler` is *declaration-only*: the runtime
   advertises it to the model but, instead of executing anything locally, leaves
   the call pending and surfaces it to the application.

   To keep the *permission* prompt pending as well, lifecycle 1 creates the
   session with a deferring `:on-permission-request` handler that returns
   `{:kind :no-result}` (see `defer-permission`). On `create`, registering any
   handler makes the session send `requestPermission: true`, so the runtime
   surfaces `permission.requested` rather than auto-approving the call;
   `:no-result` then declines to answer, leaving the request pending. (Omitting
   the handler on `create` sends `requestPermission: false`, which tells the
   runtime to auto-resolve — so a declaration-only tool would run immediately and
   no prompt would surface.) `resume` derives `requestPermission: true` even
   without a handler, so lifecycles 2-3 re-surface the pending work and resolve
   it by hand without a handler competing for it.

   The application drives the work forward by hand, and the pending requests
   survive across separate client lifecycles via `resume-session` with
   `:continue-pending-work? true`:

   1. Lifecycle 1 — create a session, ask the model to use the tool, wait for the
      pending permission request, then suspend.
   2. Lifecycle 2 — resume, approve the pending permission with
      `handle-pending-permission-request!`, wait for the pending tool request,
      then suspend.
   3. Lifecycle 3 — resume, supply the tool result with
      `handle-pending-tool-call!`, and read the model's final answer.

   This is the SDK-driven analogue of the upstream `manual_tool_resume` sample.
   It demonstrates manual pending-work resolution across **suspend/resume**. Each
   suspending lifecycle ends with `force-stop!` (a hard SIGKILL of the client),
   matching the upstream sample's `forceStop()`: on this CLI, force-stopping
   leaves the in-flight permission/tool call genuinely pending so the next
   `resume-session` (with `:continue-pending-work? true`) can continue it. A
   graceful `disconnect!` instead lets the runtime auto-resolve the pending work,
   which would drop the state the later lifecycles depend on.

   After each `force-stop!` the flow waits one second (`pause!`, printing
   \"Simulating time passing...\") before starting the next client — mirroring
   the upstream Node/Python/Rust samples exactly. `run` executes the flow once
   and fails loudly if a pending request is lost; there is no retry or fallback."
  (:require [clojure.core.async :refer [alts!! timeout]]
            [github.copilot-sdk :as copilot :refer [evt]]
            [github.copilot-sdk.tool-set :as tool-set]))

;; See examples/README.md for usage

(def tool
  (copilot/define-tool
    "manual_resume_status"
    {:description (str "Looks up a status value. The SDK consumer supplies the "
                       "result manually.")
     :parameters {:type "object"
                  :properties {:id {:type "string"
                                    :description "Identifier to look up"}}
                  :required ["id"]}}))
;; No :handler — the runtime leaves the call pending for manual resolution.

(defn defer-permission
  "Permission handler that intentionally declines to answer.

   Registering any `:on-permission-request` handler makes the session send
   `requestPermission: true`, so the runtime surfaces the `permission.requested`
   event instead of auto-approving. Returning `{:kind :no-result}` then declines
   to resolve it inline, leaving the request pending for the application to
   approve by hand later via `handle-pending-permission-request!`."
  [_request _ctx]
  {:kind :no-result})

(defn- wait-for
  "Block (up to 120s) until an event of `type-kw` (optionally matching `pred`)
   arrives on the freshly-subscribed channel `ch`, then unsubscribe and return
   it. Subscribe BEFORE triggering the work so the event cannot be missed.
   Throws if the channel closes or the timeout elapses first."
  [session type-kw ch & [pred]]
  (let [deadline (timeout 120000)]
    (try
      (loop []
        (let [[event port] (alts!! [ch deadline] :priority true)]
          (cond
            (= port deadline)
            (throw (ex-info "Timed out waiting for session event" {:event-type type-kw}))

            (nil? event)
            (throw (ex-info "Event channel closed before expected event arrived"
                            {:event-type type-kw}))

            (and (= (:type event) type-kw) (or (nil? pred) (pred event)))
            event

            :else (recur))))
      (finally
        (copilot/unsubscribe-events! session ch)))))

(defn- resolve-pending!
  "Throw if a `handle-pending-*` call did not succeed. The whole point of the
   example is that the original request ids resolve after resume, so a silent
   `{:success false}` must surface loudly."
  [result what]
  (when-not (:success result)
    (throw (ex-info (str "Failed to resolve pending " what) {:result result})))
  result)

(defn- with-suspended-client
  "Run `(f client)` on a fresh, started client, then hard-stop it (SIGKILL).

   The hard stop (rather than a graceful `stop!`/`disconnect!`) is deliberate:
   on this CLI it leaves the in-flight pending request intact for the next
   `resume-session`, whereas a graceful teardown lets the runtime auto-resolve
   it. `with-client` cannot be used here because its `stop!`-on-exit would run
   *after* the hard stop and re-trigger that graceful auto-resolution."
  [f]
  (let [client (copilot/client)]
    (copilot/start! client)
    (try
      (f client)
      (finally
        (copilot/force-stop! client)))))

(defn- settle-pending!
  "Give the CLI time to durably persist the just-captured pending request before
   the client is force-stopped.

   Root cause of the flakiness this closes: the runtime emits the pending event
   (`permission.requested` / `external_tool.requested`) BEFORE it finishes
   writing that pending state to its on-disk session store, and the event stream
   goes silent afterward — there is no observable persistence signal to wait on.
   A `force-stop!` (SIGKILL) in that window drops the write, so the next
   `resume-session` cannot continue the request. Upstream's async runtimes hide
   this race behind their own scheduling latency; the synchronous Clojure flow
   force-stops fast enough to expose it, so this bounded settle is required for a
   one-shot (no-retry) run."
  []
  (Thread/sleep 1000))

(defn- pause!
  "Simulate time passing between client lifecycles: prints \"Simulating time
   passing...\" and waits one second, mirroring the upstream Node/Python/Rust
   samples' `pause()` after each `force-stop!`. Durability across the boundary is
   ensured by `settle-pending!` before the stop, not by this pause."
  []
  (println "Simulating time passing...")
  (Thread/sleep 1000))

(defn run
  "Drive the three-lifecycle manual pending-work resolution across suspend/resume.

   Runs the flow exactly once and fails loudly (throws) if a pending request is
   lost across a suspend/resume boundary — there is no retry or fallback. After
   capturing each pending event the flow calls `settle-pending!` so the runtime
   durably persists it before the `with-suspended-client` `force-stop!`, then a
   1-second `pause!` before the next client mirrors the upstream samples'
   lifecycle."
  [{:keys [model] :or {model "claude-haiku-4.5"}}]
  (let [config {:model model
                :tools [tool]
                :available-tools [(tool-set/custom (:tool-name tool))]}
        ;; Lifecycle 1: ask for the tool, capture the pending permission, suspend.
        {:keys [session-id permission-id]}
        (with-suspended-client
          (fn [client]
            (let [session (copilot/create-session
                           client
                          ;; Deferring handler => requestPermission:true on create,
                          ;; so the runtime surfaces (and leaves pending) the
                          ;; permission prompt instead of auto-resolving it. Resume
                          ;; below derives requestPermission:true WITHOUT a handler,
                          ;; so the pending request re-surfaces for manual approval
                          ;; and no handler competes with it.
                           (assoc config :on-permission-request defer-permission))
                 ;; Subscribe BEFORE sending so the permission event cannot be missed.
                  permission-ch (copilot/subscribe-events session)]
              (println "Lifecycle 1: asking the model to use the declaration-only tool...")
              (copilot/send! session {:prompt (str "Use the manual_resume_status tool with id "
                                                   "'alpha', then tell me the status.")})
              (let [permission (wait-for session (evt :permission.requested) permission-ch)
                    permission-id (get-in permission [:data :request-id])]
                (println "  pending permission request:" permission-id)
                ;; Let the runtime durably persist the pending permission before
                ;; the enclosing `with-suspended-client` force-stops the client.
                (settle-pending!)
                {:session-id (copilot/session-id session)
                 :permission-id permission-id}))))
        _ (pause!)

        ;; Lifecycle 2: resume, approve the permission, capture the pending tool call, suspend.
        tool-request-id
        (with-suspended-client
          (fn [client]
            (let [session (copilot/resume-session
                           client session-id (assoc config :continue-pending-work? true))
                 ;; Subscribe BEFORE approving so the tool request cannot be missed.
                  tool-ch (copilot/subscribe-events session)]
              (println "Lifecycle 2: resuming and approving the pending permission...")
              (resolve-pending!
               (copilot/handle-pending-permission-request!
                session {:request-id permission-id :result {:kind :approve-once}})
               "permission request")
              (let [tool-event (wait-for session (evt :external_tool.requested) tool-ch
                                         #(= (get-in % [:data :tool-name]) "manual_resume_status"))
                    tool-request-id (get-in tool-event [:data :request-id])]
                (println "  pending tool call:" tool-request-id)
                ;; Let the runtime durably persist the pending tool call before
                ;; the enclosing `with-suspended-client` force-stops the client.
                (settle-pending!)
                tool-request-id))))
        _ (pause!)

        ;; Lifecycle 3: resume, supply the tool result by hand, read the final answer.
        answer
        (with-suspended-client
          (fn [client]
            (let [session (copilot/resume-session
                           client session-id (assoc config :continue-pending-work? true))
                 ;; Subscribe BEFORE resolving so the answer cannot be missed.
                  answer-ch (copilot/subscribe-events session)]
              (println "Lifecycle 3: resuming and supplying the tool result manually...")
              (resolve-pending!
               (copilot/handle-pending-tool-call!
                session {:request-id tool-request-id :result "MANUAL_STATUS_READY"})
               "tool call")
              (get-in (wait-for session (evt :assistant.message) answer-ch)
                      [:data :content]))))]
    (println "🤖:" answer)))
