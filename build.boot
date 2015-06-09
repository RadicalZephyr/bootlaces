(set-env!
 :resource-paths #{"src"}
 :dependencies '[[org.clojure/clojure "1.6.0" :scope "provided"]
                 [boot/core           "2.1.2" :scope "provided"]])

(require '[boot.git :refer [last-commit]]
         '[radicalzephyr.bootlaces :refer :all])

(def +version+ "0.1.11")

(bootlaces! +version+)

(task-options!
 push {:repo           "deploy"
       :ensure-branch  "master"
       :ensure-clean   true
       :ensure-tag     (last-commit)
       :ensure-version +version+}
 pom  {:project        'radicalzephyr/bootlaces
       :version        +version+
       :description    "RadicalZephyr's boot configurations for Clojure libraries "
       :url            "https://github.com/radicalzephyr/bootlaces"
       :scm            {:url "https://github.com/radicalzephyr/bootlaces"}
       :license        {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}})
