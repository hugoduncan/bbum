(ns bbum.config
  "Read/write all bbum file formats: global config, project manifest, bb.edn,
   library manifest. Also provides coord utilities and source merging."
  (:require [clojure.edn      :as edn]
            [clojure.java.io  :as io]
            [clojure.pprint   :as pprint]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip  :as z]))

;;; Low-level EDN I/O

(defn- read-edn
  "Read EDN from path. Returns nil if the file does not exist."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn- write-edn
  "Write data as pretty-printed EDN to path. Creates parent directories."
  [path data]
  (io/make-parents path)
  (spit path (with-out-str (pprint/pprint data))))

;;; Project root

(defn project-root
  "Walk up from cwd until a directory containing bb.edn is found.
   Returns the absolute path as a string, or throws if not found."
  []
  (loop [dir (io/file (System/getProperty "user.dir"))]
    (cond
      (nil? dir)
      (throw (ex-info "No bb.edn found in current or any parent directory" {}))

      (.exists (io/file dir "bb.edn"))
      (.getAbsolutePath dir)

      :else
      (recur (.getParentFile dir)))))

;;; Global config — ~/.bbum/config.edn

(defn- global-config-path []
  (str (System/getProperty "user.home") "/.bbum/config.edn"))

(defn read-global-config []
  (or (read-edn (global-config-path))
      {:sources {}}))

(defn write-global-config [config]
  (write-edn (global-config-path) config))

;;; Project manifest — <root>/.bbum.edn

(defn- project-manifest-path
  ([]     (project-manifest-path (project-root)))
  ([root] (str root "/.bbum.edn")))

(defn read-project-manifest
  ([]     (read-project-manifest (project-root)))
  ([root] (or (read-edn (project-manifest-path root))
              {:sources {} :tasks {}})))

(defn write-project-manifest
  ([data]      (write-project-manifest (project-root) data))
  ([root data] (write-edn (project-manifest-path root) data)))

;;; bb.edn — <root>/bb.edn

(defn- bb-edn-path
  ([]     (bb-edn-path (project-root)))
  ([root] (str root "/bb.edn")))

(defn read-bb-edn
  ([]     (read-bb-edn (project-root)))
  ([root] (or (read-edn (bb-edn-path root)) {})))

(defn write-bb-edn
  ([data]      (write-bb-edn (project-root) data))
  ([root data] (write-edn (bb-edn-path root) data)))

;;; bb.edn task and path management

(defn bb-edn-splice-tasks
  "Add task-map entries into bb.edn :tasks. Returns updated bb-edn map.
   Task keys are coerced to symbols — babashka's task runner requires symbol keys."
  [bb-edn task-map]
  (update bb-edn :tasks merge
          (into {} (map (fn [[k v]] [(symbol (name k)) v]) task-map))))

(defn bb-edn-remove-tasks
  "Remove task-keys from bb.edn :tasks. Returns updated bb-edn map.
   Accepts keyword or symbol task-keys."
  [bb-edn task-keys]
  (update bb-edn :tasks #(apply dissoc % (map (comp symbol name) task-keys))))

(defn bb-edn-ensure-path
  "Ensure path string is present in bb.edn :paths. Returns updated bb-edn map."
  [bb-edn path]
  (if ((set (:paths bb-edn)) path)
    bb-edn
    (update bb-edn :paths (fnil conj []) path)))

;;; Zipper-based bb.edn editing — preserves existing formatting and comments

(defn- z-get-or-create
  "Return a zipper loc for the map value at key k in map-zloc.
   If k is absent, assocs it with empty-val first.
   The returned loc is always the value node (inside the map)."
  [map-zloc k empty-val]
  (or (z/get map-zloc k)
      (z/get (z/assoc map-zloc k empty-val) k)))

(defn- z-climb-to-root
  "Return zloc navigated up to the root (the node with no parent)."
  [zloc]
  (loop [loc zloc]
    (if (nil? (z/up loc)) loc (recur (z/up loc)))))

(defn- detect-task-indent
  "Infer the indentation string used before task keys in the tasks map string
   by scanning for the first newline-then-whitespace-then-non-whitespace pattern.
   Defaults to a single space when no existing tasks are present."
  [tasks-str]
  (or (second (re-find #"\n(\s+)\S" tasks-str)) " "))

(defn- format-task-value
  "Pretty-print v and re-indent every continuation line by indent-str so that
   map keys align correctly when the value is placed after a leading indent-str."
  [v indent-str]
  (-> v
      (pprint/write :stream nil)
      clojure.string/trim-newline
      (clojure.string/replace #"\n" (str "\n" indent-str))))

(defn- ws-or-comment?
  "True when node is whitespace (spaces, newlines) or a comment."
  [node]
  (or (n/whitespace? node) (n/comment? node)))

(defn- z-append-map-entry
  "Append key k and value v to map-zloc.
   Detects existing sibling indentation and places the key on its own indented
   line, then the pprint-formatted value on the next line at the same indent.
   When k already exists its value is replaced in-place (no extra whitespace).
   Returns the map-zloc."
  [map-zloc k v]
  (if (z/get map-zloc k)
    (z/assoc map-zloc k v)
    (let [indent (detect-task-indent (z/string map-zloc))
          v-str  (format-task-value v indent)]
      (-> map-zloc
          (z/append-child (n/newline-node "\n"))
          (z/append-child (n/whitespace-node indent))
          (z/append-child k)
          (z/append-child (n/newline-node "\n"))
          (z/append-child (n/whitespace-node indent))
          (z/append-child (z/node (z/of-string v-str)))))))

;; ── Alphabetical insertion helpers ───────────────────────────────────────────

(defn- z-map-keys-ordered
  "Return the sexpr of each key in map-zloc in document order."
  [map-zloc]
  (loop [loc    (z/down map-zloc)
         result []
         is-key true]
    (cond
      (nil? loc)                        result
      (ws-or-comment? (z/node loc))     (recur (z/right loc) result is-key)
      is-key                            (recur (z/right loc) (conj result (z/sexpr loc)) false)
      :else                             (recur (z/right loc) result true))))

(defn- names-sorted?
  "True when names (a seq of strings) are in non-decreasing alphabetical order."
  [names]
  (or (< (count names) 2)
      (every? (fn [[a b]] (<= (compare a b) 0)) (partition 2 1 names))))

(defn- prefix-insertion-point
  "When new-name contains ':', look for existing task names that share the same
   prefix (the part before the first ':') and return {:after name} or
   {:before name} for the alphabetically correct position within that group.
   Returns nil when no tasks share the prefix."
  [existing-names new-name]
  (let [prefix       (first (clojure.string/split new-name #":" 2))
        prefix-tasks (filterv #(or (= % prefix)
                                   (clojure.string/starts-with? % (str prefix ":")))
                              existing-names)]
    (when (seq prefix-tasks)
      (let [before-new (filterv #(<= (compare % new-name) 0) prefix-tasks)]
        (if (seq before-new)
          {:after (last before-new)}
          {:before (first prefix-tasks)})))))

(defn- task-insertion-point
  "Given the tasks map zloc and the symbol for the new task, return one of:
     :append         — add at the end
     {:after  name}  — insert after the entry whose key name is `name`
     {:before name}  — insert before the entry whose key name is `name`"
  [map-zloc new-sym]
  (let [keys     (z-map-keys-ordered map-zloc)
        names    (mapv name keys)
        new-name (name new-sym)]
    (cond
      (empty? names)
      :append

      (names-sorted? names)
      (let [before-new (filterv #(< (compare % new-name) 0) names)
            after-new  (filterv #(> (compare % new-name) 0) names)]
        (cond
          (seq before-new) {:after  (last before-new)}
          (seq after-new)  {:before (first after-new)}
          :else            :append))

      (clojure.string/includes? new-name ":")
      (or (prefix-insertion-point names new-name) :append)

      :else :append)))

;; ── Positional zipper insertion ───────────────────────────────────────────────
;;
;; z/insert-left and z/insert-right inject automatic padding whitespace around
;; every inserted node, corrupting indentation.  Instead we:
;;   1. extract all existing entries as [key-sexpr value-string] in document order
;;   2. splice the new entry at the computed position
;;   3. rebuild the entire map string
;;   4. z/replace the tasks-map node with the fresh parse
;;
;; Existing value strings are lifted verbatim from z/string so their internal
;; formatting is preserved unchanged.

(defn- z-map-entries-ordered
  "Return [[key-sexpr value-string] …] in document order for map-zloc.
   Whitespace and comment nodes are skipped."
  [map-zloc]
  (loop [loc    (z/down map-zloc)
         result []
         k      nil]
    (cond
      (nil? loc)                    result
      (ws-or-comment? (z/node loc)) (recur (z/right loc) result k)
      (nil? k)                      (recur (z/right loc) result (z/sexpr loc))
      :else                         (recur (z/right loc) (conj result [k (z/string loc)]) nil))))

(defn- z-insert-map-entry
  "Insert key k with value v into map-zloc at the position AFTER after-name
   (a string key name).  Pass nil as after-name to insert at the beginning.
   Returns map-zloc with the node replaced."
  [map-zloc k v after-name]
  (let [map-str    (z/string map-zloc)
        indent     (detect-task-indent map-str)
        v-str      (format-task-value v indent)
        entries    (z-map-entries-ordered (z/of-string map-str))
        pos        (if (nil? after-name)
                     0
                     (inc (count (take-while #(not= (name (first %)) after-name) entries))))
        [bef aft]  (split-at pos entries)
        all-entries (concat bef [[k v-str]] aft)
        new-map-str (str "{"
                         (clojure.string/join ""
                           (map (fn [[ek ev]]
                                  (str "\n" indent (str ek) "\n" indent ev))
                                all-entries))
                         "}")]
    (z/replace map-zloc (z/node (z/of-string new-map-str)))))

(defn z-splice-tasks
  "Assoc each [sym task-def] from task-map into the :tasks map in the bb.edn
   top-level map zloc. Keys are coerced to symbols (babashka requires symbols).
   Creates :tasks {} if absent. New tasks are inserted alphabetically when
   existing tasks are sorted; otherwise grouped by ':'-delimited prefix, or
   appended. Returns the top-level map zloc."
  [zloc task-map]
  (reduce
   (fn [top [k v]]
     (let [sym       (symbol (name k))
           tasks-loc (z-get-or-create top :tasks {})]
       (if (z/get tasks-loc sym)
         ;; Existing key — replace value in-place, no position change
         (-> tasks-loc (z-append-map-entry sym v) z/up)
         ;; New key — determine the alphabetically correct insertion point
         (let [point     (task-insertion-point tasks-loc sym)
               ;; Convert {:before name} → after-name of the key that precedes it,
               ;; or nil (insert at beginning) when the before-key is first.
               after-name (cond
                            (= :append point) :append
                            (:after  point)   (:after point)
                            (:before point)
                            (let [names (mapv name (z-map-keys-ordered tasks-loc))
                                  idx   (.indexOf ^java.util.List names (:before point))]
                              (when (pos? idx) (nth names (dec idx))))
                            :else :append)
               new-tasks (if (= :append after-name)
                           (z-append-map-entry tasks-loc sym v)
                           (z-insert-map-entry tasks-loc sym v after-name))]
           (z/up new-tasks)))))
   zloc
   task-map))

(defn- z-remove-map-entry
  "Surgically remove sym and its value from a tasks-map zipper rooted at tasks-zloc.
   Works on tasks-zloc as a self-contained zipper so navigation back to the map
   root is unambiguous. Returns the (modified) root loc."
  [tasks-zloc sym]
  (if-let [val-loc (z/get tasks-zloc sym)]
    (-> val-loc z/remove z/remove z-climb-to-root)
    tasks-zloc))

(defn z-remove-tasks
  "Remove task-keys from the :tasks map in the bb.edn top-level map zloc.
   Keys are coerced to symbols. No-op when :tasks is absent or key is missing.
   Preserves all formatting outside the :tasks value node.
   Returns the top-level map zloc."
  [zloc task-keys]
  (if-let [tasks-loc (z/get zloc :tasks)]
    (let [syms         (map (comp symbol name) task-keys)
          new-tasks-str (reduce
                         (fn [s sym]
                           (-> s z/of-string (z-remove-map-entry sym) z/root-string))
                         (z/string tasks-loc)
                         syms)]
      (-> tasks-loc
          (z/replace (z/node (z/of-string new-tasks-str)))
          z/up))
    zloc))

(defn z-ensure-path
  "Ensure path string is present in the :paths vector of the bb.edn top-level
   map zloc. Creates :paths [path] if absent. Returns the top-level map zloc."
  [zloc path]
  (if-let [paths-loc (z/get zloc :paths)]
    (if (some #(= path %) (z/sexpr paths-loc))
      zloc
      (-> paths-loc (z/append-child path) z/up))
    (z/assoc zloc :paths [path])))

(defn update-bb-edn!
  "Apply zipper transform fn f to bb.edn at root, writing the result back.
   The file is read and written as a raw string — formatting and comments are
   preserved except where f explicitly modifies the tree.
   Falls back to '{}' if the file does not exist."
  ([f]      (update-bb-edn! (project-root) f))
  ([root f] (let [path    (bb-edn-path root)
                  file    (io/file path)
                  content (if (.exists file) (slurp file) "{}")
                  result  (-> content z/of-string f z/root-string)]
              (io/make-parents path)
              (spit path result))))

;;; Library manifest — bbum.edn at a resolved local path

(defn read-lib-manifest
  "Read bbum.edn from a local directory path. Throws if not found."
  [dir-path]
  (let [manifest-path (str dir-path "/bbum.edn")]
    (or (read-edn manifest-path)
        (throw (ex-info (str "No bbum.edn found at: " manifest-path)
                        {:path manifest-path})))))

;;; Task alias utilities

(defn lib-task-kw
  "Return the library task keyword for an installed task record.
   When no alias was set the installed key and lib key are the same,
   so this falls back to installed-kw."
  [installed-kw task-rec]
  (get task-rec :lib-task installed-kw))

;;; Source coord utilities

(defn coord-type
  "Returns the coord type keyword for a source coordinate map.
   One of: :local, :git/sha, :git/branch, :git/tag."
  [coord]
  (cond
    (:local/path coord) :local
    (:git/sha    coord) :git/sha
    (:git/branch coord) :git/branch
    (:git/tag    coord) :git/tag
    :else (throw (ex-info "Unrecognised coord type" {:coord coord}))))

;;; Effective sources (project overrides global)

(defn effective-sources
  "Merge global and project sources. Project sources shadow global ones of the same name.
   Returns map of name-kw → {:coord <coord> :origin :project|:global :shadowed? bool}."
  [global-config project-manifest]
  (let [global  (:sources global-config {})
        project (:sources project-manifest {})]
    (merge
     (into {} (map (fn [[k v]]
                     [k {:coord v :origin :global :shadowed? (contains? project k)}])
                   global))
     (into {} (map (fn [[k v]]
                     [k {:coord v :origin :project :shadowed? false}])
                   project)))))
