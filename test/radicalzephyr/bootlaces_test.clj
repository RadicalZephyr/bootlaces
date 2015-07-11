(ns radicalzephyr.bootlaces-test
  (:require [radicalzephyr.bootlaces :refer :all]
            [midje.sweet :refer :all]))

(facts "about de-snapshot-version"
  (de-snapshot-version-def
   "anything-else") => "anything-else"
  (de-snapshot-version-def
   "(def +version+ \"0.1.0\")") => "(def +version+ \"0.1.0\")"
  (de-snapshot-version-def
   "(def +version+ \"0.1.0-SNAPSHOT\")") => "(def +version+ \"0.1.0\")")

(facts "about version string transformation"
  (update-version "anything" first) => "anything"
  (update-version "(def +version+ \"0.1.0\")" first)
    => "(def +version+ \"0\")")

(facts "about incrementing version numbers"
  (fact "a version string is three numbers separated by dots"
    (vstring-as-version "0.1.0")
      => {:major 0 :minor 1 :patch 0 :suffix ""}
    (vstring-as-version "1.1.0")
      => {:major 1 :minor 1 :patch 0 :suffix ""}
    (vstring-as-version "3.4.8")
      => {:major 3 :minor 4 :patch 8 :suffix ""})

  (fact "version's can have optional suffixes after a dash"
    (vstring-as-version "0.1.0-SNAPSHOT")
      => {:major 0 :minor 1 :patch 0 :suffix "-SNAPSHOT"}
    (vstring-as-version "0.1.0-SNAPSHOT-arbitrary")
      => {:major 0 :minor 1 :patch 0 :suffix "-SNAPSHOT-arbitrary"}
    (vstring-as-version "0.41.0-SNAPSHOT-arbitrary")
      => {:major 0 :minor 41 :patch 0 :suffix "-SNAPSHOT-arbitrary"})

  (fact "incrementing a level resets all lower levels"
    (inc-version-level {:major 0 :minor 41 :patch 1 :suffix "-SNAPSHOT-arbitrary"} :major)
      => {:major 1 :minor 0 :patch 0 :suffix "-SNAPSHOT-arbitrary"}
    (inc-version-level {:major 0 :minor 41 :patch 1 :suffix "-SNAPSHOT-arbitrary"} :minor)
      => {:major 0 :minor 42 :patch 0 :suffix "-SNAPSHOT-arbitrary"}
    (inc-version-level {:major 0 :minor 41 :patch 1 :suffix "-SNAPSHOT-arbitrary"} :patch)
      => {:major 0 :minor 41 :patch 2 :suffix "-SNAPSHOT-arbitrary"})

  (fact "a version map can be turned back into a string"
    (version-as-vstring {:major 0 :minor 41 :patch 2 :suffix "-SNAP"})
      => "0.41.2-SNAP"
    (version-as-vstring {:major 1 :minor 0 :patch 2 :suffix ""})
      => "1.0.2")

  (fact "a version string can be incremented"
    (inc-version-in-string "0.1.0" :minor) => "0.2.0"
    (inc-version-in-string "2.1.0" :minor) => "2.2.0"))
