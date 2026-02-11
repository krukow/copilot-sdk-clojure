(ns config-skill-output
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async :refer [<!!]]
            [github.copilot-sdk :as copilot]
            [github.copilot-sdk.tools :as tools]))

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
                                        (println (str "[tool] big_output handler called, returning " bytes " bytes"))
                                        (tools/result-success
                                         (str "Generated " bytes " bytes.\n\n" content))))})]
    (.mkdirs (java.io.File. skill-dir))
    (.mkdirs (java.io.File. output-dir))
    (write-demo-skill! skill-dir)
    (println (str "[debug] output-dir: " output-dir))
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
      (println "[debug] Session created, sending prompt via send-async...")
      (let [events-ch (copilot/send-async session
                                          {:prompt "Call the big_output tool with no arguments, then reply with just DONE."
                                           :timeout-ms 120000})
            done? (atom false)]
        ;; Monitor events in the foreground
        (loop []
          (when-not @done?
            (if-let [event (<!! events-ch)]
              (do
                (println (str "[event] " (:type event)
                              (when-let [d (:data event)]
                                (let [s (pr-str d)]
                                  (if (> (count s) 200)
                                    (str " " (subs s 0 200) "...")
                                    (str " " s))))))
                ;; Check for output file after each event
                (when-let [file (latest-output-file output-dir)]
                  (println (str "[found] " (.getAbsolutePath ^java.io.File file)
                                " (" (.length ^java.io.File file) " bytes)")))
                ;; Stop on terminal events
                (when (#{:copilot/session.idle :copilot/session.error} (:type event))
                  (reset! done? true))
                (recur))
              ;; Channel closed
              (reset! done? true))))
        ;; Final check for output file
        (if-let [file (latest-output-file output-dir)]
          (println (str "[result] " (.getAbsolutePath ^java.io.File file)
                        " (" (.length ^java.io.File file) " bytes)"))
          (do
            (println "[result] No output file detected in output-dir.")
            (println "[debug] Checking system tmpdir for large output files...")
            (let [tmpdir (System/getProperty "java.io.tmpdir")
                  tmp-file (latest-output-file tmpdir)]
              (if tmp-file
                (println (str "[found] File in tmpdir: " (.getAbsolutePath ^java.io.File tmp-file)
                              " (" (.length ^java.io.File tmp-file) " bytes)"))
                (println "[debug] No output files in tmpdir either.")))))))
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
  ;; ~100KB output: exceeds CLI's default 30KB threshold.
  ;; Note: the CLI ignores our custom outputDir/maxSizeBytes (known issue)
  ;; and writes to system tmpdir with the default 30KB threshold.
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
