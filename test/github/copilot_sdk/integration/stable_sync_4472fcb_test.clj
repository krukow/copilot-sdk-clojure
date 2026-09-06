(ns github.copilot-sdk.integration.stable-sync-4472fcb-test
  "Stable upstream parity after the 811adc oracle."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* await-value! with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]))

(use-fixtures :each with-mock-server)

(def ^:private stable-delta-report
  (-> "resources/stable_upstream_delta_4472fcb.edn"
      io/resource
      slurp
      edn/read-string))

(def ^:private decision-context
  {:outcome :auto-approved
   :source :host-policy
   :surface :sdk})

(def ^:private historical-decision-context
  (get-in stable-delta-report
          [:historical-contracts :permission-decision-context]))

(deftest stable-delta-inventory-is-complete-and-internally-consistent
  (let [source-symbols (:source-symbols stable-delta-report)
        stable-deltas (:stable-deltas stable-delta-report)
        stable-ids (set (map :id stable-deltas))
        classifications (:commit-classifications stable-delta-report)]
    (is (= "811adc050a82d823cc6f6891576f30058554af8d"
           (get-in stable-delta-report [:upstream :base-commit])))
    (is (= "4472fcb9ad342b02aae14ccc3cf1c8083603863e"
           (get-in stable-delta-report [:upstream :target-commit])))
    (is (= "v1.0.11"
           (get-in stable-delta-report [:upstream :target-release])))
    (is (= "1.0.80"
           (get-in stable-delta-report [:upstream :schema-version])))
    (is (= (:stable-delta-ids stable-delta-report) stable-ids))
    (is (empty? (:unclassified-stable stable-delta-report)))
    (is (= 12 (count classifications)))
    (is (= (set (map :commit classifications))
           #{"f0c89d176399b24d68d65814009317644d345fca"
             "3b0d55633c4821a45357616baba6b7f4b51ccada"
             "1935fd3029f86f2b9f077dffc4cce1d2807886a1"
             "a550258d5c37bd662197536992a23d633bfe5804"
             "93f3542173e92b039699aa2c1eb8324fa71256ec"
             "85ededd134f0d6411de3c044c52236f801a1e5c2"
             "e2ee46f8d80cf11d0339cc95fd9ffeefeba7bc5d"
             "9d67eb76e93367164a1e11bf0b2fd0833047db45"
             "a67956bb15135c27e3baf55d04b63e5d65dea729"
             "18002a81fa52d006589b813441f98b53e118fe8a"
             "731317c2c4006e73c3d41b68b47efa5b0ce63fe2"
             "4472fcb9ad342b02aae14ccc3cf1c8083603863e"}))
    (is (every? #(contains? #{:stable :experimental :internal
                              :generated-only :language-specific}
                            (:classification %))
                classifications))
    (is (every? #(and (= :stable (:classification %))
                      (= :ported (:status %))
                      (seq (:evidence %)))
                stable-deltas))
    (is (every? #(every? (fn [evidence-id]
                           (let [{:keys [file symbol]}
                                 (get source-symbols evidence-id)]
                             (and (string? file)
                                  (.startsWith file "nodejs/src/")
                                  (string? symbol)
                                  (not (.isBlank symbol)))))
                         (:evidence %))
                stable-deltas))))

(deftest builtin-plugin-directory-contract
  (testing "client option is closed, absolute, and nil-rejecting"
    (is (s/valid? ::specs/client-options
                  {:builtin-plugin-directories []}))
    (is (s/valid? ::specs/client-options
                  {:builtin-plugin-directories ["/opt/copilot/plugins"]}))
    (is (not (s/valid? ::specs/client-options
                       {:builtin-plugin-directories nil})))
    (is (not (s/valid? ::specs/client-options
                       {:builtin-plugin-directories ["relative/plugins"]}))))

  (testing "constructor rejects relative paths before startup"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"builtin-plugin-directories.*absolute"
         (sdk/client {:auto-start? false
                      :builtin-plugin-directories ["relative/plugins"]}))))

  (testing "startup helper omits absent/empty and sends the complete non-empty set"
    (let [register (ns-resolve 'github.copilot-sdk.client
                               'register-builtin-plugin-directories!)
          requests (atom [])]
      (is (some? register))
      (when register
        (with-redefs [protocol/send-request!
                      (fn [_connection method params]
                        (swap! requests conj [method params])
                        {})]
          (doseq [options [{:auto-start? false}
                           {:auto-start? false
                            :builtin-plugin-directories []}
                           {:auto-start? false
                            :builtin-plugin-directories
                            ["/opt/copilot/one" "/opt/copilot/two"]}]]
            (let [copilot-client (sdk/client options)]
              (swap! (:state copilot-client)
                     assoc :connection-io ::connection)
              (register copilot-client)))))
      (is (= [["plugins.builtin.set"
               {:paths ["/opt/copilot/one" "/opt/copilot/two"]}]]
             @requests))))

  (testing "startup registers plugins after handshake and before routing"
    (let [calls (atom [])
          copilot-client
          (sdk/client {:auto-start? false
                       :cli-url "localhost:1234"
                       :builtin-plugin-directories ["/opt/copilot/plugins"]})
          register
          (ns-resolve 'github.copilot-sdk.client
                      'register-builtin-plugin-directories!)]
      (with-redefs-fn
        {(var client/connect-tcp!)
         (fn [c]
           (swap! calls conj :connect)
           (swap! (:state c)
                  assoc
                  :connection {:running? true}
                  :connection-io ::connection))
         (var client/verify-protocol-version!)
         (fn [_] (swap! calls conj :verify))
         register
         (fn [_] (swap! calls conj :plugins))
         (var client/start-notification-router!)
         (fn [_] (swap! calls conj :router))
         (var client/setup-request-handler!)
         (fn [_] (swap! calls conj :request-handler))}
        #(sdk/start! copilot-client))
      (is (= [:connect :verify :plugins :router :request-handler]
             @calls))
      (is (= :connected (sdk/state copilot-client)))))

  (testing "registration failure remains a startup failure with cleanup"
    (let [cleanup (atom [])
          copilot-client
          (sdk/client {:auto-start? false
                       :cli-url "localhost:1234"
                       :builtin-plugin-directories ["/opt/copilot/plugins"]})]
      (with-redefs-fn
        {(var client/connect-tcp!)
         (fn [c]
           (swap! (:state c)
                  assoc
                  :connection (protocol/initial-connection-state)
                  :connection-io ::connection))
         (var client/verify-protocol-version!) (fn [_] nil)
         (var client/release-transport!)
         (fn [_ options]
           (swap! cleanup conj options)
           [])
         (var protocol/send-request!)
         (fn [_ method _]
           (when (= "plugins.builtin.set" method)
             (throw (ex-info "plugin registration failed" {})))
           {})}
        #(is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"plugin registration failed"
              (sdk/start! copilot-client))))
      (is (= [{:process :forcible}] @cleanup))
      (is (= :error (sdk/state copilot-client)))))

  (testing "force-stop failure remains observable without replacing registration failure"
    (let [cleanup-error (ex-info "force-stop failed" {})
          copilot-client
          (sdk/client {:auto-start? false
                       :cli-url "localhost:1234"
                       :builtin-plugin-directories ["/opt/copilot/plugins"]})
          startup-error
          (with-redefs-fn
            {(var client/connect-tcp!)
             (fn [c]
               (swap! (:state c)
                      assoc
                      :connection (protocol/initial-connection-state)
                      :connection-io ::connection))
             (var client/verify-protocol-version!) (fn [_] nil)
             (var client/force-stop!)
             (fn [c]
               (swap! (:state c) assoc :stopping? true)
               (throw cleanup-error))
             (var protocol/send-request!)
             (fn [_ method _]
               (when (= "plugins.builtin.set" method)
                 (throw (ex-info "plugin registration failed" {})))
               {})}
            #(try
               (sdk/start! copilot-client)
               nil
               (catch Exception error
                 error)))]
      (is (= "plugin registration failed" (ex-message startup-error)))
      (is (= 1 (count (.getSuppressed ^Throwable startup-error))))
      (is (identical?
           cleanup-error
           (ex-cause (first (.getSuppressed ^Throwable startup-error)))))
      (is (= :error (sdk/state copilot-client))))))

(deftest historical-permission-decision-context-vocabulary-is-pinned
  (is (= #{:auto-approved :autopilot-denied :prompted-user}
         (:outcomes historical-decision-context)))
  (is (= #{:judge-recommendation
           :human-response
           :host-policy
           :unattended-fallback}
         (:sources historical-decision-context)))
  (is (= #{:tui :prompt-mode :copilot-app :sdk}
         (:surfaces historical-decision-context)))
  (is (not (contains? (:sources historical-decision-context)
                      :assisted-approval))
      "assisted_approval was introduced after the 4472fcb target")
  (is (not (contains? (:surfaces historical-decision-context)
                      :acp))
      "the ACP surface was introduced after the 4472fcb target"))

(deftest attributed-permission-result-contract
  (testing "helper constructs and replaces attribution without nesting"
    (let [attributed
          (sdk/attributed-permission-result
           {:kind :approve-once}
           decision-context)]
      (is (= {:kind :attributed
              :result {:kind :approve-once}
              :decision-context decision-context}
             attributed))
      (is (true? (sdk/attributed-permission-result? attributed)))
      (is (false? (sdk/attributed-permission-result?
                   {:kind :approve-once})))
      (is (false? (sdk/attributed-permission-result?
                   {:kind :attributed})))
      (is (= {:kind :attributed
              :result {:kind :approve-once}
              :decision-context
              {:outcome :prompted-user
               :source :human-response
               :surface :copilot-app}}
             (sdk/attributed-permission-result
              attributed
              {:outcome :prompted-user
               :source :human-response
               :surface :copilot-app})))))

  (testing "specs reject malformed attribution"
    (is (s/valid? ::specs/attributed-permission-result
                  {:kind :attributed
                   :result {:kind :approve-once}
                   :decision-context decision-context}))
    (is (not (s/valid? ::specs/attributed-permission-result
                       {:kind :attributed
                        :result {:kind :approve-once}
                        :decision-context
                        {:outcome :auto-approved
                         :source :unknown
                         :surface :sdk}})))
    (is (not (s/valid? ::specs/attributed-permission-result
                       {:kind :attributed
                        :result {:kind :approve-once}
                        :decision-context
                        (assoc decision-context :extra true)}))))

  (testing "decision-context enums map to exact wire strings"
    (doseq [[context expected]
            [[decision-context
              {:outcome "auto_approved"
               :source "host_policy"
               :surface "sdk"}]
             [{:outcome :autopilot-denied
               :source :assisted-approval
               :surface :prompt-mode}
              {:outcome "autopilot_denied"
               :source "assisted_approval"
               :surface "prompt_mode"}]
             [{:outcome :prompted-user
               :source :human-response
               :surface :copilot-app}
              {:outcome "prompted_user"
               :source "human_response"
               :surface "copilot_app"}]
             [{:outcome :auto-approved
               :source :unattended-fallback
               :surface :tui}
              {:outcome "auto_approved"
               :source "unattended_fallback"
               :surface "tui"}]
             [{:outcome :auto-approved
               :source :host-policy
               :surface :acp}
              {:outcome "auto_approved"
               :source "host_policy"
               :surface "acp"}]]]
      (let [session
            (sdk/create-session
             *test-client*
             {:on-permission-request
              (fn [_ _]
                (sdk/attributed-permission-result
                 {:kind :approve-once}
                 context))})
            response
            (async/<!!
             (session/handle-permission-request!
              *test-client* (sdk/session-id session)
              {:permission-kind :shell}))]
        (is (= expected (:decision-context response))))))

  (testing "the current spec rejects the historical judge spelling"
    (is (not (s/valid? ::specs/permission-decision-context
                       {:outcome :auto-approved
                        :source :judge-recommendation
                        :surface :sdk}))))

  (testing "attribution is a sibling of result and legacy payload remains unchanged"
    (doseq [[label handler expected]
            [[:plain
              (fn [_ _] {:kind :approve-once})
              {:sessionId "SESSION"
               :requestId "perm-plain"
               :result {:kind "approve-once"}}]
             [:historical-wrapped
              (fn [_ _]
                {:result {:kind :approve-once}
                 :decision-context {:unvalidated true}})
              {:sessionId "SESSION"
               :requestId "perm-historical-wrapped"
               :result {:kind "approve-once"}}]
             [:attributed
              (fn [_ _]
                (sdk/attributed-permission-result
                 {:kind :approve-once}
                 decision-context))
              {:sessionId "SESSION"
               :requestId "perm-attributed"
               :result {:kind "approve-once"}
               :decisionContext {:outcome "auto_approved"
                                 :source "host_policy"
                                 :surface "sdk"}}]
             [:attributed-async
              (fn [_ _]
                (async/to-chan!
                 [(sdk/attributed-permission-result
                   {:kind :approve-once}
                   decision-context)]))
              {:sessionId "SESSION"
               :requestId "perm-attributed-async"
               :result {:kind "approve-once"}
               :decisionContext {:outcome "auto_approved"
                                 :source "host_policy"
                                 :surface "sdk"}}]]]
      (let [request-id (str "perm-" (name label))
            requests (atom [])
            rpc-latch (java.util.concurrent.CountDownLatch. 1)
            _ (mock/set-request-hook!
               *mock-server*
               (fn [method params]
                 (when (= "session.permissions.handlePendingPermissionRequest"
                          method)
                   (swap! requests conj params)
                   (.countDown rpc-latch))))
            session (sdk/create-session
                     *test-client*
                     {:on-permission-request handler})
            session-id (sdk/session-id session)]
        (swap! (:state *test-client*)
               assoc :negotiated-protocol-version 3)
        (mock/send-v3-broadcast-event!
         *mock-server* session-id "permission.requested"
         {:requestId request-id
          :permissionRequest {:permissionKind "shell"
                              :fullCommandText "echo test"}})
        (is (.await rpc-latch 5 java.util.concurrent.TimeUnit/SECONDS))
        (is (= (assoc expected :sessionId session-id :requestId request-id)
               (first @requests))))))

  (testing "attributed no-result still suppresses the response RPC"
    (let [requests (atom [])
          processing-completed (promise)
          real-handler
          (var-get (var client/handle-v3-permission-requested!))
          _ (mock/set-request-hook!
             *mock-server*
             (fn [method params]
               (swap! requests conj [method params])))
          session
          (sdk/create-session
           *test-client*
           {:on-permission-request
            (fn [_ _]
              (sdk/attributed-permission-result
               {:kind :no-result}
               decision-context))})
          session-id (sdk/session-id session)]
      (swap! (:state *test-client*)
             assoc :negotiated-protocol-version 3)
      (reset! requests [])
      (with-redefs-fn
        {(var client/handle-v3-permission-requested!)
         (fn [copilot-client id event]
           (let [result-ch (real-handler copilot-client id event)]
             (async/take! result-ch
                          (fn [_] (deliver processing-completed true)))
             result-ch))}
        #(do
           (mock/send-v3-broadcast-event!
            *mock-server* session-id "permission.requested"
            {:requestId "perm-no-result"
             :permissionRequest {:permissionKind "shell"
                                 :fullCommandText "echo test"}})
           (is (true? (await-value! processing-completed
                                    "attributed no-result processing"
                                    1000)))
           (is (empty?
                (filter
                 (fn [[method _]]
                   (= "session.permissions.handlePendingPermissionRequest"
                      method))
                 @requests))))))))

(deftest attributed-permission-facade-is-instrumented
  (let [instrument-all!
        (requiring-resolve 'github.copilot-sdk.instrument/instrument-all!)
        unstrument-all!
        (requiring-resolve 'github.copilot-sdk.instrument/unstrument-all!)]
    (instrument-all!)
    (try
      (is (= {:kind :attributed
              :result {:kind :approve-once}
              :decision-context decision-context}
             (sdk/attributed-permission-result
              {:kind :approve-once}
              decision-context)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (sdk/attributed-permission-result
                    {:kind :approve-once}
                    {:outcome :auto-approved
                     :source :unknown
                     :surface :sdk})))
      (finally
        (unstrument-all!)))))
