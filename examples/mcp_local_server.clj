(ns mcp-local-server
  (:require [github.copilot-sdk :as copilot]
            [github.copilot-sdk.helpers :as h]))

;; See examples/README.md for usage
;; See doc/mcp/overview.md for full MCP documentation

;; This example demonstrates using MCP (Model Context Protocol) servers
;; to extend the assistant's capabilities with external tools.

;; NOTE: This example requires npx (Node.js) to be installed,
;; as it uses the @modelcontextprotocol/server-filesystem MCP server.

(def defaults
  {:allowed-dir "/tmp"})

(defn run
  "Run a query with the filesystem MCP server.

  Usage:
    clojure -A:examples -X mcp-local-server/run
    clojure -A:examples -X mcp-local-server/run :allowed-dir '\"/home/user/docs\"'"
  [{:keys [allowed-dir] :or {allowed-dir (:allowed-dir defaults)}}]
  (println (str "MCP Filesystem Server — allowed directory: " allowed-dir))
  (println)
  (let [session-config {:model "gpt-5.2"
                        :mcp-servers
                        {"filesystem"
                         {:mcp-command "npx"
                          :mcp-args ["-y" "@modelcontextprotocol/server-filesystem"
                                     allowed-dir]
                          :mcp-tools ["*"]}}}]
    (copilot/with-client [client {}]
      (copilot/with-session [session client session-config]
        (println "Q: List the files in the allowed directory")
        (println "🤖:" (h/query (str "List the files in " allowed-dir ". Just list the filenames, nothing more.")
                                :session session))))))

(defn run-with-custom-tools
  "Run with MCP server and custom tools combined.

  Usage:
    clojure -A:examples -X mcp-local-server/run-with-custom-tools
    clojure -A:examples -X mcp-local-server/run-with-custom-tools :allowed-dir '\"/home/user/docs\"'"
  [{:keys [allowed-dir] :or {allowed-dir (:allowed-dir defaults)}}]
  (let [summary-tool
        (copilot/define-tool "summarize_text"
          {:description "Summarize a piece of text into a single sentence."
           :parameters {:type "object"
                        :properties {:text {:type "string"
                                            :description "The text to summarize"}}
                        :required ["text"]}
           :handler (fn [{:keys [text]} _]
                      (copilot/result-success
                       (str "Summary: " (subs text 0 (min 100 (count text))) "...")))})
        session-config {:model "gpt-5.2"
                        :tools [summary-tool]
                        :mcp-servers
                        {"filesystem"
                         {:mcp-command "npx"
                          :mcp-args ["-y" "@modelcontextprotocol/server-filesystem" allowed-dir]
                          :mcp-tools ["*"]}}}]
    (println "MCP + Custom Tools Example")
    (println (str "  Allowed directory: " allowed-dir))
    (println)
    (copilot/with-client [client {}]
      (copilot/with-session [session client session-config]
        (println "🤖:" (h/query (str "List the files in " allowed-dir " and summarize the result briefly using the summarize_text tool.")
                                :session session))))))
