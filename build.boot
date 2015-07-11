(set-env!
 :resource-paths #{"src" "test"}
 :dependencies '[[org.clojure/clojure "1.6.0" :scope "provided"]
                 [boot/core           "2.1.2" :scope "provided"]
                 [midje               "1.7.0"          :scope "test"]
                 [zilti/boot-midje    "0.2.1-SNAPSHOT" :scope "test"]])

(require '[boot.git :refer [last-commit]]
         '[radicalzephyr.bootlaces :refer :all]
         '[zilti.boot-midje :refer [midje]])

(def +version+ "0.1.14-SNAPSHOT")

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
       :license        {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}}
 sift {:invert true
       :include [#".*_test.clj"]})
