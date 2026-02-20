(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [clojure.xml :as xml])
  (:import [java.io File]))

(def lib 'io.github.copilot-community-sdk/copilot-sdk-clojure)
(def version "0.1.26.0-SNAPSHOT")
(def class-dir "target/classes")

(defn- try-sh
  "Run shell/sh, returning result map or nil if the binary is not found."
  [& args]
  (try (apply shell/sh args)
       (catch java.io.IOException _ nil)))

(defn- md5-hash
  "Compute MD5 hex digest of a file (cross-platform: macOS md5 / Linux md5sum)."
  [path]
  (if-let [{:keys [exit out]} (try-sh "md5" "-q" path)]
    (when (zero? exit) (str/trim out))
    (if-let [{:keys [exit out]} (try-sh "md5sum" path)]
      (when (zero? exit) (first (str/split out #"\s+")))
      (throw (ex-info "Neither md5 nor md5sum available" {:path path})))))

(defn- sha1-hash
  "Compute SHA1 hex digest of a file (cross-platform: macOS shasum / Linux sha1sum)."
  [path]
  (if-let [{:keys [exit out]} (try-sh "shasum" "-a" "1" path)]
    (when (zero? exit) (first (str/split out #"\s+")))
    (if-let [{:keys [exit out]} (try-sh "sha1sum" path)]
      (when (zero? exit) (first (str/split out #"\s+")))
      (throw (ex-info "Neither shasum nor sha1sum available" {:path path})))))

(defn- get-developer-email []
  (or (System/getenv "DEVELOPER_EMAIL")
      (let [f (java.io.File. (str (System/getProperty "user.home") "/.copilot-sdk-email"))]
        (when (.exists f) (str/trim (slurp f))))))

(defn- pom-template [version]
  (let [email (get-developer-email)]
    [[:description "Clojure SDK for GitHub Copilot CLI."]
     [:url "https://github.com/copilot-community-sdk/copilot-sdk-clojure"]
     [:licenses
      [:license
       [:name "MIT License"]
       [:url "https://opensource.org/licenses/MIT"]]]
     [:developers
      (into [:developer
             [:id "krukow"]
             [:name "Karl Krukow"]]
            (when email [[:email email]]))]
     [:scm
      [:url "https://github.com/copilot-community-sdk/copilot-sdk-clojure"]
      [:connection "scm:git:https://github.com/copilot-community-sdk/copilot-sdk-clojure.git"]
      [:developerConnection "scm:git:ssh:git@github.com:copilot-community-sdk/copilot-sdk-clojure.git"]
      [:tag (str "v" version)]]]))

(defn- jar-opts [opts]
  (assoc opts
         :lib lib
         :version version
         :jar-file (format "target/%s-%s.jar" lib version)
         :basis (b/create-basis {})
         :class-dir class-dir
         :target "target"
         :src-dirs ["src"]
         :pom-data (pom-template version)))

(defn jar "Build JAR." [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "Copying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "Building JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

(defn install "Install JAR to local Maven repo." [opts]
  (jar opts)
  (b/install (jar-opts opts))
  opts)

;;; Maven Central publishing

(defn- read-maven-settings [server-id]
  (let [settings-file (File. (System/getProperty "user.home") ".m2/settings.xml")]
    (when (.exists settings-file)
      (let [xml (xml/parse settings-file)
            servers (->> (:content xml)
                         (filter #(= :servers (:tag %)))
                         first :content
                         (filter #(= :server (:tag %))))]
        (->> servers
             (filter #(= server-id
                         (->> % :content
                              (filter (fn [e] (= :id (:tag e))))
                              first :content first)))
             first :content
             (reduce #(assoc %1 (:tag %2) (first (:content %2))) {}))))))

(defn- get-central-credentials []
  (let [settings (read-maven-settings "central")
        username (or (System/getenv "CENTRAL_USERNAME") (:username settings))
        password (or (System/getenv "CENTRAL_PASSWORD") (:password settings))]
    (when (or (str/blank? username) (str/blank? password))
      (throw (ex-info "Credentials not found. Set CENTRAL_USERNAME/CENTRAL_PASSWORD or configure ~/.m2/settings.xml with server id 'central'" {})))
    {:username username :password password}))

(defn bundle
  "Create bundle zip for Maven Central. Usage: clj -T:build bundle"
  [opts]
  (let [v (or (:version opts) version)]
    (when (:version opts) (alter-var-root #'version (constantly v)))
    (jar opts)
    (let [artifact-dir (str "target/bundle/" (str/replace (namespace lib) "." "/") "/" (name lib) "/" v)
          pom-file (str class-dir "/META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml")
          files {:jar (format "copilot-sdk-clojure-%s.jar" v)
                 :sources (format "copilot-sdk-clojure-%s-sources.jar" v)
                 :javadoc (format "copilot-sdk-clojure-%s-javadoc.jar" v)
                 :pom (format "copilot-sdk-clojure-%s.pom" v)}]
      (b/delete {:path "target/bundle"})
      (shell/sh "mkdir" "-p" artifact-dir)
      ;; Sources JAR
      (println "\nCreating sources JAR...")
    (b/jar {:class-dir "src" :jar-file (str artifact-dir "/" (:sources files))})
    ;; Javadoc JAR (placeholder)
    (println "Creating javadoc JAR...")
    (let [javadoc-dir "target/javadoc"]
      (b/delete {:path javadoc-dir})
      (shell/sh "mkdir" "-p" javadoc-dir)
      (spit (str javadoc-dir "/README.md") "Clojure library. See https://github.com/copilot-community-sdk/copilot-sdk-clojure")
      (b/jar {:class-dir javadoc-dir :jar-file (str artifact-dir "/" (:javadoc files))}))
      ;; Copy main JAR and POM
      (println "Copying artifacts...")
      (shell/sh "cp" (format "target/%s-%s.jar" lib v) (str artifact-dir "/" (:jar files)))
      (shell/sh "cp" pom-file (str artifact-dir "/" (:pom files)))
      ;; Sign and checksum
      (println "Signing and checksumming...")
      (doseq [f (vals files)
              :let [path (str artifact-dir "/" f)]]
        (let [result (shell/sh "gpg" "--batch" "-ab" path)]
          (when-not (zero? (:exit result))
            (throw (ex-info (str "GPG signing failed for " f
                                 ". Ensure a GPG key is available (see PUBLISHING.md).")
                            {:file f :exit (:exit result) :err (:err result)}))))
        (spit (str path ".md5") (md5-hash path))
        (spit (str path ".sha1") (sha1-hash path)))
      ;; Create zip
      (let [bundle-zip (str "target/copilot-sdk-clojure-" v "-bundle.zip")]
        (shell/sh "sh" "-c" (str "cd target/bundle && zip -r ../copilot-sdk-clojure-" v "-bundle.zip ."))
        (println "\n✅ Bundle created:" bundle-zip)
        bundle-zip))))

(defn- upload-with-checksums
  "Upload a file with MD5 and SHA1 checksums."
  [auth base-url remote-path local-file remote-name]
  (let [url (format "%s/%s/%s" base-url remote-path remote-name)
        md5 (md5-hash local-file)
        sha1 (sha1-hash local-file)]
    ;; Upload main file
    (let [result (shell/sh "curl" "--silent" "--show-error" "--fail"
                           "--user" auth "--upload-file" local-file url)]
      (when-not (zero? (:exit result))
        (throw (ex-info (str "Failed to upload " local-file) result))))
    ;; Upload checksums
    (shell/sh "curl" "--silent" "--show-error" "--fail"
              "--user" auth "--upload-file" "-" (str url ".md5")
              :in md5)
    (shell/sh "curl" "--silent" "--show-error" "--fail"
              "--user" auth "--upload-file" "-" (str url ".sha1")
              :in sha1)
    (println "  ✓" remote-name)))

(defn- deploy-snapshot
  "Deploy SNAPSHOT to Maven Central snapshots repository."
  [opts]
  (let [{:keys [username password]} (get-central-credentials)
        v (or (:version opts) version)]
    (jar opts)
    (let [base-url "https://central.sonatype.com/repository/maven-snapshots"
          auth (str username ":" password)
          jar-file (format "target/%s-%s.jar" lib v)
          pom-file (str class-dir "/META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml")
          remote-path (format "%s/%s/%s" (str/replace (namespace lib) "." "/") (name lib) v)]
      (println "\n📤 Uploading SNAPSHOT to Maven Central...")
      (upload-with-checksums auth base-url remote-path jar-file (format "copilot-sdk-clojure-%s.jar" v))
      (upload-with-checksums auth base-url remote-path pom-file (format "copilot-sdk-clojure-%s.pom" v))
      (println "✅ SNAPSHOT published!")
      (println "Add this repository to consume:")
      (println "  https://central.sonatype.com/repository/maven-snapshots/"))))

(defn deploy-central
  "Deploy to Maven Central. Usage: clj -T:build deploy-central"
  [opts]
  (let [v (or (:version opts) version)]
    (if (str/ends-with? v "-SNAPSHOT")
      (deploy-snapshot opts)
      (let [{:keys [username password]} (get-central-credentials)
            bundle-zip (bundle opts)
            auth-token (.encodeToString (java.util.Base64/getEncoder)
                                        (.getBytes (str username ":" password) "UTF-8"))]
        (println "\n📤 Uploading to Maven Central...")
        (let [result (shell/sh "curl" "--silent" "--show-error" "--fail"
                               "--request" "POST"
                               "--header" (str "Authorization: Bearer " auth-token)
                               "--form" (str "bundle=@" bundle-zip)
                               "https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC")]
          (if (zero? (:exit result))
            (do (println "✅ Upload successful! Deployment ID:" (:out result))
                (println "Check status: https://central.sonatype.com/publishing"))
            (throw (ex-info "Upload failed" result))))))))

(defn release "Alias for deploy-central." [opts] (deploy-central opts))

(defn update-readme-sha "Update README.md git SHA to HEAD." [_opts]
  (let [{:keys [exit out]} (shell/sh "git" "rev-parse" "HEAD")
        sha (str/trim out)
        readme (slurp "README.md")
        match? (boolean (re-find #":git/sha \"[^\"]+\"" readme))
        updated (str/replace readme #":git/sha \"[^\"]+\"" (str ":git/sha \"" sha "\""))]
    (when-not (zero? exit) (throw (ex-info "Failed to read git SHA" {})))
    (when-not match? (throw (ex-info "Pattern not found in README.md" {})))
    (if (= readme updated)
      (println "README.md SHA already up to date:" sha)
      (do
        (spit "README.md" updated)
        (println "Updated README.md SHA to" sha)))))

(defn- update-version-in-files!
  "Update version string in all files that reference it."
  [new-version]
  ;; build.clj
  (let [build-clj (slurp "build.clj")
        updated (str/replace build-clj
                             #"\(def version \"[^\"]+\"\)"
                             (str "(def version \"" new-version "\")"))]
    (when (= build-clj updated)
      (throw (ex-info "Failed to update version in build.clj" {})))
    (spit "build.clj" updated))
  ;; README.md
  (let [readme (slurp "README.md")
        updated (-> readme
                    (str/replace #"\{:mvn/version \"[^\"]+\"\}"
                                 (str "{:mvn/version \"" new-version "\"}")))]
    (spit "README.md" updated))
  (println "Updated: build.clj, README.md"))

(defn- parse-version
  "Parse a 4-segment version string into [upstream-major upstream-minor upstream-patch clj-patch]."
  [v]
  (let [base (str/replace v #"-SNAPSHOT$" "")
        parts (str/split base #"\.")
        nums (mapv parse-long parts)]
    (case (count nums)
      3 (conj nums 0)
      4 nums
      (throw (ex-info (str "Expected 3 or 4 version segments, got: " v) {:version v})))))

(defn sync-version
  "Set version to match an upstream copilot-sdk release.
   The version format is UPSTREAM.PATCH where UPSTREAM is the 3-segment
   upstream version and PATCH is a Clojure-specific patch counter (starts at 0).

   Usage: clj -T:build sync-version :upstream '\"0.1.22\"'
          clj -T:build sync-version :upstream '\"0.1.23\"' :snapshot true"
  [{:keys [upstream snapshot] :or {snapshot false}}]
  (when-not upstream
    (throw (ex-info "Required: :upstream version (e.g., :upstream '\"0.1.22\"')" {})))
  (let [parts (str/split upstream #"\.")
        _ (when-not (= 3 (count parts))
            (throw (ex-info "Upstream version must be 3 segments (e.g., \"0.1.22\")" {:upstream upstream})))
        new-version (str upstream ".0" (when snapshot "-SNAPSHOT"))]
    (update-version-in-files! new-version)
    (println (str "Synced version to upstream " upstream ": " version " -> " new-version))
    new-version))

(defn bump-version
  "Bump the Clojure-specific patch segment of the version.
   Version format: UPSTREAM_MAJOR.UPSTREAM_MINOR.UPSTREAM_PATCH.CLJ_PATCH

   Usage: clj -T:build bump-version                    ; 0.1.22.0 -> 0.1.22.1
          clj -T:build bump-version :snapshot true      ; 0.1.22.0 -> 0.1.22.1-SNAPSHOT
          clj -T:build bump-version :version '\"0.1.23.0\"'  ; explicit version"
  [{:keys [version snapshot] :or {snapshot false}}]
  (let [current (str/replace build/version #"-SNAPSHOT$" "")
        [maj min patch clj-patch] (parse-version current)
        new-version (cond
                      version version
                      :else (format "%d.%d.%d.%d" maj min patch (inc clj-patch)))
        new-version (if snapshot (str new-version "-SNAPSHOT") new-version)]
    (update-version-in-files! new-version)
    (println (str "Bumped version: " build/version " -> " new-version))
    new-version))

(def ^:private changelog-file "CHANGELOG.md")
(def ^:private repo-url "https://github.com/copilot-community-sdk/copilot-sdk-clojure")

(defn stamp-changelog
  "Move [Unreleased] entries to a versioned section in CHANGELOG.md.
   Uses the current version from build.clj and today's date.

   Transforms:
     ## [Unreleased]
     ### Added
     - feature X
   Into:
     ## [Unreleased]

     ## [0.1.24.3] - 2026-02-18
     ### Added
     - feature X

   Also updates the comparison links at the bottom.

   Usage: clj -T:build stamp-changelog"
  [_]
  (let [changelog (slurp changelog-file)
        today (.format (java.time.LocalDate/now)
                       (java.time.format.DateTimeFormatter/ISO_LOCAL_DATE))
        ver version
        tag (str "v" ver)
        ;; Find the [Unreleased] header and capture everything until the next ## section.
        ;; Uses single \n after header so the lookahead \n## can detect an empty section.
        unreleased-re #"(?s)## \[Unreleased\]\n(.*?)(?=\n## \[)"
        m (re-find unreleased-re changelog)]
    (when-not m
      (throw (ex-info "Could not find [Unreleased] section in CHANGELOG.md" {})))
    (let [unreleased-content (str/trim (nth m 1))]
      (if (str/blank? unreleased-content)
        (println "⚠️  No entries under [Unreleased] — changelog not stamped.")
        (let [;; Replace the [Unreleased] block with a fresh empty one + versioned section
            new-section (str "## [Unreleased]\n\n"
                             "## [" ver "] - " today "\n"
                             unreleased-content)
            updated (str/replace changelog #"(?s)## \[Unreleased\]\n.*?(?=\n## \[)"
                                 (str new-section "\n"))
            ;; Update comparison links at bottom:
            ;; [Unreleased]: .../compare/OLD...HEAD  →  .../compare/vNEW...HEAD
            ;; Add new version link: [NEW]: .../compare/OLD_TAG...vNEW
            ;; Find the previous version tag from the existing [Unreleased] link
            prev-tag-re #"\[Unreleased\]: .*/compare/(.+?)\.\.\.HEAD"
            prev-tag (second (re-find prev-tag-re updated))
            updated (if prev-tag
                      (-> updated
                          ;; Update [Unreleased] link to compare from new tag
                          (str/replace (re-pattern (str "\\[Unreleased\\]: .*/compare/.*?\\.\\.\\.HEAD"))
                                       (str "[Unreleased]: " repo-url "/compare/" tag "...HEAD"))
                          ;; Insert new version link after [Unreleased] link
                          (str/replace (str "[Unreleased]: " repo-url "/compare/" tag "...HEAD")
                                       (str "[Unreleased]: " repo-url "/compare/" tag "...HEAD\n"
                                            "[" ver "]: " repo-url "/compare/" prev-tag "..." tag)))
                      updated)]
        (spit changelog-file updated)
        (println (str "Stamped CHANGELOG.md: [Unreleased] → [" ver "] - " today)))))))
