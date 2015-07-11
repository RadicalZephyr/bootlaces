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
    (vstring-as-numbers "0.1.0")
      => {:major 0 :minor 1 :patch 0 :suffix ""}
    (vstring-as-numbers "1.1.0")
      => {:major 1 :minor 1 :patch 0 :suffix ""}
    (vstring-as-numbers "3.4.8")
      => {:major 3 :minor 4 :patch 8 :suffix ""})

  (fact "version's can have optional suffixes after a dash"
    (vstring-as-numbers "0.1.0-SNAPSHOT")
      => {:major 0 :minor 1 :patch 0 :suffix "-SNAPSHOT"}
    (vstring-as-numbers "0.1.0-SNAPSHOT-arbitrary")
      => {:major 0 :minor 1 :patch 0 :suffix "-SNAPSHOT-arbitrary"}
    (vstring-as-numbers "0.41.0-SNAPSHOT-arbitrary")
      => {:major 0 :minor 41 :patch 0 :suffix "-SNAPSHOT-arbitrary"}))
