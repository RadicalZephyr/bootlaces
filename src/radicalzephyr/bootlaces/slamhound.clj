(ns radicalzephyr.bootlaces.slamhound
  {:boot/export-tasks true}
  (:require [boot.core :as core]
            [boot.pod  :as pod]
            [boot.util  :as util]
            [clojure.tools.trace :as t]
            [clojure.string :as str])
  (:import (java.io File)
           (java.nio.file Path
                          Paths)))

(def pod-deps
  '[[slamhound "1.5.5"]])

(defn- init [fresh-pod]
  (pod/with-eval-in fresh-pod
    (require '[slam.hound])))

(defn- get-path [p & ps]
  (Paths/get p (into-array String ps)))

(defn- ns-of [^File f]
  (let [^Path path (.toPath f)
        ^Path root-path (get-path "/")]
    (cond->> path
      (.isAbsolute path) (.relativize root-path)
      :always            str
      :always            util/path->ns)))

(defn by-ns
  "This function takes two arguments `nses` and `files`, where `nses`
  is a seq of clojure ns name strings like `[\"foo.core\"
  \"foo.bar.baz\"]` and `files` is a seq of file objects. Returns a
  seq of the files in `files` which have paths corresponding to
  clojure namespace names listed in `nses`."
  [nses files & [negate?]]
  ((core/file-filter #(fn [f] (= (ns-of f) %))) nses files negate?))

(core/deftask slamhound
  "Run the slamhound namespace re-writer on a set of namespaces."
  [n namespaces NSES #{sym} "The set of namespaces to feed through the slamhound."]
  (let [worker-pod (pod/make-pod (update-in (core/get-env) [:dependencies] into pod-deps))]
    (init worker-pod)
    (core/cleanup (pod/destroy-pod worker-pod))
    (core/with-pre-wrap [fs]
      (let [clojure-files (->> (core/input-files fs)
                               (core/by-ext #{".clj"})
                               (by-ns namespaces))]
        (pod/with-eval-in worker-pod
          (apply slam.hound/-main ~(mapv (comp str core/tmp-file) clojure-files)))
        fs))))
