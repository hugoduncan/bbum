(ns bbum.print
  "Formatted terminal output utilities."
  (:require [clojure.string :as str]))

;;; Column-aligned table printing

(defn- col-widths
  "Compute max width of each column across all rows."
  [headers rows]
  (reduce (fn [ws row]
            (mapv (fn [w cell] (max w (count (str cell))))
                  ws row))
          (mapv count headers)
          rows))

(defn print-table
  "Print a simple fixed-width table.
   headers — vector of string column headers.
   rows    — seq of vectors, one per row, same arity as headers."
  [headers rows]
  (let [widths   (col-widths headers rows)
        fmt-cell (fn [w cell] (format (str "%-" w "s") (str cell)))
        sep-col  (fn [w] (apply str (repeat w \─)))
        row->str (fn [cells] (str/join "  " cells))]
    (println (row->str (mapv fmt-cell widths headers)))
    (println (row->str (mapv sep-col widths)))
    (doseq [row rows]
      (println (row->str (mapv fmt-cell widths row))))))
