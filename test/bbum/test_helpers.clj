(ns bbum.test-helpers
  "Shared test infrastructure: environment isolation and fixture builders."
  (:require [babashka.fs     :as fs]
            [bbum.config     :as config]
            [clojure.pprint  :as pprint]))

;;; Environment isolation

(defmacro with-clean-env
  "Run body with user.dir bound to a fresh temp project dir.
   user.home is also isolated so tests never touch ~/.bbum/config.edn.
   Binds root-sym to the absolute project root path string."
  [[root-sym] & body]
  `(fs/with-temp-dir [project# {:prefix "bbum-proj-"}]
     (fs/with-temp-dir [home# {:prefix "bbum-home-"}]
       (let [old-dir#  (System/getProperty "user.dir")
             old-home# (System/getProperty "user.home")]
         (System/setProperty "user.dir"  (str project#))
         (System/setProperty "user.home" (str home#))
         (try
           (let [~root-sym (str project#)]
             ~@body)
           (finally
             (System/setProperty "user.dir"  old-dir#)
             (System/setProperty "user.home" old-home#)))))))

;;; Fixture builders

(defn make-project!
  "Write a minimal bb.edn and empty .bbum.edn into root."
  [root]
  (config/write-bb-edn      root {:paths ["src"] :tasks {}})
  (config/write-project-manifest root {:sources {} :tasks {}}))

(defn make-lib!
  "Write bbum.edn with lib-name and tasks into lib-dir.
   Creates stub .clj files for every path declared in :files."
  [lib-dir lib-name tasks]
  (spit (str lib-dir "/bbum.edn")
        (with-out-str (pprint/pprint {:lib lib-name :tasks tasks})))
  (doseq [[_ {:keys [files]}] tasks
          file-path            files]
    (let [dest (str lib-dir "/" file-path)]
      (fs/create-dirs (fs/parent dest))
      (spit dest "(ns stub)\n"))))

(defn add-project-source!
  "Register a project-level source in root's .bbum.edn."
  [root name-kw coord]
  (let [manifest (config/read-project-manifest root)]
    (config/write-project-manifest root
      (assoc-in manifest [:sources name-kw] coord))))

;;; Simple task definitions for tests

(def simple-task-def
  {:doc   "A simple test task"
   :files ["src/test_org/test_lib/hello.clj"]
   :task  '{:doc "A simple test task"
            :requires ([test-org.test-lib.hello :as hello])
            :task (hello/run)}})

(def simple-tasks {:hello simple-task-def})

(def dep-task-defs
  {:main {:doc     "Main task"
          :files   ["src/test_org/test_lib/main.clj"]
          :depends [:helper]
          :task    '{:doc "Main" :requires ([test-org.test-lib.main :as m]) :task (m/run)}}
   :helper {:doc  "Helper task"
            :files ["src/test_org/test_lib/helper.clj"]
            :task '{:doc "Helper" :requires ([test-org.test-lib.helper :as h]) :task (h/run)}}})
