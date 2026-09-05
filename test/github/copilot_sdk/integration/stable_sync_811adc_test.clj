(ns github.copilot-sdk.integration.stable-sync-811adc-test
  "Stable upstream parity through github/copilot-sdk 811adc."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* await-event-type! with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util]))

(use-fixtures :each with-mock-server)

(def ^:private stable-delta-report
  (-> "resources/stable_upstream_delta_811adc.edn"
      io/resource
      slurp
      edn/read-string))

(deftest stable-delta-inventory-is-complete-and-internally-consistent
  (let [source-symbols (:source-symbols stable-delta-report)
        stable-deltas (:stable-deltas stable-delta-report)
        stable-ids (set (map :id stable-deltas))
        exclusions (:experimental-exclusions stable-delta-report)]
    (is (= "811adc050a82d823cc6f6891576f30058554af8d"
           (get-in stable-delta-report [:upstream :target-commit])))
    (is (= "1.0.79-9"
           (get-in stable-delta-report [:upstream :schema-version])))
    (is (= (:stable-delta-ids stable-delta-report) stable-ids))
    (is (empty? (:unclassified-stable stable-delta-report)))
    (let [schema (with-open [reader (io/reader "schemas/session-events.schema.json")]
                   (json/read reader :key-fn keyword))]
      (is (= {:type "boolean"}
             (select-keys
              (get-in schema [:definitions :SubagentCompletedData
                              :properties :cancelled])
              [:type]))))
    (is (every? #(and (= :stable (:classification %))
                      (= :ported (:status %))
                      (seq (:evidence %)))
                stable-deltas))
    (is (every? #(every? (fn [evidence-id]
                           (let [{:keys [file symbol]} (get source-symbols evidence-id)]
                             (and (string? file)
                                  (.startsWith file "nodejs/src/")
                                  (string? symbol)
                                  (not (.isBlank symbol)))))
                         (:evidence %))
                stable-deltas))
    (is (every? #(and (#{:experimental :internal :language-only}
                       (:classification %))
                      (= :excluded (:status %))
                      (keyword? (:reason %)))
                exclusions))))

(defn- capture-session-request
  [scope config]
  (let [requests (atom [])
        seed (when (= :resume scope)
               (sdk/create-session *test-client* {}))
        method (case scope
                 :create "session.create"
                 :resume "session.resume")]
    (mock/set-request-hook!
     *mock-server*
     (fn [request-method params]
       (swap! requests conj [request-method params])))
    (try
      (case scope
        :create (sdk/create-session *test-client* config)
        :resume (sdk/resume-session *test-client* (sdk/session-id seed) config))
      {:params (some (fn [[request-method params]]
                       (when (= method request-method) params))
                     @requests)
       :requests @requests}
      (catch Throwable error
        {:error error :requests @requests}))))

(deftest file-change-tracking-create-resume-wire-matrix
  (doseq [scope [:create :resume]
          {:keys [name config expected]}
          [{:name :omitted :config {} :expected ::absent}
           {:name :false :config {:enable-file-change-tracking? false} :expected false}
           {:name :true :config {:enable-file-change-tracking? true} :expected true}
           {:name :nil :config {:enable-file-change-tracking? nil} :expected ::invalid}
           {:name :non-boolean :config {:enable-file-change-tracking? "true"} :expected ::invalid}]]
    (testing (str (name scope) " " name)
      (let [{:keys [error params]} (capture-session-request scope config)]
        (case expected
          ::invalid
          (do
            (is (instance? clojure.lang.ExceptionInfo error))
            (is (nil? params)))

          ::absent
          (do
            (is (nil? error))
            (is (not (contains? params :enableFileChangeTracking))))

          (do
            (is (nil? error))
            (is (= expected (:enableFileChangeTracking params)))
            (is (not (contains? params :enableFileChangeTracking?)))))))))

(deftest file-change-tracking-join-and-options-update-contract
  (doseq [value [false true]]
    (let [config {:enable-file-change-tracking? value}
          wire (util/clj->wire
                (#'client/build-resume-session-params "joined-session" config))]
      (is (s/valid? ::specs/join-session-config config))
      (is (= value (:enableFileChangeTracking wire)))))
  (is (not (s/valid? ::specs/join-session-config
                     {:enable-file-change-tracking? nil})))
  (is (not (contains?
            (#'client/build-session-options-update-patch
             *test-client*
             {:enable-file-change-tracking? true})
            :enable-file-change-tracking))))

(deftest file-tracking-events-use-public-event-stream
  (let [session (sdk/create-session *test-client* {})
        events (sdk/subscribe-events session)
        raw-events [{:id "00000000-0000-4000-8000-000000000001"
                     :timestamp "2026-08-13T08:00:00Z"
                     :parentId nil
                     :type "session.workspace_file_changed"
                     :data {:path "src/example.clj"
                            :operation "update"}}
                    {:id "00000000-0000-4000-8000-000000000002"
                     :timestamp "2026-08-13T08:00:01Z"
                     :parentId "00000000-0000-4000-8000-000000000001"
                     :ephemeral true
                     :type "session.snapshot_rewind"
                     :data {:upToEventId "00000000-0000-4000-8000-000000000001"
                            :eventsRemoved 1}}]]
    (try
      (doseq [event raw-events]
        (mock/send-notification! *mock-server* "session.event"
                                 {:sessionId (sdk/session-id session)
                                  :event event})
        (let [event-type (keyword "copilot" (:type event))
              received (await-event-type! events event-type 1000)]
          (is (= event-type (:type received)))
          (is (= (util/wire->clj (:data event)) (:data received)))))
      (finally
        (sdk/unsubscribe-events! session events)))))

(def ^:private subagent-completed-base
  {:toolCallId "tool-1"
   :agentName "explore"
   :agentDisplayName "Explore"})

(deftest subagent-cancelled-wire-to-idiom-contract
  (is (some? (s/get-spec ::specs/cancelled)))
  (doseq [[label cancelled present?]
          [[:omitted nil false]
           [:false false true]
           [:true true true]]]
    (testing (name label)
      (let [wire-data (cond-> subagent-completed-base
                        present? (assoc :cancelled cancelled))
            live (#'protocol/normalize-incoming
                  {:method "session.event"
                   :params {:event {:id "event-1"
                                    :timestamp "2026-08-13T08:00:00Z"
                                    :type "subagent.completed"
                                    :data wire-data}}})
            historical (#'protocol/normalize-incoming
                        {:id "response-1"
                         :result {:events [{:id "event-1"
                                            :timestamp "2026-08-13T08:00:00Z"
                                            :type "subagent.completed"
                                            :data wire-data}]}})
            live-data (get-in live [:params :event :data])
            historical-data (get-in historical [:result :events 0 :data])]
        (is (s/valid? ::generated-events/subagent.completed-data
                      (util/wire->clj wire-data)))
        (is (s/valid? ::specs/subagent.completed-data live-data))
        (if present?
          (do
            (is (= cancelled (:cancelled live-data)))
            (is (= cancelled (:cancelled historical-data))))
          (do
            (is (not (contains? live-data :cancelled)))
            (is (not (contains? historical-data :cancelled)))))))))

(def ^:private json-values
  [{:label :null :value nil}
   {:label :scalar :value "value"}
   {:label :array :value [1 true nil {"nested_key" "value"}]}
   {:label :object :value {:snake_key {"mixedCase" [1 false nil]}}}])

(def ^:private opaque-event-cases
  [{:event-type "external_tool.requested"
    :spec ::generated-events/external_tool.requested-data
    :base {:request-id "request-1"
           :session-id "session-1"
           :tool-call-id "tool-1"
           :tool-name "external"}
    :field :arguments
    :wire-field :arguments
    :object-key-path [:snake_key]
    :expected-key :snake_key}
   {:event-type "session.custom_notification"
    :spec ::generated-events/session.custom_notification-data
    :base {:source "extension"
           :name "notification"}
    :field :payload
    :wire-field :payload
    :object-key-path [:snake_key]
    :expected-key :snake_key}
   {:event-type "hook.end"
    :spec ::generated-events/hook.end-data
    :base {:hook-invocation-id "hook-1"
           :hook-type "postToolUse"
           :success true}
    :field :output
    :wire-field :output
    :object-key-path [:snake_key]
    :expected-key :snake_key}])

(deftest opaque-json-event-contracts
  (doseq [{:keys [event-type spec base field wire-field object-key-path expected-key]}
          opaque-event-cases
          {:keys [label value]} json-values]
    (testing (str event-type " " (name label))
      (let [idiom-data (assoc base field value)
            wire-data (util/clj->wire idiom-data)
            event {:id "event-1"
                   :timestamp "2026-08-13T08:00:00Z"
                   :type event-type
                   :data (assoc wire-data wire-field value)}
            live (#'protocol/normalize-incoming
                  {:method "session.event" :params {:event event}})
            historical (#'protocol/normalize-incoming
                        {:id "response-1" :result {:events [event]}})
            live-value (get-in live [:params :event :data field])
            historical-value (get-in historical [:result :events 0 :data field])]
        (is (s/valid? spec (util/wire->clj (:data event))))
        (is (= live-value historical-value))
        (if (= :object label)
          (do
            (is (= expected-key (first (keys live-value))))
            (is (some? (get-in live-value object-key-path))))
          (is (= value live-value)))))))

(deftest recursive-json-and-tool-telemetry-contract
  (doseq [value [nil "text" 42 1.5 true [nil "x" 1] {"nested" [false nil 2]}]]
    (is (s/valid? ::specs/json-value value)))
  (doseq [value [#{1 2} 'symbol (fn [] nil) {:keyword-key true}
                 [Double/NaN] {"bad" #{1}}]]
    (is (not (s/valid? ::specs/json-value value))))

  (let [valid {"metrics" {"latency_ms" 42
                          "cached" false
                          "details" {"samples" [1 2 nil]}}}]
    (is (s/valid? ::specs/tool-telemetry {}))
    (is (s/valid? ::specs/tool-telemetry valid))
    (is (= valid (:tool-telemetry (sdk/result-success "ok" valid)))))

  (doseq [invalid [{"bucket" nil}
                   {"bucket" "not-an-object"}
                   {"bucket" {:keyword-key true}}
                   {:keyword-bucket {"value" 1}}
                   {"bucket" {"set" #{1}}}
                   {"bucket" {"symbol" 'value}}
                   {"bucket" {"function" (fn [] nil)}}]]
    (is (not (s/valid? ::specs/tool-telemetry invalid)))))

(deftest result-helpers-enforce-tool-telemetry-when-instrumented
  (let [instrument-all! (requiring-resolve 'github.copilot-sdk.instrument/instrument-all!)
        unstrument-all! (requiring-resolve 'github.copilot-sdk.instrument/unstrument-all!)]
    (instrument-all!)
    (try
      (is (= {"metrics" {"count" 1}}
             (:tool-telemetry
              (sdk/result-success "ok" {"metrics" {"count" 1}}))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (sdk/result-success "bad" {"metrics" nil})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (sdk/result-failure "bad" "error" {"metrics" #{1}})))
      (finally
        (unstrument-all!)))))
