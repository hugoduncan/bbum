(ns bbum.cmd.remove-test
  (:require [clojure.test      :refer [deftest testing is]]
            [babashka.fs       :as fs]
            [bbum.config       :as config]
            [bbum.cmd.add]
            [bbum.cmd.remove]
            [bbum.test-helpers :as h]))

;;; Private fn access

(def ^:private orphaned-implicits @#'bbum.cmd.remove/orphaned-implicits)
(def ^:private collect-removals   @#'bbum.cmd.remove/collect-removals)

;;; orphaned-implicits unit tests

(deftest orphaned-implicits-test
  ;; all-tasks map mirrors .bbum.edn :tasks structure
  (let [all-tasks {:main   {:install :explicit}
                   :helper {:install :implicit :required-by [:main]}
                   :shared {:install :implicit :required-by [:main :other]}
                   :other  {:install :explicit}}]

    (testing "implicit task with all requirers removed becomes orphan"
      (let [result (orphaned-implicits #{:main} all-tasks)]
        (is (contains? result :helper)
            ":helper is only required by :main, so it is orphaned")))

    (testing "implicit task still required by surviving task is not orphaned"
      (let [result (orphaned-implicits #{:main} all-tasks)]
        (is (not (contains? result :shared))
            ":shared is also required by :other which survives")))

    (testing "removing a task not referenced by any implicit has no orphans"
      (let [result (orphaned-implicits #{:other} all-tasks)]
        (is (empty? result))))))

;;; collect-removals unit tests

(deftest collect-removals-test
  (let [all-tasks {:main    {:install :explicit}
                   :helper  {:install :implicit :required-by [:main]}
                   :sub     {:install :implicit :required-by [:helper]}}]

    (testing "collects transitively orphaned implicits"
      ;; :main → :helper (orphaned) → :sub (orphaned when :helper also removed)
      (let [result (collect-removals #{:main} all-tasks)]
        (is (= #{:main :helper :sub} result))))

    (testing "no implicit orphans when explicit has no deps"
      (let [solo-tasks {:solo {:install :explicit}}
            result     (collect-removals #{:solo} solo-tasks)]
        (is (= #{:solo} result))))))

;;; Integration helpers

(defn- install-hello! [root lib-dir]
  (h/add-project-source! root :test-lib {:local/path lib-dir})
  (bbum.cmd.add/run ["test-lib" "hello"]))

(defn- install-hello-as! [root lib-dir alias]
  (h/add-project-source! root :test-lib {:local/path lib-dir})
  (bbum.cmd.add/run ["test-lib" "hello" "--as" alias]))

;;; Integration tests

(deftest remove-cleans-up-fully-test
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/simple-tasks)
      (install-hello! root (str lib))

      (bbum.cmd.remove/run ["hello"])

      (testing "file is removed from .bbum/lib"
        (is (not (fs/exists? (str root "/.bbum/lib/test_org/test_lib/hello.clj")))))

      (testing "task entry removed from bb.edn"
        (let [bb-edn (config/read-bb-edn root)]
          (is (not (contains? (:tasks bb-edn) 'hello)))))

      (testing "task record removed from .bbum.edn"
        (let [manifest (config/read-project-manifest root)]
          (is (not (contains? (:tasks manifest) :hello))))))))

(deftest remove-alias-deletes-correct-file-test
  ;; Regression: task-files used the installed alias name to look up :files in the
  ;; lib manifest. Since the manifest only knows the lib name, the lookup returned []
  ;; and the file was never removed.
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/simple-tasks)
      (install-hello-as! root (str lib) "greet")

      (testing "pre-condition: file exists after aliased install"
        (is (fs/exists? (str root "/.bbum/lib/test_org/test_lib/hello.clj"))))

      (bbum.cmd.remove/run ["greet"])

      (testing "file is removed when removing by alias"
        (is (not (fs/exists? (str root "/.bbum/lib/test_org/test_lib/hello.clj")))
            "lib file must be deleted even when task was installed under an alias"))

      (testing "aliased entry removed from bb.edn"
        (let [bb-edn (config/read-bb-edn root)]
          (is (not (contains? (:tasks bb-edn) 'greet)))))

      (testing "aliased record removed from .bbum.edn"
        (let [manifest (config/read-project-manifest root)]
          (is (not (contains? (:tasks manifest) :greet))))))))

(deftest remove-shared-file-kept-while-other-task-needs-it-test
  ;; File declared by two tasks must not be deleted while the second task stays.
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (let [shared-tasks
            {:task-a {:doc   "A"
                      :files ["src/test_org/test_lib/shared.clj"
                               "src/test_org/test_lib/a.clj"]
                      :task  '{:task (a/run)}}
             :task-b {:doc   "B"
                      :files ["src/test_org/test_lib/shared.clj"
                               "src/test_org/test_lib/b.clj"]
                      :task  '{:task (b/run)}}}]
        (h/make-lib! (str lib) 'test-org/test-lib shared-tasks)
        (h/add-project-source! root :test-lib {:local/path (str lib)})
        (bbum.cmd.add/run ["test-lib" "task-a" "task-b"])

        (bbum.cmd.remove/run ["task-a"])

        (testing "shared file kept because task-b still needs it"
          (is (fs/exists? (str root "/.bbum/lib/test_org/test_lib/shared.clj"))))

        (testing "task-a-only file is removed"
          (is (not (fs/exists? (str root "/.bbum/lib/test_org/test_lib/a.clj")))))))))

(deftest remove-warns-on-orphan-without-with-deps-test
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/dep-task-defs)
      (h/add-project-source! root :test-lib {:local/path (str lib)})
      (bbum.cmd.add/run ["test-lib" "main"])

      ;; Without --with-deps, remove of :main should warn and leave :helper in place
      (bbum.cmd.remove/run ["main"])

      (testing ":helper implicit task is not removed (user must confirm)"
        (let [manifest (config/read-project-manifest root)]
          (is (contains? (:tasks manifest) :helper)
              "implicit orphan must survive when --with-deps not given"))))))

(deftest remove-with-deps-removes-orphans-test
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/dep-task-defs)
      (h/add-project-source! root :test-lib {:local/path (str lib)})
      (bbum.cmd.add/run ["test-lib" "main"])

      (bbum.cmd.remove/run ["--with-deps" "main"])

      (testing "both main and orphaned helper are removed"
        (let [manifest (config/read-project-manifest root)]
          (is (empty? (:tasks manifest)))))

      (testing "all files are deleted"
        (is (not (fs/exists? (str root "/.bbum/lib/test_org/test_lib/main.clj"))))
        (is (not (fs/exists? (str root "/.bbum/lib/test_org/test_lib/helper.clj"))))))))

(deftest remove-implicit-task-directly-is-error-test
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/dep-task-defs)
      (h/add-project-source! root :test-lib {:local/path (str lib)})
      (bbum.cmd.add/run ["test-lib" "main"])

      (testing "cannot directly remove an implicit task"
        (is (thrown? Exception
              (bbum.cmd.remove/run ["helper"])))))))
