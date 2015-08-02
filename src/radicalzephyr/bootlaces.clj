(ns radicalzephyr.bootlaces
  {:boot/export-tasks true}
  (:require
   [clojure.edn        :as edn]
   [clojure.java.io    :as io]
   [clojure.string     :as str]
   [boot.git           :as git]
   [boot.pod           :as pod]
   [boot.util          :as util]
   [boot.core          :refer :all]
   [boot.task.built-in :refer :all]
   [radicalzephyr.bootlaces.template :as t]))

(def ^:private +gpg-config+
  (let [gpg-files (filter #(.exists %)
                          [(io/file "gpg.edn")
                           (io/file (System/getProperty "user.home")
                                    ".boot"
                                    "gpg.edn")])]
    (when-not (empty? gpg-files) (read-string (slurp (first gpg-files))))))

(def ^:private +last-commit+
  (try (git/last-commit) (catch Throwable _)))

(defn- assert-edn-resource [x]
  (-> x
      io/resource
      (doto (assert (format "resource not found on class path (%s)" x)))
      slurp
      read-string))

(defn bootlaces!
  [version & {:keys [dev-dependencies
                     project-type]}]
  (when (not= project-type :java)
    (merge-env! :resource-paths #{"src"}))
  (when dev-dependencies
    (->> dev-dependencies
         assert-edn-resource
         (map #(into % [:scope "test"]))
         (merge-env! :dependencies)))
  (task-options!
    push #(into %
                (merge {:repo "deploy-clojars" :ensure-version version}
                       (when +last-commit+ {:ensure-clean  true
                                            :ensure-branch "master"
                                            :ensure-tag    (git/last-commit)})))))

(defn- get-creds [prefix]
  (mapv #(System/getenv %) [(str prefix "USER") (str prefix "PASS")]))

(deftask ^:private collect-clojars-credentials
  "Collect CLOJARS_USER and CLOJARS_PASS from the user if they're not set."
  []
  (fn [next-handler]
    (fn [fileset]
      (let [[user pass] (get-creds "CLOJARS_")
            clojars-creds (atom {})]
        (if (and user pass)
          (swap! clojars-creds assoc :username user :password pass)
          (do (println (str "CLOJARS_USER and CLOJARS_PASS were not set;"
                            " please enter your Clojars credentials."))
              (print "Username: ")
              (#(swap! clojars-creds assoc :username %) (read-line))
              (print "Password: ")
              (#(swap! clojars-creds assoc :password %)
               (apply str (.readPassword (System/console))))))
        (merge-env! :repositories
                    [["deploy-clojars" (merge @clojars-creds
                                              {:url "https://clojars.org/repo"})]])
        (next-handler fileset)))))

(deftask ^:private update-readme-dependency
  "Update latest release version in README.md file."
  [v version VERSION str "The version to write"]
  (let [readme (io/file "README.md")]
    (if-not (.exists readme)
      identity
      (with-pre-wrap fileset
        (let [{:keys [project pom-version]} (-> #'pom meta :task-options)
              version (or version pom-version)
              old-readme (slurp readme)
              new-readme (t/update-dependency old-readme project version)]
          (when (not= old-readme new-readme)
            (util/info "Updating latest Clojars version in README.md...\n")
            (spit readme new-readme))
          fileset)))))

(deftask build-jar
  "Build jar and install to local repo."
  [v version VERSION str "The version to write"]
  (comp (if version
          (pom :version version)
          (pom))
        (jar)
        (install)))

(defn get-current-version []
  (-> #'pom meta :task-options :version))

(defn update-version [s f]
  (if-let [[whole version] (re-find #"\(def \+version\+ \"(.*)\"\)"
                                    s)]
    (.replace s whole (format "(def +version+ \"%s\")" (f version)))
    s))

(defn de-snapshot-version-def
  [s]
  (update-version s #(.replace % "-SNAPSHOT" "")))

(deftask de-snapshot-version
  "Remove SNAPSHOT from the version."
  []
  (let [boot-build (io/file "build.boot")]
    (if-not (.exists boot-build)
      identity
      (with-pre-wrap fileset
        (let [old-boot-build (slurp boot-build)
              new-boot-build (de-snapshot-version-def old-boot-build)]
          (when (not= old-boot-build new-boot-build)
            (util/info "Removing SNAPSHOT from version in build.boot...\n")
            (spit boot-build new-boot-build)))
        fileset))))

(deftask commit-files
  [f files   FILE    [str] "The files to commit."
   m message MESSAGE  str  "The commit message."]
  (let [worker-pods (pod/pod-pool
                     (update-in (get-env)
                                [:dependencies]
                                into '[[clj-jgit "0.8.8"]
                                       [org.slf4j/slf4j-api "1.7.12"]
                                       [org.slf4j/slf4j-simple "1.7.12"]])
                     :init #(pod/with-eval-in %
                              (require '[clj-jgit.porcelain :as jgit])))]
    (cleanup (worker-pods :shutdown))
    (with-pre-wrap fileset
      (let [{modified? :modified} (git/status)]
        (if-not (every? modified? files)
          (util/info "Nothing has changed, not committing.\n")
          (let [worker-pod (worker-pods :refresh)]
            (pod/with-eval-in worker-pod
              (jgit/with-repo "."
                (doseq [file ~files]
                  (jgit/git-add repo file))
                (jgit/git-commit repo ~message))))))
      fileset)))

(deftask build-snapshot
  []
  (if-not (git/clean?)
    (do
      (util/warn "Refusing to continue, git repo is not clean.\n")
      identity)
    (let [version (get-current-version)]
      (comp (update-readme-dependency :version version)
            (commit-files :files ["build.boot" "README.md"]
                          :message (str "Create " version))
            (sift)
            (build-jar :version version)))))

(deftask build-release
  []
  (if-not (git/clean?)
    (do
      (util/warn "Refusing to continue, git repo is not clean.\n")
      identity)
    (let [version (.replace (get-current-version) "-SNAPSHOT" "")]
      (comp (de-snapshot-version)
            (update-readme-dependency :version version)
            (commit-files :files ["build.boot" "README.md"]
                          :message (str "Release " version))
            (sift)
            (build-jar :version version)))))

(deftask push-snapshot
  "Deploy snapshot version to Clojars."
  [f file PATH str "The jar file to deploy."]
  (comp (collect-clojars-credentials)
        (push :file            file
              :ensure-snapshot true
              :ensure-branch   "dev"
              :repo            "deploy-clojars")))

(deftask push-release
  "Deploy release version to Clojars."
  [f file PATH str "The jar file to deploy."]
  (comp
   (collect-clojars-credentials)
   (push
    :file           file
    :tag            (boolean +last-commit+)
    :gpg-sign       true
    :gpg-keyring    (:keyring +gpg-config+)
    :gpg-user-id    (:user-id +gpg-config+)
    :ensure-release true
    :repo           "deploy-clojars")))

(defn vstring-as-version [vstr]
  (let [i (.indexOf vstr "-")
        dash-index (case i -1 (count vstr) i)
        suffix (subs vstr dash-index (count vstr))
        vstr (subs vstr 0 dash-index)
        [major minor patch] (->> (clojure.string/split vstr #"\.")
                                 (map edn/read-string))]
    {:major major :minor minor :patch patch
     :suffix suffix}))

(defn version-as-vstring [{:keys [major minor patch suffix]}]
  (format "%d.%d.%d%s" major minor patch suffix))

(defn inc-version-level [version level]
  (let [version (update-in version [level] inc)]
    (case level
      :major (assoc version :minor 0 :patch 0)
      :minor (assoc version :patch 0)
      version)))

(defn inc-version-in-string [s level]
  (-> s
      vstring-as-version
      (inc-version-level level)
      version-as-vstring))

(defn inc-version-in-file [s level]
  (update-version s #(inc-version-in-string % level)))

(deftask inc-version
  "Increment project version number."
  [l level LEVEL kw "Version level to increment."]
  (let [boot-build (io/file "build.boot")]
    (if-not (and level (.exists boot-build))
      (do
        (util/warn "No version level given.\n")
        identity)
      (with-pre-wrap fileset
        (let [old-boot-build (slurp boot-build)
              new-boot-build (inc-version-in-file old-boot-build level)]
          (when (not= old-boot-build new-boot-build)
            (let [fmt "Incrementing %s version number in build.boot...\n"]
              (util/info fmt (name level)))
            (spit boot-build new-boot-build)))
        fileset))))

(defn add-snapshot [vstr]
  (if (.contains vstr "-SNAPSHOT")
    vstr
    (str vstr "-SNAPSHOT")))

(deftask snapshotify-version
  "Add -SNAPSHOT to the version."
  []
  (let [boot-build (io/file "build.boot")]
    (if-not (.exists boot-build)
      identity
      (with-pre-wrap fileset
        (let [old-boot-build (slurp boot-build)
              new-boot-build (update-version old-boot-build
                                             add-snapshot)]
          (when (not= old-boot-build new-boot-build)
            (util/info "Appending SNAPSHOT to version...\n")
            (spit boot-build new-boot-build)))
        fileset))))

(deftask start-next
  "Start working on next release."
  [j major bool "Bump major version number."
   m minor bool "Bump minor version number."
   p patch bool "Bump patch version number."]
  (let [level (cond major :major
                    minor :minor
                    :else :patch)
        version (-> (get-current-version)
                    (inc-version-in-string level)
                    add-snapshot)
        msg (format "Start work on version %s" version)]
    (comp (snapshotify-version)
          (inc-version :level level)
          (update-readme-dependency :version version)
          (commit-files :files ["build.boot" "README.md"]
                        :message msg))))
