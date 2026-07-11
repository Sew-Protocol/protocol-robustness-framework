(ns build
  "Build source-bundled uberjar (no AOT) for PRF scenario runner.
   Four variants: core, sew, benchmark, and cli.

   Usage:
     clojure -T:build uberjar :variant core
     clojure -T:build uberjar :variant sew
     clojure -T:build uberjar :variant benchmark
     clojure -T:build uberjar :variant cli"
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
        is-cli (= vname "cli")
        main-cls (or main (cond
                            is-benchmark "resolver-sim.benchmark.cli"
                            is-cli "resolver-sim.cli.main"
                            is-core "resolver-sim.replay-core"
                            :else "resolver-sim.sew-bootstrap"))
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
         jar-file (if is-cli "target/prf.jar" (str "target/prf-runner-" vname "-" version ".jar"))
         uber-file (if is-cli "target/prf-uber.jar" (str "target/prf-runner-" vname "-" version "-uber.jar"))]

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
    ;; Remove test directories from class-dir to prevent AOT from picking them up
    (doseq [test-dir ["test" "protocols_src/test"]]
      (let [td (java.io.File. class-dir test-dir)]
        (when (.exists td)
          (printf "  Removing test dir: %s\n" test-dir)
          (b/delete {:path (str td)}))))
    ;; Copy data dirs preserving directory name (b/copy-dir flattens contents,
    ;; so copy each dir into a subdirectory of class-dir)
    (when (or is-benchmark is-cli)
      (doseq [extra-dir (if is-cli
                          ["resources/prf"]
                          ["scenarios" "benchmarks" "data" "suites" "config"])]
        (let [d (java.io.File. extra-dir)]
          (when (.exists d)
            (printf "    %s/ -> class-dir/%s/\n" extra-dir extra-dir)
            (.mkdirs (java.io.File. class-dir extra-dir))
            (b/copy-dir {:src-dirs [extra-dir]
                         :target-dir (str class-dir "/" extra-dir)})))))

    ;; Build manifest for the JAR
    ;; Add a marker file to indicate this is a source-only JAR
    (.mkdirs (java.io.File. (str class-dir "/META-INF")))
    (spit (str class-dir "/META-INF/prf-runner.edn")
          (pr-str {:variant vname :main main-cls :version version :source-only true
                   :built-at (str (java.time.Instant/now))}))

    ;; AOT compile the bootstrapper for benchmark variant (minimal deps).
    ;; Must be done before copying other sources to avoid namespace collisions.
    (when is-benchmark
      (println "  Compiling AOT for benchmark bootstrapper...")
      (let [bs-deps (pr-str '{:deps {org.clojure/clojure {:mvn/version "1.12.0"}}
                              :paths ["scripts/benchmark-bootstrap"]})
            bs-deps-path (str (System/getProperty "java.io.tmpdir")
                              "/prf-bs-deps-" (System/nanoTime) ".edn")]
        (spit bs-deps-path bs-deps)
        (let [bs-basis (b/create-basis {:project bs-deps-path})]
          (b/compile-clj {:basis bs-basis
                          :src-dirs ["scripts/benchmark-bootstrap"]
                          :class-dir class-dir
                          :ns-compile-command ['resolver-sim.benchmark.main]}))
        (io/delete-file bs-deps-path)))

    ;; AOT compile the bootstrapper for sew variant (minimal deps).
    (when (and (not is-core) (not is-benchmark) (not is-cli))
      (println "  Compiling AOT for sew bootstrapper...")
      (let [bs-deps (pr-str '{:deps {org.clojure/clojure {:mvn/version "1.12.0"}}
                              :paths ["scripts/sew-bootstrap"]})
            bs-deps-path (str (System/getProperty "java.io.tmpdir")
                              "/prf-bs-deps-" (System/nanoTime) ".edn")]
        (spit bs-deps-path bs-deps)
        (let [bs-basis (b/create-basis {:project bs-deps-path})]
          (b/compile-clj {:basis bs-basis
                          :src-dirs ["scripts/sew-bootstrap"]
                          :class-dir class-dir
                          :ns-compile-command ['resolver-sim.sew-bootstrap]}))
        (io/delete-file bs-deps-path)))

    ;; Build JAR(s)
    (if is-cli
      ;; CLI variant: AOT compile a minimal bootstrapper (no protocol deps),
      ;; then build standalone uberjar with Main-Class pointing at it.
      (let [main-sym 'resolver-sim.cli-bootstrap
            bs-deps (pr-str '{:deps {org.clojure/clojure {:mvn/version "1.12.0"}}
                             :paths ["scripts/cli-bootstrap"]})
            bs-deps-path (str (System/getProperty "java.io.tmpdir")
                              "/prf-bs-deps-" (System/nanoTime) ".edn")]
        (spit bs-deps-path bs-deps)
        (let [bs-basis (b/create-basis {:project bs-deps-path})]
          (b/compile-clj {:basis bs-basis
                          :src-dirs ["scripts/cli-bootstrap"]
                          :class-dir class-dir
                          :ns-compile-command ['resolver-sim.cli-bootstrap]}))
        (io/delete-file bs-deps-path)
        (println "  Building CLI uberjar (Main-Class:" main-sym ")...")
        (b/uber {:class-dir class-dir
                 :uber-file "target/prf.jar"
                 :basis basis
                 :main main-sym}))
      ;; Other variants: build both source JAR and uberjar
      (let [main-sym (cond
                       is-benchmark 'resolver-sim.benchmark.main
                       (not is-core) 'resolver-sim.sew-bootstrap
                       :else 'clojure.main)]
        (println "  Building source JAR (Main-Class:" main-sym ")...")
        (b/jar {:class-dir class-dir
                :jar-file jar-file
                :lib lib
                :version version
                :main main-sym})

        (println "  Building source uberjar (Main-Class:" main-sym ")...")
        (b/uber {:class-dir class-dir
                 :uber-file uber-file
                 :basis basis
                 :main main-sym})))

    ;; Cleanup
    (b/delete {:path class-dir})
    (io/delete-file deps-path)

    ;; Report
    (println "\n=== Results ===")
    (doseq [f (if is-cli ["target/prf.jar"] [jar-file uber-file])]
      (let [jf (java.io.File. f)]
        (when (.exists jf)
          (printf "  %-50s %d KB\n" (.getName jf) (quot (.length jf) 1024)))))
    (if is-core
      (printf "\n  Source-only build (no AOT).\n")
      (printf "\n  AOT bootstrapper compiled for JAR Main-Class.\n"))
    (println "  Done.\n")
    (flush)))

(defn aot-sew
  "AOT-compile SEW protocol source dirs only (protocols_src, then src)
   for faster test startup.  Writes .class files to target/classes.
   
   Uses separate passes so a failure in src (deeper deps) doesn't
   block the protocol layer compilation.
   
   Usage: clojure -T:build aot-sew"
  [_]
  (let [basis    (b/create-basis {:project "deps.edn"
                                  :aliases [:test :with-sew]})
        class-dir "target/classes"]
    (.mkdirs (java.io.File. class-dir))
    (println "\n=== AOT compile SEW protocol sources ===")
    (doseq [src-dir ["protocols_src" "src"]]
      (let [d (java.io.File. src-dir)]
        (when (.exists d)
          (try
            (println (str "  Compiling " src-dir "..."))
            (b/compile-clj {:basis basis
                            :src-dirs [src-dir]
                            :class-dir class-dir})
            (println (str "    OK: " src-dir))
            (catch Exception e
              (println (str "    WARN: " src-dir " — " (.getMessage e))))))))
    (println "\n  Done.\n")))
