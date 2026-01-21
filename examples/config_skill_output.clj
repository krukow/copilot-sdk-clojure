(ns config-skill-output
  "Example 5: Config directory, skills, and large output handling

   This example demonstrates:
   - config-dir override
   - skill-directories
   - disabled-skills
   - large-output settings

   Run with: clojure -A:examples -M -m config-skill-output"
  (:require [clojure.core.async :refer [<!! alts!! timeout]]
            [clojure.java.io :as io]
            [krukow.copilot-sdk :as copilot]
            [krukow.copilot-sdk.tools :as tools]))

(defn- latest-output-file
  [output-dir]
  (let [dir (java.io.File. output-dir)
        files (seq (.listFiles dir))]
    (when files
      (->> files
           (filter #(re-find #"copilot-tool-output" (.getName ^java.io.File %)))
           (sort-by #(.lastModified ^java.io.File %) >)
           first))))

(defn- large-output-text
  []
  (apply str (repeat 10000 "x")))

(defn- write-large-output!
  [output-dir content]
  (let [timestamp (System/currentTimeMillis)
        suffix (subs (str (java.util.UUID/randomUUID)) 0 6)
        file-name (str timestamp "-copilot-tool-output-" suffix ".txt")
        file (io/file output-dir file-name)]
    (spit file content)
    (.getAbsolutePath file)))

(defn- write-demo-skill!
  [skill-dir]
  (let [demo-skill-dir (io/file skill-dir "demo-skill")
        skill-file (io/file demo-skill-dir "SKILL.md")
        contents (str
                  "---\n"
                  "name: demo-skill\n"
                  "description: Demonstrates skill discovery from custom directories.\n"
                  "allowed-tools: bash, powershell\n"
                  "---\n\n"
                  "This skill is intentionally minimal. It exists to show skill discovery.\n")]
    (.mkdirs demo-skill-dir)
    (spit skill-file contents)))

(defn -main [& _args]
  (println "⚙️  Config, Skills, and Large Output Example")
  (println "===========================================\n")
  (let [cli-path (or (System/getenv "COPILOT_CLI_PATH") "copilot")
        config-dir (or (System/getenv "COPILOT_CONFIG_DIR")
                       (str (System/getProperty "user.home") "/.copilot"))
        skill-dir "/tmp/copilot-skills"
        output-dir "/tmp/copilot-tool-output"
        max-size-bytes 2000
        big-output-tool (tools/define-tool
                         "big_output"
                         {:description "Return a large output payload for testing large output handling."
                          :parameters {:type "object" :properties {} :required []}
                          :handler (fn [_ _]
                                     (let [content (large-output-text)
                                           bytes (count (.getBytes content "UTF-8"))]
                                       (if (> bytes max-size-bytes)
                                         (let [file-path (write-large-output! output-dir content)
                                               preview (subs content 0 (min 500 (count content)))]
                                           (tools/result-success
                                            (str "Output too large to read at once (" bytes " bytes). Saved to: "
                                                 file-path
                                                 "\nConsider using tools like grep, head/tail, or view_range.\n\n"
                                                 "Preview (first 500 chars):\n"
                                                 preview)))
                                         (tools/result-success content))))})]
    (.mkdirs (java.io.File. skill-dir))
    (.mkdirs (java.io.File. output-dir))
    (write-demo-skill! skill-dir)
    (try
      (println "📡 Starting Copilot client (debug logging enabled)...")
      (copilot/with-client [client {:cli-path cli-path
                                    :log-level :debug}]
        (println "✅ Connected!\n")
        (println "📝 Creating session with config-dir, skills, and large-output...")
        (copilot/with-session [session client
                               {:model "gpt-5"
                                :config-dir config-dir
                                :skill-directories [skill-dir]
                                :disabled-skills ["demo-skill"]
                                :large-output {:enabled true
                                               :max-size-bytes max-size-bytes
                                               :output-dir output-dir}
                                :tools [big-output-tool]
                                :available-tools ["big_output"]
                                :system-message {:mode :append
                                                 :content "Always call the big_output tool before replying."}}]
          (println (str "✅ Session created: " (copilot/session-id session) "\n"))
          (println "💬 Asking: Trigger a large tool output and summarize it.")
          (let [event-ch (copilot/send-async session
                                             {:prompt "Call the big_output tool with no arguments, then reply with just DONE."})
                deadline (timeout 60000)
                tool-seen? (atom false)
                file-reported? (atom false)]
            (loop []
              (let [poll (timeout 500)
                    [event ch] (alts!! [event-ch deadline poll])]
                (cond
                  (= ch deadline)
                  (println "\n⚠️  Timed out waiting for session.idle.")

                  (= ch poll)
                  (let [file (latest-output-file output-dir)]
                    (when (and file (compare-and-set! file-reported? false true))
                      (println "\n📄 Large output file created:")
                      (println (str "   " (.getAbsolutePath ^java.io.File file) " (" (.length ^java.io.File file) " bytes)"))
                      (println "\nℹ️  Aborting session after large output file for demo.")
                      (copilot/abort! session))
                    (when-not @file-reported?
                      (recur)))

                  (nil? event)
                  (println "\n⚠️  Event stream closed.")

                  :else
                  (do
                    (case (:type event)
                      :tool.execution_complete
                      (let [result (get-in event [:data :result])
                            content (or (:content result) (:text-result-for-llm result))
                            error (:error result)]
                        (println "\n🧰 Tool output message:")
                        (when content
                          (println content))
                        (when error
                          (println (str "❌ Tool error: " error)))
                        (reset! tool-seen? true))
                      :assistant.message
                      (do
                        (println "\n🤖 Response:")
                        (println (get-in event [:data :content])))
                      :session.idle
                      (println "\n✅ Session idle.")
                      nil)
                    (when (and @tool-seen? (not= :session.idle (:type event)))
                      (println "\nℹ️  Aborting session after tool output for demo.")
                      (copilot/abort! session))
                    (when-not (or (= :session.idle (:type event)) @tool-seen? @file-reported?)
                      (recur)))))))))
      (println "\n✅ Done!")
      ;; Ensure JVM exits - background threads from core.async may keep it alive
      (shutdown-agents)
      (System/exit 0)
      (catch Exception e
        (println (str "❌ Error: " (.getMessage e)))
        (shutdown-agents)
        (System/exit 1)))))
