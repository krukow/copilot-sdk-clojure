(ns config-skill-output
  (:require [clojure.java.io :as io]
            [krukow.copilot-sdk :as copilot]
            [krukow.copilot-sdk.tools :as tools]))

;; See examples/README.md for usage

(declare latest-output-file large-output-text write-demo-skill!)

(defn run
  [_opts]
  (let [config-dir (or (System/getenv "COPILOT_CONFIG_DIR")
                       (str (System/getProperty "user.home") "/.copilot"))
        base-dir (str (java.nio.file.Files/createTempDirectory "copilot-demo-"
                                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        skill-dir (str base-dir "/skills")
        output-dir (str base-dir "/tool-output")
        max-size-bytes 2000
        big-output-tool (tools/define-tool
                         "big_output"
                         {:description "Return a large output payload for testing large output handling."
                          :parameters {:type "object" :properties {} :required []}
                          :handler (fn [_ _]
                                     (let [content (large-output-text)
                                           bytes (count (.getBytes content "UTF-8"))]
                                       (tools/result-success
                                        (str "Generated " bytes " bytes.\n\n" content))))})]
    (.mkdirs (java.io.File. skill-dir))
    (.mkdirs (java.io.File. output-dir))
    (write-demo-skill! skill-dir)
    (copilot/with-client-session [session {:model "gpt-5.2"
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
      (copilot/send! session {:prompt "Call the big_output tool with no arguments, then reply with just DONE."})
      (loop [attempts 0]
        (if-let [file (latest-output-file output-dir)]
          (do
            (println (str (.getAbsolutePath ^java.io.File file) " (" (.length ^java.io.File file) " bytes)"))
            (copilot/abort! session))
          (if (>= attempts 120)
            (println "No output file detected; CLI may not handle external tool output.")
            (do
              (Thread/sleep 500)
              (recur (inc attempts)))))))
    nil))

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
  (apply str (repeat 100000 "x")))

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
