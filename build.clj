(ns build
  "Build source-bundled uberjar (no AOT) for PRF scenario runner.
   Three variants: core, sew, and benchmark.

   Usage:
     clojure -T:build uberjar :variant core
     clojure -T:build uberjar :variant sew
     clojure -T:build uberjar :variant benchmark"
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b]))

(def version "0.1.0")

(defn uberjar
  [{:keys [variant main]
    :or   {variant "sew"
           main   nil}}]
  (let [vname (name variant)
        is-core (= vname "core")
        is-benchmark (= vname "benchmark")
        main-cls (or main (cond
                            is-benchmark "resolver-sim.benchmark.cli"
                            (not is-core) "resolver-sim.minimal-runner"
                            :else "resolver-sim.replay-core"))
        lib (symbol (str "resolver-sim/prf-runner-" vname))

        ;; Build deps file for clean classpath
        core-deps-str (pr-str
                       '{:deps {org.clojure/clojure {:mvn/version "1.12.0"}
                                org.clojure/tools.logging {:mvn/version "1.2.4"}
                                org.slf4j/slf4j-api {:mvn/version "1.7.36"}
                                org.slf4j/slf4j-simple {:mvn/version "1.7.36"}
                                org.clojure/tools.cli {:mvn/version "1.0.219"}
                                org.clojure/data.json {:mvn/version "2.4.0"}}
                         :paths ["src" "resources"]})
        sew-deps-str (pr-str
                      '{:deps {org.clojure/clojure {:mvn/version "1.12.0"}
                               org.clojure/tools.logging {:mvn/version "1.2.4"}
                               org.slf4j/slf4j-api {:mvn/version "1.7.36"}
                               org.slf4j/slf4j-simple {:mvn/version "1.7.36"}
                               org.clojure/tools.cli {:mvn/version "1.0.219"}
                               org.clojure/data.json {:mvn/version "2.4.0"}
                               buddy/buddy-core {:mvn/version "1.12.0-430"}
                               com.github.seancorfield/next.jdbc {:mvn/version "1.3.939"}
                               org.postgresql/postgresql {:mvn/version "42.7.2"}
                               metosin/malli {:mvn/version "0.17.0"}}
                        :paths ["src" "protocols_src" "resources"]})
        deps-str (cond
                   is-core core-deps-str
                   is-benchmark sew-deps-str  ;; benchmark needs same deps as sew (buddy, tools.cli, protocols_src)
                   :else sew-deps-str)
        deps-path (str (System/getProperty "java.io.tmpdir")
                       "/prf-build-deps-" (System/nanoTime) ".edn")
        _ (spit deps-path deps-str)
        basis (b/create-basis {:project deps-path})
        src-dirs (if (or is-benchmark (not is-core)) ["src" "protocols_src" "resources"] ["src" "resources"])
        class-dir (str (System/getProperty "java.io.tmpdir")
                       "/prf-build-" (System/nanoTime))
        jar-file (str "target/prf-runner-" vname "-" version ".jar")
        uber-file (str "target/prf-runner-" vname "-" version "-uber.jar")]

    (println "\n=== Build: prf-runner-" vname " ===")
    (printf "  Main class: %s\n" main-cls)
    (printf "  Source dirs: %s\n" (pr-str src-dirs))

    ;; Copy source + resources to class dir
    (println "\n  Copying source...")
    (doseq [sd src-dirs]
      (let [d (java.io.File. sd)]
        (when (.exists d)
          (printf "    %s\n" sd)
          (b/copy-dir {:src-dirs [sd] :target-dir class-dir}))))

    ;; Build manifest for the JAR
    ;; Add a marker file to indicate this is a source-only JAR
    (.mkdirs (java.io.File. (str class-dir "/META-INF")))
    (spit (str class-dir "/META-INF/prf-runner.edn")
          (pr-str {:variant vname :main main-cls :version version :source-only true
                   :built-at (str (java.time.Instant/now))}))

    ;; Build JAR (source only, no AOT; Main-Class = clojure.main)
    (println "  Building source JAR...")
    (b/jar {:class-dir class-dir
            :jar-file jar-file
            :lib lib
            :version version
            :main 'clojure.main})

    ;; Build uberjar (source + dependency JARs, no AOT; Main-Class = clojure.main)
    (println "  Building source uberjar...")
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'clojure.main})

    ;; Cleanup
    (b/delete {:path class-dir})
    (io/delete-file deps-path)

    ;; Report
    (println "\n=== Results ===")
    (doseq [f [jar-file uber-file]]
      (let [jf (java.io.File. f)]
        (printf "  %-50s %d KB\n" (.getName jf) (quot (.length jf) 1024))))
    (printf "\n  Source-only build (no AOT).\n")
    (println "  Done.\n")
    (flush)))
