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
