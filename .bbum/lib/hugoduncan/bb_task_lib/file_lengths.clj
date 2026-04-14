(ns hugoduncan.bb-task-lib.file-lengths
  "List source file line counts, with optional filtering and a failure gate.

  Usage: bb file-lengths [--min-lines N] [--fail-above N] [path ...]

  Paths default to src/ and test/ if none are given.

  --min-lines N   Only show files with at least N lines in the output.
  --fail-above N  Exit 1 if any file exceeds N lines (violators printed to stderr).

  Both options are independent and may be combined."
  (:require [babashka.fs      :as fs]
            [babashka.process :as proc]
            [clojure.string   :as str]))

(defn- parse-opts [args]
  (loop [args       args
         min-lines  nil
         fail-above nil
         paths      []]
    (if-let [arg (first args)]
      (cond
        (= arg "--min-lines")
        (if-let [n (some-> (second args) parse-long)]
          (recur (nnext args) n fail-above paths)
          (throw (ex-info "--min-lines requires an integer argument" {:args args})))

        (= arg "--fail-above")
        (if-let [n (some-> (second args) parse-long)]
          (recur (nnext args) min-lines n paths)
          (throw (ex-info "--fail-above requires an integer argument" {:args args})))

        :else
        (recur (next args) min-lines fail-above (conj paths arg)))
      {:min-lines  min-lines
       :fail-above fail-above
       :paths      (if (seq paths) paths ["src" "test"])})))

(defn- file-rows [paths]
  (let [result (apply proc/sh "find"
                      (concat paths ["-type" "f" "-exec" "wc" "-l" "{}" ";"]))]
    (->> (str/split-lines (:out result))
         (keep (fn [line]
                 (let [[_ n path] (re-matches #"\s*(\d+)\s+(.+)" line)]
                   (when (and n path)
                     {:lines (parse-long n) :path path}))))
         (sort-by (juxt (comp - :lines) :path)))))

(defn run []
  (let [{:keys [min-lines fail-above paths]} (parse-opts *command-line-args*)
        existing (filterv #(fs/exists? %) paths)]
    (when (empty? existing)
      (println "No paths to check.")
      (System/exit 0))
    (let [rows     (file-rows existing)
          shown    (cond->> rows min-lines (filter #(>= (:lines %) min-lines)))
          failing  (when fail-above (filterv #(> (:lines %) fail-above) rows))]
      (doseq [{:keys [lines path]} shown]
        (println (format "%6d  %s" lines path)))
      (when (seq failing)
        (binding [*out* *err*]
          (println (str "Files exceeding " fail-above " lines:"))
          (doseq [{:keys [lines path]} failing]
            (println (format "  %s (%d lines)" path lines))))
        (System/exit 1)))))
