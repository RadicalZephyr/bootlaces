(ns radicalzephyr.bootlaces
  {:boot/export-tasks true}
  (:require
   [clojure.java.io    :as io]
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
  [version & {:keys [dev-dependencies]}]
  (merge-env! :resource-paths #{"src"})
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

(defn- get-creds []
  (mapv #(System/getenv %) ["CLOJARS_USER" "CLOJARS_PASS"]))

(deftask ^:private collect-clojars-credentials
  "Collect CLOJARS_USER and CLOJARS_PASS from the user if they're not set."
  []
  (fn [next-handler]
    (fn [fileset]
      (let [[user pass] (get-creds), clojars-creds (atom {})]
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
  []
  (comp (pom) (jar) (install) (update-readme-dependency)))

(defn de-snapshot-version-def
  [s]
  (if-let [[whole version] (re-find #"\(def \+version\+ \"(.*)-SNAPSHOT\"\)"
                                    s)]
    (.replace s whole (format "(def +version+ \"%s\")" version))
    s))

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
      (let [worker-pod (worker-pods :refresh)]
        (pod/with-eval-in worker-pod
          (jgit/with-repo "."
            (doseq [file ~files]
              (jgit/git-add repo file))
            (jgit/git-commit repo ~message))))
      fileset)))

(deftask build-release
  [v version str "The current project version number."]
  (if-not (git/clean?)
      identity
      (comp (de-snapshot-version)
            (build-jar)
            (commit-files :files ["build.boot" "README.md"]
                          :message (str "Release " version)))))

(deftask push-snapshot
  "Deploy snapshot version to Clojars."
  [f file PATH str "The jar file to deploy."]
  (comp (collect-clojars-credentials)
        (push :file file :ensure-snapshot true)))

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
