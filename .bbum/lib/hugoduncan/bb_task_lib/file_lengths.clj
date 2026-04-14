(ns hugoduncan.bb-task-lib.file-lengths
  "Fail when any file under the searched paths exceeds a line-length threshold.
  Suitable for pre-commit checks.

  Usage: bb file-lengths [--max-lines N] [path ...]

  Paths default to src/ and test/ if none are given.
  --max-lines defaults to 800.
  Exits 1 and prints offending files to stderr if any exceed the limit."
  (:require [babashka.fs     :as fs]
            [babashka.process :as proc]
            [clojure.string  :as str]))

(defn- parse-opts
  "Parse *command-line-args*. Returns {:max-lines N :paths [...]}.
   Positional args accumulate as paths; --max-lines N sets the threshold."
  [args]
  (loop [args      args
         max-lines 800
         paths     []]
    (if-let [arg (first args)]
      (cond
        (= arg "--max-lines")
        (if-let [n (some-> (second args) parse-long)]
          (recur (nnext args) n paths)
          (throw (ex-info "--max-lines requires an integer argument" {:args args})))
        :else
        (recur (next args) max-lines (conj paths arg)))
      {:max-lines max-lines
       :paths     (if (seq paths) paths ["src" "test"])})))

(defn run []
  (let [{:keys [max-lines paths]} (parse-opts *command-line-args*)
        existing                  (filterv #(fs/exists? %) paths)]
    (when (empty? existing)
      (println "No paths to check.")
      (System/exit 0))
    (let [result (apply proc/sh "find"
                        (concat existing ["-type" "f" "-exec" "wc" "-l" "{}" ";"]))
          bad    (->> (str/split-lines (:out result))
                      (keep (fn [line]
                              (let [[_ n path] (re-matches #"\s*(\d+)\s+(.+)" line)]
                                (when (and n path (> (parse-long n) max-lines))
                                  (str "  " path " (" n " lines)")))))
                      vec)]
      (when (seq bad)
        (binding [*out* *err*]
          (println (str "Files exceeding " max-lines " lines:"))
          (run! println bad))
        (System/exit 1)))))
