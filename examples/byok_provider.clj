(ns byok-provider
  (:require [krukow.copilot-sdk :as copilot]
            [krukow.copilot-sdk.helpers :as h]))

;; See examples/README.md for usage
;; See doc/auth/byok.md for full BYOK documentation

;; This example demonstrates using the Copilot SDK with your own API key
;; (BYOK = Bring Your Own Key) to connect to OpenAI, Azure, Anthropic,
;; or any OpenAI-compatible endpoint.

;; NOTE: This example requires an API key. Set one of:
;;   OPENAI_API_KEY    - for OpenAI
;;   AZURE_OPENAI_KEY  - for Azure OpenAI
;;   ANTHROPIC_API_KEY - for Anthropic
;;   (no key needed)   - for Ollama (local)

(def defaults
  {:provider-name "openai"})

(defn- openai-config
  "Configuration for OpenAI direct."
  []
  (let [api-key (System/getenv "OPENAI_API_KEY")]
    (when-not api-key
      (throw (ex-info "Set OPENAI_API_KEY environment variable" {})))
    {:model "gpt-5.2"
     :provider {:provider-type :openai
                :base-url "https://api.openai.com/v1"
                :api-key api-key}}))

(defn- azure-config
  "Configuration for Azure OpenAI."
  []
  (let [api-key (System/getenv "AZURE_OPENAI_KEY")
        base-url (or (System/getenv "AZURE_OPENAI_ENDPOINT")
                     "https://your-resource.openai.azure.com")]
    (when-not api-key
      (throw (ex-info "Set AZURE_OPENAI_KEY environment variable" {})))
    {:model "gpt-5.2"
     :provider {:provider-type :azure
                :base-url base-url
                :api-key api-key
                :azure-options {:azure-api-version "2024-10-21"}}}))

(defn- anthropic-config
  "Configuration for Anthropic."
  []
  (let [api-key (System/getenv "ANTHROPIC_API_KEY")]
    (when-not api-key
      (throw (ex-info "Set ANTHROPIC_API_KEY environment variable" {})))
    {:model "claude-sonnet-4"
     :provider {:provider-type :anthropic
                :base-url "https://api.anthropic.com"
                :api-key api-key}}))

(defn- ollama-config
  "Configuration for Ollama (local, no API key needed)."
  []
  {:model "llama3"
   :provider {:provider-type :openai
              :base-url "http://localhost:11434/v1"}})

(defn- get-provider-config [provider-name]
  (case provider-name
    "openai" (openai-config)
    "azure" (azure-config)
    "anthropic" (anthropic-config)
    "ollama" (ollama-config)
    (throw (ex-info (str "Unknown provider: " provider-name
                         ". Use: openai, azure, anthropic, or ollama")
                    {:provider provider-name}))))

(defn run
  "Run a BYOK query with a specified provider.

  Usage:
    clojure -A:examples -X byok-provider/run
    clojure -A:examples -X byok-provider/run :provider-name '\"anthropic\"'
    clojure -A:examples -X byok-provider/run :provider-name '\"ollama\"'"
  [{:keys [provider-name] :or {provider-name (:provider-name defaults)}}]
  (let [config (get-provider-config provider-name)]
    (println (str "Using BYOK provider: " provider-name))
    (println (str "  Model: " (:model config)))
    (println (str "  Base URL: " (get-in config [:provider :base-url])))
    (println)
    (copilot/with-client [client {}]
      (copilot/with-session [session client config]
        (println "Q: What is 2+2?")
        (println "🤖:" (h/query "What is 2+2? Answer in one sentence." :session session))))))
