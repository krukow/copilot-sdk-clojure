(ns github.copilot-sdk.lifecycle-macros-behavior-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.helpers :as helpers]))

(defn- with-client-in-go
  [value-ch]
  (async/go
    (sdk/with-client [client]
      [client (async/<! value-ch)])))

(defn- with-session-in-go
  [value-ch]
  (async/go
    (sdk/with-session [session ::client {}]
      [session (async/<! value-ch)])))

(defn- with-client-session-in-go
  [value-ch]
  (async/go
    (sdk/with-client-session [session {}]
      [session (async/<! value-ch)])))

(defn- with-query-seq-in-go
  [value-ch]
  (async/go
    (helpers/with-query-seq [events "prompt"]
      [(vec events) (async/<! value-ch)])))

(deftest lifecycle-macros-keep-parking-forms-in-go-context
  (let [calls (atom [])]
    (with-redefs-fn
      {#'sdk/client (fn [& _] ::client)
       #'sdk/start! (fn [client] (swap! calls conj [:start client]))
       #'sdk/stop! (fn [client] (swap! calls conj [:stop client]))
       #'sdk/create-session (fn [client opts]
                              (swap! calls conj [:create client opts])
                              ::session)
       #'sdk/disconnect! (fn [session]
                           (swap! calls conj [:disconnect session]))}
      (fn []
        (is (= [::client ::value]
               (async/<!! (with-client-in-go (async/to-chan! [::value])))))
        (is (= [::session ::value]
               (async/<!! (with-session-in-go (async/to-chan! [::value])))))
        (is (= [::session ::value]
               (async/<!! (with-client-session-in-go
                            (async/to-chan! [::value])))))))
    (is (= [[:start ::client]
            [:stop ::client]
            [:create ::client {}]
            [:disconnect ::session]
            [:start ::client]
            [:create ::client {}]
            [:disconnect ::session]
            [:stop ::client]]
           @calls))))

(deftest with-query-seq-keeps-parking-forms-in-go-context
  (let [cleaned? (promise)]
    (with-redefs-fn
      {(requiring-resolve
        'github.copilot-sdk.helpers/query-seq-source)
       (fn [& _]
         [[::event] #(deliver cleaned? true)])}
      (fn []
        (is (= [[::event] ::value]
               (async/<!!
                (with-query-seq-in-go
                  (async/to-chan! [::value])))))))
    (is (true? (deref cleaned? 500 false)))))

(deftest with-client-and-with-session-own-resources-in-order
  (let [calls (atom [])]
    (with-redefs-fn
      {#'sdk/client (fn [& args]
                      (swap! calls conj [:client (vec args)])
                      ::client)
       #'sdk/start! (fn [client]
                      (swap! calls conj [:start client]))
       #'sdk/stop! (fn [client]
                     (swap! calls conj [:stop client]))}
      (fn []
        (sdk/with-client [client {:log-level :debug}]
          (swap! calls conj [:body client]))))
    (is (= [[:client [{:log-level :debug}]]
            [:start ::client]
            [:body ::client]
            [:stop ::client]]
           @calls)))

  (let [calls (atom [])]
    (with-redefs-fn
      {#'sdk/create-session (fn [client opts]
                              (swap! calls conj [:create-session client opts])
                              ::session)
       #'sdk/disconnect! (fn [session]
                           (swap! calls conj [:disconnect session]))}
      (fn []
        (sdk/with-session [session ::client {:model "test"}]
          (swap! calls conj [:body session]))))
    (is (= [[:create-session ::client {:model "test"}]
            [:body ::session]
            [:disconnect ::session]]
           @calls))))

(deftest with-client-session-supports-all-binding-forms
  (let [run-case
        (fn [invoke]
          (let [calls (atom [])]
            (with-redefs-fn
              {#'sdk/client (fn [& args]
                              (swap! calls conj [:client (vec args)])
                              ::client)
               #'sdk/start! (fn [client]
                              (swap! calls conj [:start client]))
               #'sdk/stop! (fn [client]
                             (swap! calls conj [:stop client]))
               #'sdk/create-session (fn [client opts]
                                      (swap! calls conj [:create-session client opts])
                                      ::session)
               #'sdk/disconnect! (fn [session]
                                   (swap! calls conj [:disconnect session]))}
              #(invoke calls))
            @calls))
        session-opts {:model "test"}
        client-opts {:log-level :debug}
        cases
        [{:label "anonymous client with defaults"
          :invoke (fn [calls]
                    (sdk/with-client-session [session {:model "test"}]
                      (swap! calls conj [:body session])))
          :expected [[:client []]
                     [:start ::client]
                     [:create-session ::client session-opts]
                     [:body ::session]
                     [:disconnect ::session]
                     [:stop ::client]]}
         {:label "anonymous client with options"
          :invoke (fn [calls]
                    (sdk/with-client-session [{:log-level :debug} session {:model "test"}]
                      (swap! calls conj [:body session])))
          :expected [[:client [client-opts]]
                     [:start ::client]
                     [:create-session ::client session-opts]
                     [:body ::session]
                     [:disconnect ::session]
                     [:stop ::client]]}
         {:label "named client with defaults"
          :invoke (fn [calls]
                    (sdk/with-client-session [client session {:model "test"}]
                      (swap! calls conj [:body client session])))
          :expected [[:client []]
                     [:start ::client]
                     [:create-session ::client session-opts]
                     [:body ::client ::session]
                     [:disconnect ::session]
                     [:stop ::client]]}
         {:label "named client with options"
          :invoke (fn [calls]
                    (sdk/with-client-session [client {:log-level :debug} session {:model "test"}]
                      (swap! calls conj [:body client session])))
          :expected [[:client [client-opts]]
                     [:start ::client]
                     [:create-session ::client session-opts]
                     [:body ::client ::session]
                     [:disconnect ::session]
                     [:stop ::client]]}]]
    (doseq [{:keys [label invoke expected]} cases]
      (testing label
        (is (= expected (run-case invoke)))))))

(deftest lifecycle-macros-preserve-body-and-setup-exceptions
  (let [calls (atom [])
        body-error (ex-info "body failed" {:phase :body})
        caught
        (with-redefs-fn
          {#'sdk/create-session (fn [_ _]
                                  (swap! calls conj :create-session)
                                  ::session)
           #'sdk/disconnect! (fn [_]
                               (swap! calls conj :disconnect))}
          (fn []
            (try
              (sdk/with-session [session ::client {}]
                (swap! calls conj :body)
                (throw body-error))
              (catch Throwable error
                error))))]
    (is (identical? body-error caught))
    (is (= [:create-session :body :disconnect] @calls)))

  (let [calls (atom [])
        setup-error (ex-info "session setup failed" {:phase :setup})
        caught
        (with-redefs-fn
          {#'sdk/client (fn []
                          (swap! calls conj :client)
                          ::client)
           #'sdk/start! (fn [_]
                          (swap! calls conj :start))
           #'sdk/stop! (fn [_]
                         (swap! calls conj :stop))
           #'sdk/create-session (fn [_ _]
                                  (swap! calls conj :create-session)
                                  (throw setup-error))
           #'sdk/disconnect! (fn [_]
                               (swap! calls conj :disconnect))}
          (fn []
            (try
              (sdk/with-client-session [session {}]
                (swap! calls conj [:body session]))
              (catch Throwable error
                error))))]
    (is (identical? setup-error caught))
    (is (= [:client :start :create-session :stop] @calls))))

(deftest lifecycle-macros-preserve-primary-failures-and-interrupts
  (testing "a body failure remains primary when session cleanup also fails"
    (let [body-error (ex-info "body failed" {:phase :body})
          cleanup-error (ex-info "disconnect failed" {:phase :cleanup})
          caught
          (with-redefs-fn
            {#'sdk/create-session (fn [_ _] ::session)
             #'sdk/disconnect! (fn [_] (throw cleanup-error))}
            (fn []
              (try
                (sdk/with-session [_session ::client {}]
                  (throw body-error))
                (catch Throwable error
                  error))))]
      (is (identical? body-error caught))
      (is (= [cleanup-error] (vec (.getSuppressed ^Throwable caught))))))

  (testing "cleanup interruption is attached and the interrupt flag is restored"
    (let [body-error (ex-info "body failed" {:phase :body})
          cleanup-error (InterruptedException. "disconnect interrupted")
          outcome
          (deref
           (future
             (with-redefs-fn
               {#'sdk/create-session (fn [_ _] ::session)
                #'sdk/disconnect! (fn [_] (throw cleanup-error))}
               (fn []
                 (try
                   (sdk/with-session [_session ::client {}]
                     (throw body-error))
                   (catch Throwable error
                     (let [result
                           {:caught error
                            :suppressed (vec (.getSuppressed ^Throwable error))
                            :interrupted? (.isInterrupted (Thread/currentThread))}]
                       (Thread/interrupted)
                       result))))))
           1000
           ::timeout)]
      (is (not= ::timeout outcome))
      (is (identical? body-error (:caught outcome)))
      (is (= [cleanup-error] (:suppressed outcome)))
      (is (true? (:interrupted? outcome))))))
