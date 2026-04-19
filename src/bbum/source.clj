(ns bbum.source
  "Source resolution: coord → locked-coord, coord → local directory, lib manifest fetch."
  (:require [babashka.fs      :as fs]
            [babashka.process :as proc]
            [bbum.config      :as config]
            [clojure.string   :as str]))

;;; Git remote queries

(defn- git-ls-remote
  "Run `git ls-remote url patterns...`.
   Returns map of ref-string → sha-string."
  [url & patterns]
  (let [{:keys [out exit err]}
        (apply proc/sh "git" "ls-remote" url patterns)]
    (when-not (zero? exit)
      (throw (ex-info (str "git ls-remote failed for: " url)
                      {:url url :stderr err})))
    (into {}
          (keep (fn [line]
                  (let [parts (str/split (str/trim line) #"\s+" 2)]
                    (when (= 2 (count parts))
                      [(second parts) (first parts)])))
                (remove str/blank? (str/split-lines out))))))

(defn- resolve-branch-sha
  "Resolve a branch to its current head sha."
  [url branch]
  (let [ref  (str "refs/heads/" branch)
        refs (git-ls-remote url ref)]
    (or (get refs ref)
        (throw (ex-info (str "Branch not found: " branch " at " url)
                        {:url url :branch branch})))))

(defn- resolve-tag-sha
  "Resolve a tag to the commit sha it points at.
   Prefers the peeled ref (^{}) which gives commit sha for annotated tags."
  [url tag]
  (let [ref        (str "refs/tags/" tag)
        peeled     (str ref "^{}")
        refs       (git-ls-remote url ref peeled)]
    (or (get refs peeled)
        (get refs ref)
        (throw (ex-info (str "Tag not found: " tag " at " url)
                        {:url url :tag tag})))))

;;; Coord validation

(defn- required-keys-check
  "Throw if any of required-keys are absent from coord."
  [coord required-keys]
  (let [missing (remove #(contains? coord %) required-keys)]
    (when (seq missing)
      (throw (ex-info (str "Coord is missing required keys: "
                           (str/join ", " (map str missing)))
                      {:coord coord :missing missing})))))

(defn validate-coord
  "Validate a coord structurally and, for git coords, verify reachability
   via git ls-remote. Throws ex-info on failure."
  [coord]
  (case (config/coord-type coord)
    :local
    (do (required-keys-check coord [:local/path])
        (when-not (fs/exists? (:local/path coord))
          (throw (ex-info (str "Local source path not found: " (:local/path coord))
                          {:path (:local/path coord)}))))

    :git/sha
    (required-keys-check coord [:git/url :git/sha])

    :git/branch
    (do (required-keys-check coord [:git/url :git/branch])
        (resolve-branch-sha (:git/url coord) (:git/branch coord))
        nil)

    :git/tag
    (do (required-keys-check coord [:git/url :git/tag])
        (resolve-tag-sha (:git/url coord) (:git/tag coord))
        nil)))

;;; Coord resolution

(defn resolve-coord
  "Resolve a source coord to its locked form.
   :local and :git/sha coords pass through unchanged.
   :git/branch and :git/tag coords are resolved to their current head sha."
  [coord]
  (case (config/coord-type coord)
    :local      coord
    :git/sha    coord
    :git/branch (let [{:git/keys [url branch]} coord]
                  {:git/url url :git/sha (resolve-branch-sha url branch)})
    :git/tag    (let [{:git/keys [url tag]} coord]
                  {:git/url url :git/sha (resolve-tag-sha url tag)})))

;;; Clone helpers

(defn- git-clone-branch
  "Shallow-clone a specific branch or tag into dir."
  [url ref-name dir]
  (proc/shell "git" "clone" "--depth" "1" "--branch" ref-name url dir))

(defn- git-clone-sha
  "Clone url then check out a specific sha into dir.
   Attempts a shallow fetch-by-sha first (fast); falls back to full fetch."
  [url sha dir]
  (proc/shell "git" "init" dir)
  (proc/shell {:dir dir} "git" "remote" "add" "origin" url)
  (let [shallow-ok? (zero? (:exit (proc/sh "git" "-C" dir "fetch"
                                           "--depth" "1" "origin" sha)))]
    (when-not shallow-ok?
      (proc/shell {:dir dir} "git" "fetch" "origin")))
  (proc/shell {:dir dir} "git" "checkout" sha))

;;; Source materialisation

(defn with-source-dir
  "Call f with a local directory path that contains the source files.
   For :local coords, f receives :local/path directly.
   For :git/* coords, a temp clone is made, f is called, then cleaned up.
   Returns whatever f returns."
  [coord f]
  (case (config/coord-type coord)
    :local
    (let [path (:local/path coord)]
      (when-not (fs/exists? path)
        (throw (ex-info (str "Local source path not found: " path)
                        {:path path})))
      (f path))

    :git/branch
    (fs/with-temp-dir [tmpdir {:prefix "bbum-src-"}]
      (git-clone-branch (:git/url coord) (:git/branch coord) (str tmpdir))
      (f (str tmpdir)))

    :git/tag
    (fs/with-temp-dir [tmpdir {:prefix "bbum-src-"}]
      (git-clone-branch (:git/url coord) (:git/tag coord) (str tmpdir))
      (f (str tmpdir)))

    :git/sha
    (fs/with-temp-dir [tmpdir {:prefix "bbum-src-"}]
      (git-clone-sha (:git/url coord) (:git/sha coord) (str tmpdir))
      (f (str tmpdir)))))

;;; Library manifest

(defn read-lib-manifest
  "Fetch and return the bbum.edn manifest from a source coord."
  [coord]
  (with-source-dir coord config/read-lib-manifest))

;;; Source name resolution

(defn resolve-source-name
  "Look up source-name-kw in project-manifest then global-config.
   Returns [coord origin-kw] where origin is :project or :global.
   Throws if the source name is not found."
  [source-name-kw project-manifest global-config]
  (or (when-let [c (get-in project-manifest [:sources source-name-kw])]
        [c :project])
      (when-let [c (get-in global-config [:sources source-name-kw])]
        [c :global])
      (throw (ex-info (str "Unknown source: " (name source-name-kw))
                      {:source source-name-kw}))))
