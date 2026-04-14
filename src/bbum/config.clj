(ns bbum.config
  "Read/write all bbum file formats: global config, project manifest, bb.edn,
   library manifest. Also provides coord utilities and source merging."
  (:require [clojure.edn    :as edn]
            [clojure.java.io :as io]
            [clojure.pprint  :as pprint]))

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
