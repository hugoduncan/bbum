(ns hugoduncan.bb-task-lib.ref-report
  "Report over clojure-lsp analysis for public function visibility.
  Identifies public functions that are likely internal helpers and public
  functions referenced only by tests.

  Requires clojure-lsp to be installed and on the PATH."
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- classify-uri [uri]
  (cond
    (str/includes? uri "/src/") :src
    (str/includes? uri "/test/") :test
    :else :other))

(defn- project-file? [root uri]
  (str/starts-with? uri (str "file://" root)))

(defn- public-defn? [d]
  (and (= 'clojure.core/defn (:defined-by->lint-as d))
       (not (:private d))))

(defn- load-lsp-dump []
  (let [{:keys [exit out err] :as result}
        (sh/sh "clojure-lsp" "dump" "--project-root" "." "--raw")]
    (when-not (zero? exit)
      (throw (ex-info "clojure-lsp dump failed" result)))
    (when (seq err)
      (binding [*out* *err*]
        (println err)))
    (edn/read-string out)))

(def ^:private unused-public-var-message-re
  #"Unused public var '([^']+)'")

(defn- unused-public-vars [{:keys [diagnostics]}]
  (->> diagnostics
       vals
       (mapcat identity)
       (keep (fn [{:keys [code message]}]
               (when (= "clojure-lsp/unused-public-var" code)
                 (second (re-find unused-public-var-message-re message)))))
       set))

(defn- ref-rows [{:keys [analysis project-root] :as dump}]
  (let [files       (->> (keys analysis) (filter #(project-file? project-root %)) sort)
        defs        (mapcat (fn [uri]
                              (filter public-defn? (:var-definitions (get analysis uri))))
                            files)
        usages      (mapcat (fn [uri] (:var-usages (get analysis uri))) files)
        grouped     (group-by (fn [u] [(:to u) (:name u)]) usages)
        unused-vars (unused-public-vars dump)]
    (for [d defs
          :let [k            [(:ns d) (:name d)]
                refs         (remove #(and (= (:uri d) (:uri %))
                                           (= (:name d) (:from-var %)))
                                     (get grouped k []))
                src-refs     (filter #(= :src (classify-uri (:uri %))) refs)
                test-refs    (filter #(= :test (classify-uri (:uri %))) refs)
                external-src (filter #(and (= :src (classify-uri (:uri %)))
                                           (not= (:ns d) (:from %)))
                                     refs)
                var-name     (str (:ns d) "/" (:name d))
                test-only?   (and (empty? src-refs) (seq test-refs))
                privatize?   (and (seq src-refs) (empty? external-src))]]
      {:var                 var-name
       :src-ref-count       (count src-refs)
       :test-ref-count      (count test-refs)
       :src-ref-namespaces  (vec (sort (distinct (map :from src-refs))))
       :test-ref-namespaces (vec (sort (distinct (map :from test-refs))))
       :test-only?          test-only?
       :privatize?          privatize?
       :unused-public-var?  (contains? unused-vars var-name)})))

(defn- print-section [title rows]
  (println)
  (println title)
  (println (apply str (repeat (count title) "-")))
  (if (seq rows)
    (doseq [{:keys [var src-ref-count test-ref-count src-ref-namespaces test-ref-namespaces]} rows]
      (println var)
      (println "  src refs :" src-ref-count src-ref-namespaces)
      (println "  test refs:" test-ref-count test-ref-namespaces))
    (println "(none)")))

(defn run
  "Print a report of public functions that are good privatization candidates
  and public functions referenced only by tests."
  []
  (let [rows           (sort-by :var (vec (ref-rows (load-lsp-dump))))
        test-only      (filterv :test-only? rows)
        privatize-only (filterv #(and (:privatize? %) (not (:test-only? %))) rows)
        unreferenced   (filterv :unused-public-var? rows)]
    (println "Public function visibility report")
    (println "=================================")
    (println)
    (println "Rules:")
    (println "- test-only: no src references, at least one test reference")
    (println "- privatize candidate: all src references are from the defining namespace")
    (println "- unreferenced: flagged by clojure-lsp as clojure-lsp/unused-public-var")
    (print-section "Fns used only in tests" test-only)
    (print-section "Fns to privatize" privatize-only)
    (print-section "Unreferenced public fns" unreferenced)))
