(ns github.copilot-sdk.send-and-wait-test
  "Deterministic regression coverage for `send-and-wait!` outcome races.

   Mirrors the upstream `nodejs/test/session-send-and-wait.test.ts` suite that
   guards the send/idle/error ordering contract. Instead of fixed sleeps these
   tests gate the real `session.send` RPC on the piped-stream mock server via a
   request hook, inject events while the send is in flight, then release the
   send and assert which outcome wins.

   The upstream contract these tests pin:

   - an early `session.error` observed while `send` is in flight is retained and
     surfaced once `send` completes;
   - an early `session.idle` (and any assistant message) is retained but does
     not produce a return before `send` completes;
   - a `send` RPC rejection wins over an earlier `session.error`;
   - the first terminal outcome (idle or error) observed wins;
   - the zero-timeout default is 60000ms, matching upstream `session.ts`."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as async]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.mock-server :as mock]))

;; -----------------------------------------------------------------------------
;; Fixture: a piped-stream mock server whose `session.send` RPC is gated on a
;; promise, mirroring upstream's `controlledSession` (a manually-resolved
;; sendRequest). While the send is parked we inject session events over the wire
;; so they buffer ahead of the send response in strict FIFO order.
;; -----------------------------------------------------------------------------

(defn- gated-send-context
  "Start a mock server + connected client with a gated `session.send`.

   Returns a map:
   - :client       connected CopilotClient
   - :session      a live CopilotSession
   - :session-id   its id
   - :server       the mock server (for injecting events)
   - :send-started promise delivered when the server receives `session.send`
   - :release      (fn) resolves the send RPC (default handler then runs)
   - :reject       (fn [msg]) rejects the send RPC with `msg`
   - :close        (fn) tears everything down"
  []
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)
        client (sdk/client {:auto-start? false})
        [in out] (mock/client-streams server)
        _ (client/connect-with-streams! client in out)
        copilot-session (sdk/create-session client {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id copilot-session)
        send-started (promise)
        ;; Delivered value drives the gate: :resolve → normal response;
        ;; {:reject msg} → server writes a JSON-RPC error for `session.send`.
        send-gate (promise)]
    (mock/set-request-hook!
     server
     (fn [method _params]
       (when (= method "session.send")
         (deliver send-started true)
         (let [gate @send-gate]
           (when-let [msg (:reject gate)]
             (throw (ex-info msg {:code -32000})))))
       nil))
    {:client client
     :session copilot-session
     :session-id session-id
     :server server
     :send-started send-started
     :release #(deliver send-gate :resolve)
     :reject (fn [msg] (deliver send-gate {:reject msg}))
     :close (fn []
              (try (sdk/stop! client) (catch Exception _))
              (mock/stop-mock-server! server))}))

(defn- inject-error!
  "Inject a `session.error` event over the wire (the shape a joined client's
   `session.log(_, {level: \"error\"})` produces)."
  [{:keys [server session-id]} message]
  (mock/send-session-event! server session-id "session.error"
                            {:errorType "notification" :message message}))

(defn- inject-idle!
  "Inject a `session.idle` event over the wire."
  ([ctx]
   (inject-idle! ctx {}))
  ([{:keys [server session-id]} data]
   (mock/send-session-event! server session-id "session.idle" data :ephemeral? true)))

;; -----------------------------------------------------------------------------
;; Timeout-selection harness (PAR-003 follow-up): drives `send-and-wait!` with a
;; stubbed `send!` that records the opts it receives and dispatches idle itself
;; (it runs after the mult tap, so idle is delivered deterministically). Every
;; `async/timeout` call is captured so a test can assert exactly which deadline
;; the wait armed -- or that none was armed for a disabled (nil) timeout. No
;; sleeps; the client is always torn down.
;; -----------------------------------------------------------------------------

(defn- capture-send-and-wait
  "Runs `(f session)` under instrumentation-free capture and returns
   {:timeouts <vec of ms passed to async/timeout>
    :send-opts <opts map the stubbed send! received>
    :result <return value of f>}."
  [f]
  (let [timeouts (atom [])
        send-opts (atom ::unset)
        real-timeout async/timeout
        client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "capture-session" {})
        session-id (sdk/session-id copilot-session)]
    (try
      (with-redefs [async/timeout (fn [^long ms] (swap! timeouts conj ms) (real-timeout ms))
                    session/send! (fn [_ opts]
                                    (reset! send-opts opts)
                                    (session/dispatch-event!
                                     client session-id
                                     {:type :copilot/session.idle :data {}})
                                    "msg")]
        (let [result (f copilot-session)]
          {:timeouts @timeouts
           :send-opts @send-opts
           :result result}))
      (finally
        (sdk/force-stop! client)))))

(deftest send-and-wait-honors-explicit-numeric-timeout-in-opts
  (testing "an explicit numeric :timeout-ms in opts arms that exact deadline and is not forwarded"
    (let [{:keys [timeouts send-opts result]}
          (capture-send-and-wait
           (fn [s] (session/send-and-wait! s {:prompt "hi" :timeout-ms 1234})))]
      (is (= [1234] timeouts)
          "the deadline must use the opts :timeout-ms, not the default")
      (is (nil? result))
      (is (map? send-opts))
      (is (not (contains? send-opts :timeout-ms))
          ":timeout-ms must be stripped before the underlying session.send"))))

(deftest send-and-wait-nil-timeout-in-opts-disables-deadline
  (testing "a nil :timeout-ms in opts arms no deadline channel at all"
    (let [{:keys [timeouts send-opts result]}
          (capture-send-and-wait
           (fn [s] (session/send-and-wait! s {:prompt "hi" :timeout-ms nil})))]
      (is (= [] timeouts)
          "nil :timeout-ms must not call async/timeout")
      (is (nil? result))
      (is (not (contains? send-opts :timeout-ms))))))

(deftest send-and-wait-nil-positional-timeout-disables-deadline
  (testing "a nil positional 3-arity timeout arms no deadline channel"
    (let [{:keys [timeouts result]}
          (capture-send-and-wait
           (fn [s] (session/send-and-wait! s {:prompt "hi"} nil)))]
      (is (= [] timeouts)
          "nil positional timeout must not call async/timeout")
      (is (nil? result)))))

;; -----------------------------------------------------------------------------
;; Upstream scenario 1: early session.error is retained until send completes.
;; -----------------------------------------------------------------------------

(deftest early-session-error-is-retained-while-send-is-in-flight
  (testing "a session.error arriving before send resolves surfaces once send completes"
    (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
      (try
        (let [pending (future
                        (try
                          (session/send-and-wait! session {:prompt "hi"} 5000)
                          (catch Exception e [:threw (ex-message e)])))]
          ;; Wait until the send RPC is parked server-side, then inject the error
          ;; while send() is still in flight (its idle race is not yet armed).
          (is (true? (deref send-started 2000 ::timeout)))
          (inject-error! ctx "MCP server failed to start")
          ;; Nothing is observed until send completes.
          (release)
          (is (= [:threw "MCP server failed to start"]
                 (deref pending 5000 ::timeout))))
        (finally (close))))))

;; -----------------------------------------------------------------------------
;; Upstream scenario 2: an early idle is preserved but does not return early.
;; -----------------------------------------------------------------------------

(deftest early-idle-is-preserved-until-send-completes
  (testing "an early session.idle does not produce a return before send completes"
    (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
      (try
        (let [pending (future (session/send-and-wait! session {:prompt "hi"} 5000))]
          (is (true? (deref send-started 2000 ::timeout)))
          (inject-idle! ctx)
          ;; send() is still parked, so the outcome cannot have been consumed yet.
          (is (not (realized? pending))
              "send-and-wait! must not return before send completes")
          (release)
          ;; No assistant message preceded idle in buffer order → returns nil.
          (is (nil? (deref pending 5000 ::timeout))))
        (finally (close))))))

(deftest autopilot-idle-is-not-terminal-for-send-and-wait
  (testing "send-and-wait! ignores autopilot idle and returns after the regular idle"
    (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
      (try
        (let [pending (future (session/send-and-wait! session {:prompt "hi"} 5000))]
          (is (true? (deref send-started 2000 ::timeout)))
          (inject-idle! ctx {:mode "autopilot"})
          (release)
          (let [result (deref pending 5000 ::timeout)]
            (is (= :copilot/assistant.message (:type result)))
            (is (= "Mock response to: hi" (get-in result [:data :content])))))
        (finally (close))))))

(deftest autopilot-idle-is-not-terminal-for-async-sends
  (testing "both async send paths remain open across autopilot idle"
    (doseq [[label start]
            [[:events #(session/send-async % {:prompt "hi" :timeout-ms 5000})]
             [:with-id #(-> (session/send-async-with-id %
                                                        {:prompt "hi" :timeout-ms 5000})
                            :events-ch)]]]
      (testing (name label)
        (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
          (try
            (let [events-ch (future (start session))]
              (is (true? (deref send-started 2000 ::timeout)))
              (inject-idle! ctx {:mode :autopilot})
              (release)
              (let [events-ch (deref events-ch 5000 ::timeout)
                    events (loop [acc []]
                             (let [[event port] (async/alts!! [events-ch (async/timeout 5000)])]
                               (cond
                                 (not= port events-ch) ::timeout
                                 (nil? event) acc
                                 :else (recur (conj acc event)))))]
                (is (vector? events) "the event stream must close after regular idle")
                (is (some #(and (= :copilot/session.idle (:type %))
                                (#{"autopilot" :autopilot}
                                 (get-in % [:data :mode])))
                          events))
                (is (some #(= :copilot/assistant.message (:type %)) events))
                (is (= :copilot/session.idle (:type (last events))))
                (is (nil? (get-in (last events) [:data :mode])))))
            (finally (close))))))))

(deftest terminal-idle-recognizes-both-autopilot-representations
  (is (false? (@#'session/terminal-idle-event?
               {:type :copilot/session.idle :data {:mode "autopilot"}})))
  (is (false? (@#'session/terminal-idle-event?
               {:type :copilot/session.idle :data {:mode :autopilot}})))
  (is (true? (@#'session/terminal-idle-event?
              {:type :copilot/session.idle :data {}}))))

;; -----------------------------------------------------------------------------
;; Upstream scenario 3: a send rejection wins over an earlier session.error.
;; -----------------------------------------------------------------------------

(deftest send-rejection-wins-over-earlier-session-error
  (testing "a rejected session.send propagates even when a session.error arrived first"
    (let [{:keys [session reject send-started close] :as ctx} (gated-send-context)]
      (try
        (let [pending (future
                        (try
                          (session/send-and-wait! session {:prompt "hi"} 5000)
                          (catch Exception e [:threw (ex-message e)])))]
          (is (true? (deref send-started 2000 ::timeout)))
          (inject-error! ctx "session error")
          (reject "send failed")
          (is (= [:threw "send failed"]
                 (deref pending 5000 ::timeout))
              "the send rejection, not the earlier session.error, must win"))
        (finally (close))))))

;; -----------------------------------------------------------------------------
;; Upstream scenario 4: the first terminal outcome observed wins.
;; -----------------------------------------------------------------------------

(deftest first-terminal-outcome-wins-idle-first
  (testing "idle observed before a later error returns nil (idle wins)"
    (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
      (try
        (let [pending (future (session/send-and-wait! session {:prompt "hi"} 5000))]
          (is (true? (deref send-started 2000 ::timeout)))
          (inject-idle! ctx)
          (inject-error! ctx "later error")
          (release)
          (is (nil? (deref pending 5000 ::timeout))))
        (finally (close))))))

(deftest first-terminal-outcome-wins-error-first
  (testing "an error observed before a later idle throws the error (error wins)"
    (let [{:keys [session release send-started close] :as ctx} (gated-send-context)]
      (try
        (let [pending (future
                        (try
                          (session/send-and-wait! session {:prompt "hi"} 5000)
                          (catch Exception e [:threw (ex-message e)])))]
          (is (true? (deref send-started 2000 ::timeout)))
          (inject-error! ctx "first error")
          (inject-idle! ctx)
          (release)
          (is (= [:threw "first error"]
                 (deref pending 5000 ::timeout))))
        (finally (close))))))

;; -----------------------------------------------------------------------------
;; PAR-003: the zero-timeout default aligns to upstream's 60000ms.
;;
;; Isolated from the RPC path: `send!` is stubbed so the only `async/timeout`
;; call inside `send-and-wait!` is the loop deadline, making the captured value
;; the default under test.
;;
;; Determinism: `send-and-wait!` taps the event mult *before* calling `send!`,
;; so the stubbed `send!` dispatches `session.idle` itself. That guarantees idle
;; is emitted after the tap is installed and cannot be dropped, letting the call
;; run synchronously and return the moment idle is observed -- no thread-timing
;; assumptions and no fixed sleeps. A `finally` tears the client down so an
;; unexpected failure cannot strand a 60s deadline wait or a live tap.
;; -----------------------------------------------------------------------------

(deftest default-timeout-matches-upstream-60s
  (testing "the zero-timeout send-and-wait! deadline is 60000ms"
    (let [captured (atom [])
          real-timeout async/timeout
          client (sdk/client {:auto-start? false})
          copilot-session (session/create-session client "timeout-session" {})
          session-id (sdk/session-id copilot-session)]
      (try
        (with-redefs [async/timeout (fn [^long ms] (swap! captured conj ms) (real-timeout ms))
                      session/send! (fn [_ _]
                                      ;; Invoked after the mult tap, so this idle
                                      ;; is guaranteed to reach the waiting loop.
                                      (session/dispatch-event!
                                       client session-id
                                       {:type :copilot/session.idle :data {}})
                                      "msg")]
          (is (nil? (session/send-and-wait! copilot-session {:prompt "hi"}))
              "idle with no preceding assistant message returns nil"))
        (is (= [60000] @captured)
            "the sole deadline timeout must be the 60000ms default")
        (is (not-any? #{300000 180000} @captured)
            "the prior 300000/180000 defaults must be gone")
        (finally
          (sdk/force-stop! client))))))

;; -----------------------------------------------------------------------------
;; ASY-002 follow-up: a caller parked on the send-lock when the session is torn
;; down wakes with a closed lock (nil) and must settle on a consistent
;; "disconnected" outcome via `send!`'s own guard -- never a hang, and never a
;; silent proceed-to-send. This is distinct from the force-stop test that covers
;; a caller already past the lock and waiting on events (which fails with
;; "Event channel closed"); here the caller is still blocked acquiring the lock.
;; -----------------------------------------------------------------------------

(deftest parked-send-lock-caller-sees-consistent-disconnect-after-force-stop
  (testing "force-stop closing the send-lock wakes a parked caller into a disconnected error"
    (let [client (sdk/client {:auto-start? false})
          copilot-session (session/create-session client "parked-lock-session" {})
          send-lock (get-in @(:state client)
                            [:session-io "parked-lock-session" :send-lock])]
      ;; Drain the single lock token so a fresh send-and-wait! must park on it.
      (is (some? (async/<!! send-lock)))
      (let [pending (future
                      (try
                        (session/send-and-wait! copilot-session {:prompt "hi"} 5000)
                        :completed
                        (catch Exception e [:threw (ex-message e)])))]
        ;; The caller is now parked acquiring the lock (or about to). Tearing the
        ;; session down closes the lock, waking the caller with nil; send!'s guard
        ;; then produces a deterministic disconnected error regardless of the
        ;; check/park interleaving.
        (sdk/force-stop! client)
        (let [result (deref pending 5000 ::timeout)]
          (is (vector? result)
              "the parked caller must throw, not hang or complete a send")
          (is (= :threw (first result)))
          (is (re-find #"Session has been disconnected" (second result))
              "the outcome must be a consistent disconnected error"))))))
