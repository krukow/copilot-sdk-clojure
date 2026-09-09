(ns github.copilot-sdk.integration.message-source-test
  "Stable message-source contract from upstream PR #2573."
  (:require   [clojure.spec.alpha :as s]
              [clojure.test :refer [deftest is testing use-fixtures]]
              [github.copilot-sdk :as sdk]
              [github.copilot-sdk.integration.support
               :refer [*mock-server*
                       *test-client*
                       await-atom!
                       await-event-type!
                       with-mock-server]]
              [github.copilot-sdk.mock-server :as mock]
              [github.copilot-sdk.specs :as specs]))

(use-fixtures :each with-mock-server)

(def ^:private omitted ::omitted)

(def ^:private source-cases
  [{:label "omitted"
    :source omitted
    :wire-source omitted}
   {:label "user"
    :source :user
    :wire-source "user"}
   {:label "system"
    :source :system
    :wire-source "system"}
   {:label "empty agent id"
    :source {:agent-id ""}
    :wire-source "agent-"}
   {:label "opaque agent id"
    :source {:agent-id " Agent/reviewer "}
    :wire-source "agent- Agent/reviewer "}
   {:label "already-prefixed agent id"
    :source {:agent-id "agent-reviewer"}
    :wire-source "agent-agent-reviewer"}])

(def ^:private delivery-modes
  [omitted :enqueue :immediate])

(defn- send-opts
  [source mode]
  (cond-> {:prompt "hello"}
    (not= omitted source) (assoc :source source)
    (not= omitted mode) (assoc :mode mode)))

(defn- assert-wire-options
  [params source mode wire-source]
  (if (= omitted wire-source)
    (is (not (contains? params :source)))
    (is (= wire-source (:source params))))
  (if (= omitted mode)
    (is (not (contains? params :mode)))
    (is (= (name mode) (:mode params)))))

(deftest message-source-spec-contract
  (testing "stable message sources use one canonical Clojure shape"
    (doseq [{:keys [source]} (remove #(= omitted (:source %)) source-cases)]
      (is (s/valid? ::specs/message-source source)))
    (doseq [source [nil
                    false
                    true
                    ""
                    "user"
                    "agent-reviewer"
                    {}
                    {:agent-id nil}
                    {:agent-id 42}
                    {:agent-id "reviewer" :extra true}]]
      (is (not (s/valid? ::specs/message-source source))
          (str "must reject " (pr-str source))))))

(deftest send-message-source-wire-contract
  (let [requests (atom [])
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (when (= "session.send" method)
               (swap! requests conj params))))
        session (sdk/create-session
                 *test-client*
                 {:on-permission-request sdk/approve-all})]
    (doseq [{:keys [label source wire-source]} source-cases
            mode delivery-modes]
      (testing (str label " with delivery mode " mode)
        (sdk/send! session (send-opts source mode))
        (assert-wire-options (last @requests) source mode wire-source)))))

(deftest async-message-source-wire-contract
  (let [requests (atom [])
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (when (= "session.send" method)
               (swap! requests conj params))))
        session (sdk/create-session
                 *test-client*
                 {:on-permission-request sdk/approve-all})]
    (doseq [{:keys [label source wire-source]} source-cases
            mode delivery-modes]
      (testing (str label " with delivery mode " mode)
        (let [request-count (count @requests)
              events-ch (sdk/send-async session (send-opts source mode))]
          (await-atom! requests
                       #(> (count %) request-count)
                       "async session.send request"
                       2000)
          (assert-wire-options (last @requests) source mode wire-source)
          (await-event-type! events-ch :copilot/session.idle 2000))))))

(deftest message-source-coexists-with-send-options
  (let [seen (atom nil)
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (when (= "session.send" method)
               (reset! seen params))))
        session (sdk/create-session
                 *test-client*
                 {:on-permission-request sdk/approve-all})]
    (sdk/send! session
               {:prompt "model prompt"
                :source {:agent-id "reviewer"}
                :attachments [{:type :file :path "/tmp/context.txt"}]
                :mode :immediate
                :agent-mode :plan
                :display-prompt "Visible prompt"
                :request-headers {"X-Trace-Id" "trace-1"}})
    (is (= "agent-reviewer" (:source @seen)))
    (is (= [{:type "file" :path "/tmp/context.txt"}]
           (:attachments @seen)))
    (is (= "immediate" (:mode @seen)))
    (is (= "plan" (:agentMode @seen)))
    (is (= "Visible prompt" (:displayPrompt @seen)))
    (is (= "trace-1"
           (get-in @seen [:requestHeaders (keyword "X-Trace-Id")])))))

(deftest explicit-nil-message-source-is-rejected
  (let [requests (atom [])
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (when (= "session.send" method)
               (swap! requests conj params))))
        session (sdk/create-session
                 *test-client*
                 {:on-permission-request sdk/approve-all})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid send options"
         (sdk/send! session {:prompt "hello" :source nil})))
    (is (empty? @requests))))

(deftest message-source-preserves-trace-context
  (let [seen (atom nil)
        _ (mock/set-request-hook!
           *mock-server*
           (fn [method params]
             (when (= "session.send" method)
               (reset! seen params))))
        trace {:traceparent
               "00-fedcba0987654321fedcba0987654321-abcdef1234567890-01"
               :tracestate "vendor=source"}
        sdk-client
        (assoc *test-client* :on-get-trace-context (constantly trace))
        session
        (sdk/create-session
         sdk-client
         {:session-id "trace-session"
          :on-permission-request sdk/approve-all})]
    (sdk/send! session {:prompt "hello" :source :system})
    (is (= "system" (:source @seen)))
    (is (= (:traceparent trace) (:traceparent @seen)))
    (is (= (:tracestate trace) (:tracestate @seen)))))

(deftest message-source-round-trips-on-live-and-historical-events
  (let [session (sdk/create-session
                 *test-client*
                 {:on-permission-request sdk/approve-all})
        session-id (sdk/session-id session)
        live-events (sdk/subscribe-events session)
        event-data {:content "hello"
                    :source "agent-reviewer"}]
    (try
      (mock/send-session-event!
       *mock-server*
       session-id
       :user.message
       event-data)
      (let [event (await-event-type!
                   live-events
                   :copilot/user.message
                   2000)]
        (is (= "agent-reviewer" (get-in event [:data :source])))
        (is (s/valid? ::specs/user.message-data (:data event))))

      (mock/set-session-messages!
       *mock-server*
       session-id
       [{:type "user.message"
         :id "event-1"
         :timestamp "2026-09-09T12:00:00Z"
         :parentId nil
         :data event-data}])
      (let [[event] (sdk/get-messages session)]
        (is (= :copilot/user.message (:type event)))
        (is (= "agent-reviewer" (get-in event [:data :source])))
        (is (s/valid? ::specs/user.message-data (:data event))))
      (finally
        (sdk/unsubscribe-events! session live-events)))))
