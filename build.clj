(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [clojure.xml :as xml]
            [deps-deploy.deps-deploy :as dd])
  (:import [java.io File]))

(def lib 'io.github.krukow/copilot-sdk)
(def clojars-lib 'net.clojars.krukow/copilot-sdk)
(def version "0.1.2-SNAPSHOT")
(def class-dir "target/classes")
(def aot-namespaces ['krukow.copilot-sdk.java-api])

(defn- get-developer-email []
  (or (System/getenv "DEVELOPER_EMAIL")
      (let [f (java.io.File. (str (System/getProperty "user.home") "/.copilot-sdk-email"))]
        (when (.exists f) (str/trim (slurp f))))))

(defn- pom-template [version]
  (let [email (get-developer-email)]
    [[:description "Clojure SDK for GitHub Copilot CLI with Java interop support."]
     [:url "https://github.com/krukow/copilot-sdk-clojure"]
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
      [:url "https://github.com/krukow/copilot-sdk-clojure"]
      [:connection "scm:git:https://github.com/krukow/copilot-sdk-clojure.git"]
      [:developerConnection "scm:git:ssh:git@github.com:krukow/copilot-sdk-clojure.git"]
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

(defn jar "Build source-only JAR." [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "Copying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "Building JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

(defn aot-jar "Build AOT-compiled JAR for Java interop." [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)
        basis (:basis opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "Copying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "AOT compiling:" aot-namespaces)
    (b/compile-clj {:basis basis
                    :ns-compile aot-namespaces
                    :class-dir class-dir
                    :compiler-options {:direct-linking true}})
    (println "Building JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

(defn install "Install JAR to local Maven repo." [opts]
  (b/install (jar-opts opts))
  opts)

(defn deploy "Deploy JAR to Clojars (net.clojars.krukow/copilot-sdk)." [opts]
  (aot-jar opts)
  (let [jar-file (format "target/%s-%s.jar" lib version)
        pom-path (b/pom-path {:lib clojars-lib :class-dir class-dir})]
    ;; Rewrite pom.xml with Clojars coordinates
    (b/write-pom (assoc (jar-opts opts) :lib clojars-lib))
    (dd/deploy {:installer :remote
                :artifact (b/resolve-path jar-file)
                :pom-file pom-path}))
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
    (aot-jar opts)
    (let [artifact-dir (str "target/bundle/io/github/krukow/copilot-sdk/" v)
          pom-file (str class-dir "/META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml")
          files {:jar (format "copilot-sdk-%s.jar" v)
                 :sources (format "copilot-sdk-%s-sources.jar" v)
                 :javadoc (format "copilot-sdk-%s-javadoc.jar" v)
                 :pom (format "copilot-sdk-%s.pom" v)}]
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
      (spit (str javadoc-dir "/README.md") "Clojure library. See https://github.com/krukow/copilot-sdk-clojure")
      (b/jar {:class-dir javadoc-dir :jar-file (str artifact-dir "/" (:javadoc files))}))
      ;; Copy main JAR and POM
      (println "Copying artifacts...")
      (shell/sh "cp" (format "target/%s-%s.jar" lib v) (str artifact-dir "/" (:jar files)))
      (shell/sh "cp" pom-file (str artifact-dir "/" (:pom files)))
      ;; Sign and checksum
      (println "Signing and checksumming...")
      (doseq [f (vals files)
              :let [path (str artifact-dir "/" f)]]
        (shell/sh "gpg" "-ab" path)
        (spit (str path ".md5") (str/trim (:out (shell/sh "md5" "-q" path))))
        (spit (str path ".sha1") (first (str/split (:out (shell/sh "shasum" "-a" "1" path)) #"\s+"))))
      ;; Create zip
      (let [bundle-zip (str "target/copilot-sdk-" v "-bundle.zip")]
        (shell/sh "sh" "-c" (str "cd target/bundle && zip -r ../copilot-sdk-" v "-bundle.zip ."))
        (println "\n✅ Bundle created:" bundle-zip)
        bundle-zip))))

(defn- upload-with-checksums
  "Upload a file with MD5 and SHA1 checksums."
  [auth base-url remote-path local-file remote-name]
  (let [url (format "%s/%s/%s" base-url remote-path remote-name)
        md5 (str/trim (:out (shell/sh "md5" "-q" local-file)))
        sha1 (first (str/split (:out (shell/sh "shasum" "-a" "1" local-file)) #"\s+"))]
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
    (aot-jar opts)
    (let [base-url "https://central.sonatype.com/repository/maven-snapshots"
          auth (str username ":" password)
          jar-file (format "target/%s-%s.jar" lib v)
          pom-file (str class-dir "/META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml")
          remote-path (format "io/github/krukow/copilot-sdk/%s" v)]
      (println "\n📤 Uploading SNAPSHOT to Maven Central...")
      (upload-with-checksums auth base-url remote-path jar-file (format "copilot-sdk-%s.jar" v))
      (upload-with-checksums auth base-url remote-path pom-file (format "copilot-sdk-%s.pom" v))
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
        updated (str/replace readme #":git/sha \"[^\"]+\"" (str ":git/sha \"" sha "\""))]
    (when-not (zero? exit) (throw (ex-info "Failed to read git SHA" {})))
    (when (= readme updated) (throw (ex-info "Pattern not found in README.md" {})))
    (spit "README.md" updated)
    (println "Updated README.md SHA to" sha)))
