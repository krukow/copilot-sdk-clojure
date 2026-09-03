(ns github.copilot-sdk.github-token-provider
  "Internal ownership of GitHub token provider runtime state."
  (:require [clojure.core.async :as async]))

(def runtime-path
  [:github-token-provider-runtime])

(def registrations-path
  (conj runtime-path :registrations))

(def invocations-path
  (conj runtime-path :invocations))

(def executor-path
  (conj runtime-path :executor))

(def generation-path
  (conj runtime-path :generation))

(def saturation-count-path
  (conj runtime-path :saturation-count))

(defn initial-state
  []
  {:registrations {}
   :invocations {}
   :executor nil
   :generation 0
   :saturation-count 0})

(defn registration-path
  [registration-id]
  (conj registrations-path registration-id))

(defn invocation-path
  [invocation-id]
  (conj invocations-path invocation-id))

(defn purge-registrations
  "Remove registrations owned by session-id.

   scope is :all for teardown, or :committed-only when rotating a provider
   after a successful create/resume operation."
  [registrations session-id scope]
  (into {}
        (remove
         (fn [[_ registration]]
           (and (= session-id (:session-id registration))
                (case scope
                  :all true
                  :committed-only (:committed? registration)
                  (throw (ex-info "Invalid GitHub token provider purge scope"
                                  {:scope scope}))))))
        registrations))

(defn- purge-invocations
  [invocations registration-ids]
  (into {}
        (remove (fn [[_ invocation]]
                  (contains? registration-ids
                             (:registration-id invocation))))
        invocations))

(defn purge-session-resources
  "Remove a session's provider registrations and their active invocations."
  [state session-id scope]
  (let [registrations (get-in state registrations-path)
        retained (purge-registrations registrations session-id scope)
        removed-ids (into #{}
                          (remove #(contains? retained %))
                          (keys registrations))]
    (-> state
        (assoc-in registrations-path retained)
        (update-in invocations-path purge-invocations removed-ids))))

(defn purge-registration
  "Remove one provider registration and its active invocations."
  [state registration-id]
  (-> state
      (update-in registrations-path dissoc registration-id)
      (update-in invocations-path purge-invocations #{registration-id})))

(defn purge-all-resources
  "Remove every provider registration and active invocation."
  [state]
  (update state
          :github-token-provider-runtime
          assoc
          :registrations {}
          :invocations {}))

(defn close-removed-invocations!
  "Cancel invocations removed by one atomic client-state transition."
  [old-state new-state]
  (doseq [[invocation-id {:keys [cancel-chan cancelled? task] :as invocation}]
          (get-in old-state invocations-path)
          :when (not (identical?
                      invocation
                      (get-in new-state
                              (conj invocations-path invocation-id))))]
    (reset! cancelled? true)
    (async/close! cancel-chan)
    (when-let [^java.util.concurrent.Future future @task]
      (.cancel future true))))
