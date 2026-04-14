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

(defn- pprint-node
  "Return a rewrite-clj node for v formatted via pprint.
   Produces multi-line output for complex values rather than the compact
   single-line representation that n/coerce generates."
  [v]
  (-> v
      (pprint/write :stream nil)
      clojure.string/trim-newline
      z/of-string
      z/node))

(defn- z-append-map-entry
  "Append key k and value v to map-zloc, preceded by a newline separator.
   The value is formatted via pprint so complex maps/lists are readable.
   When k already exists its value is replaced in-place (no extra whitespace).
   Returns the map-zloc."
  [map-zloc k v]
  (if (z/get map-zloc k)
    (z/assoc map-zloc k v)
    (-> map-zloc
        (z/append-child (n/newline-node "\n"))
        (z/append-child k)
        (z/append-child (n/whitespace-node " "))
        (z/append-child (pprint-node v)))))

(defn z-splice-tasks
  "Assoc each [sym task-def] from task-map into the :tasks map in the bb.edn
   top-level map zloc. Keys are coerced to symbols (babashka requires symbols).
   Creates :tasks {} if absent. A newline is inserted before each new key.
   Returns the top-level map zloc."
  [zloc task-map]
  (reduce
   (fn [top [k v]]
     (let [sym       (symbol (name k))
           tasks-loc (z-get-or-create top :tasks {})]
       (-> tasks-loc (z-append-map-entry sym v) z/up)))
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
