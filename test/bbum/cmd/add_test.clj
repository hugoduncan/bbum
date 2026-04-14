(ns bbum.cmd.add-test
  (:require [clojure.test     :refer [deftest testing is]]
            [babashka.fs      :as fs]
            [bbum.config      :as config]
            [bbum.cmd.add]
            [bbum.test-helpers :as h]))

;;; Private fn access

(def ^:private resolve-task-set  @#'bbum.cmd.add/resolve-task-set)
(def ^:private parse-args        @#'bbum.cmd.add/parse-args)
(def ^:private dest-relative-path @#'bbum.cmd.add/dest-relative-path)

;;; resolve-task-set unit tests

(def ^:private simple-manifest
  {:lib   'test-org/test-lib
   :tasks {:hello {:doc "Hello" :files ["src/hello.clj"]
                   :task '{:doc "Hello" :task (hello/run)}}}})

(def ^:private dep-manifest
  {:lib   'test-org/test-lib
   :tasks {:main   {:doc "Main" :files ["src/main.clj"] :depends [:helper]
                    :task '{:task (main/run)}}
           :helper {:doc "Helper" :files ["src/helper.clj"]
                    :task '{:task (helper/run)}}}})

(def ^:private cycle-manifest
  {:lib   'test-org/test-lib
   :tasks {:a {:doc "A" :depends [:b] :files [] :task '(a)}
           :b {:doc "B" :depends [:a] :files [] :task '(b)}}})

(deftest resolve-task-set-test
  (testing "single explicit task, no deps"
    (let [result (resolve-task-set simple-manifest [:hello] {})]
      (is (= #{:hello} (set (keys result))))
      (is (= :explicit (get-in result [:hello :install])))))

  (testing "explicit task with one dep"
    (let [result (resolve-task-set dep-manifest [:main] {})]
      (is (= #{:main :helper} (set (keys result))))
      (is (= :explicit  (get-in result [:main   :install])))
      (is (= :implicit  (get-in result [:helper :install])))
      (is (= [:main]    (get-in result [:helper :required-by])))))

  (testing "alias map renames explicit task in result"
    (let [result (resolve-task-set simple-manifest [:hello] {:hello :greet})]
      (is (contains? result :greet)  "aliased name in result")
      (is (not (contains? result :hello)) "original name not present")
      (is (= :hello (get-in result [:greet :lib-task])))
      (is (= :explicit (get-in result [:greet :install])))))

  (testing "error on unknown task"
    (is (thrown? Exception
          (resolve-task-set simple-manifest [:nonexistent] {}))))

  (testing "error on dependency cycle"
    (is (thrown? Exception
          (resolve-task-set cycle-manifest [:a] {}))))

  (testing "shared dep required by multiple explicit tasks gets extended required-by"
    (let [multi-manifest
          {:lib   'test-org/test-lib
           :tasks {:a      {:doc "A" :files [] :depends [:shared] :task '(a)}
                   :b      {:doc "B" :files [] :depends [:shared] :task '(b)}
                   :shared {:doc "S" :files [] :task '(s)}}}
          result (resolve-task-set multi-manifest [:a :b] {})]
      (is (= #{:a :b :shared} (set (keys result))))
      (is (= 2 (count (get-in result [:shared :required-by])))))))

;;; dest-relative-path unit tests

(deftest dest-relative-path-test
  (testing "strips leading src/ prefix"
    (is (= "my_org/lib/foo.clj"
           (dest-relative-path "src/my_org/lib/foo.clj"))))

  (testing "passes through paths without src/ prefix"
    (is (= "resources/config.edn"
           (dest-relative-path "resources/config.edn")))))

;;; parse-args unit tests

(deftest parse-args-test
  (testing "single task"
    (let [result (parse-args ["my-src" "my-task"])]
      (is (= "my-src"    (:source-name result)))
      (is (= ["my-task"] (:task-names result)))
      (is (nil?          (:alias-name result)))))

  (testing "multiple tasks"
    (let [result (parse-args ["src" "a" "b" "c"])]
      (is (= ["a" "b" "c"] (:task-names result)))))

  (testing "--as flag sets alias-name"
    (let [result (parse-args ["src" "ref-report" "--as" "visibility"])]
      (is (= "ref-report"  (first (:task-names result))))
      (is (= "visibility"  (:alias-name result)))))

  (testing "--as with multiple tasks is an error"
    (is (thrown? Exception
          (parse-args ["src" "a" "b" "--as" "x"]))))

  (testing "missing source-name throws"
    (is (thrown? Exception (parse-args []))))

  (testing "missing task-name throws"
    (is (thrown? Exception (parse-args ["src"])))))

;;; Integration tests

(deftest add-installs-task-with-symbol-key-test
  ;; Regression: bb-edn-splice-tasks was writing keyword keys (:task-name)
  ;; instead of symbol keys (task-name). babashka silently ignored keyword-keyed tasks.
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/simple-tasks)
      (h/add-project-source! root :test-lib {:local/path (str lib)})

      (bbum.cmd.add/run ["test-lib" "hello"])

      (testing "bb.edn task key is a symbol"
        (let [bb-edn (config/read-bb-edn root)
              task-keys (keys (:tasks bb-edn))]
          (is (seq task-keys) "at least one task present")
          (is (every? symbol? task-keys)
              "all task keys must be symbols for babashka to recognise them")
          (is (contains? (:tasks bb-edn) 'hello))))

      (testing ".bbum/lib contains the installed file"
        (is (fs/exists? (str root "/.bbum/lib/test_org/test_lib/hello.clj"))))

      (testing ".bbum/lib is added to bb.edn :paths"
        (let [bb-edn (config/read-bb-edn root)]
          (is (contains? (set (:paths bb-edn)) ".bbum/lib"))))

      (testing ".bbum.edn records task as :explicit"
        (let [manifest (config/read-project-manifest root)]
          (is (= :explicit (get-in manifest [:tasks :hello :install]))))))))

(deftest add-with-alias-test
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/simple-tasks)
      (h/add-project-source! root :test-lib {:local/path (str lib)})

      (bbum.cmd.add/run ["test-lib" "hello" "--as" "greet"])

      (testing "bb.edn uses the alias as a symbol key"
        (let [bb-edn (config/read-bb-edn root)]
          (is (contains? (:tasks bb-edn) 'greet))
          (is (not (contains? (:tasks bb-edn) 'hello)))))

      (testing ".bbum.edn records :lib-task on the aliased entry"
        (let [manifest (config/read-project-manifest root)]
          (is (= :hello (get-in manifest [:tasks :greet :lib-task]))))))))

(deftest add-preflight-task-conflict-test
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/simple-tasks)
      (h/add-project-source! root :test-lib {:local/path (str lib)})

      ;; Pre-populate bb.edn with a conflicting task
      (let [bb-edn (config/read-bb-edn root)]
        (config/write-bb-edn root (assoc-in bb-edn [:tasks 'hello] {:doc "manual"})))

      (testing "throws before writing any files"
        (is (thrown? Exception
              (bbum.cmd.add/run ["test-lib" "hello"])))
        (is (not (fs/exists? (str root "/.bbum/lib/test_org/test_lib/hello.clj")))
            "file must not have been written on conflict")))))

(deftest add-preflight-file-conflict-test
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/simple-tasks)
      (h/add-project-source! root :test-lib {:local/path (str lib)})

      ;; Pre-create the destination file
      (let [dest (str root "/.bbum/lib/test_org/test_lib/hello.clj")]
        (fs/create-dirs (fs/parent dest))
        (spit dest "existing content"))

      (testing "throws before modifying bb.edn"
        (is (thrown? Exception
              (bbum.cmd.add/run ["test-lib" "hello"])))
        (let [bb-edn (config/read-bb-edn root)]
          (is (empty? (:tasks bb-edn))
              "bb.edn tasks must be untouched on file conflict"))))))

(deftest add-installs-dep-tasks-test
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/dep-task-defs)
      (h/add-project-source! root :test-lib {:local/path (str lib)})

      (bbum.cmd.add/run ["test-lib" "main"])

      (testing "explicit task recorded"
        (let [manifest (config/read-project-manifest root)]
          (is (= :explicit (get-in manifest [:tasks :main :install])))))

      (testing "dep task recorded as implicit"
        (let [manifest (config/read-project-manifest root)]
          (is (= :implicit  (get-in manifest [:tasks :helper :install])))
          (is (= [:main]    (get-in manifest [:tasks :helper :required-by])))))

      (testing "both task keys written as symbols in bb.edn"
        (let [bb-edn (config/read-bb-edn root)]
          (is (contains? (:tasks bb-edn) 'main))
          (is (contains? (:tasks bb-edn) 'helper)))))))
