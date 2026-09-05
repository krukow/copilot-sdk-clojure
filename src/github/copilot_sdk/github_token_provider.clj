(ns ^:no-doc github.copilot-sdk.github-token-provider
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

(defn invocation
  "Create one immutable provider invocation identity and its mutable
   cancellation handles."
  [attributes]
  (merge attributes
         {:cancel-chan (async/chan)
          :cancelled? (atom false)
          :task (atom nil)
          :executor (atom nil)}))

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
  (-> state
      (assoc-in registrations-path {})
      (assoc-in invocations-path {})))

(defn cancel-invocation!
  "Cancel one invocation and reclaim its executor queue slot when it has not
   started running."
  [{:keys [cancel-chan cancelled? task executor] :as invocation}]
  (reset! cancelled? true)
  (async/close! cancel-chan)
  (when-let [^java.util.concurrent.Future future @task]
    (.cancel future true)
    (when-let [^java.util.concurrent.ThreadPoolExecutor owner @executor]
      (.remove owner ^Runnable future)))
  invocation)

(defn attach-task!
  "Attach a task before executor execution, closing the cancellation race
   between registration and callback execution."
  [invocation executor future]
  (reset! (:executor invocation) executor)
  (reset! (:task invocation) future)
  (when @(:cancelled? invocation)
    (cancel-invocation! invocation))
  invocation)

(defn close-removed-invocations!
  "Cancel invocations removed or identity-replaced by one atomic client-state
   transition. Invocation entries are immutable identities; mutable task and
   cancellation handles live in the atoms created by `invocation`."
  [old-state new-state]
  (doseq [[invocation-id invocation]
          (get-in old-state invocations-path)
          :when (not (identical?
                      invocation
                      (get-in new-state
                              (conj invocations-path invocation-id))))]
    (cancel-invocation! invocation)))
