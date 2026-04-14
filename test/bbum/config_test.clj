(ns bbum.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [bbum.config  :as config]))

;;; bb-edn-splice-tasks

(deftest bb-edn-splice-tasks-produces-symbol-keys-test
  ;; Regression: babashka task runner requires symbol keys.
  ;; Keywords silently produced "No tasks found" — task was invisible to bb.
  (testing "keyword keys are coerced to symbols"
    (let [result (config/bb-edn-splice-tasks {} {:my-task {:doc "test"}})]
      (is (= #{'my-task} (set (keys (:tasks result))))
          "task key should be a symbol, not a keyword")))

  (testing "symbol keys pass through unchanged"
    (let [result (config/bb-edn-splice-tasks {} {'my-task {:doc "test"}})]
      (is (= #{'my-task} (set (keys (:tasks result)))))))

  (testing "mixed keyword and symbol inputs both produce symbols"
    (let [result (config/bb-edn-splice-tasks {} {:kw-task {:doc "a"}
                                                  'sym-task {:doc "b"}})]
      (is (every? symbol? (keys (:tasks result))))))

  (testing "merges onto existing tasks preserving existing symbol keys"
    (let [base   {:tasks {'existing {:doc "existing"}}}
          result (config/bb-edn-splice-tasks base {:new-task {:doc "new"}})]
      (is (= #{'existing 'new-task} (set (keys (:tasks result)))))))

  (testing "works when :tasks key is absent from bb-edn"
    (let [result (config/bb-edn-splice-tasks {:paths ["src"]} {:hello {:doc "hi"}})]
      (is (= 'hello (first (keys (:tasks result))))))))

;;; bb-edn-remove-tasks

(deftest bb-edn-remove-tasks-handles-symbol-keys-test
  ;; Regression: bb.edn stores symbol keys; dissoc by keyword was a silent no-op.
  (let [bb-edn {:tasks {'lint {:doc "lint"} 'fmt {:doc "fmt"}}}]

    (testing "removes task when given a keyword (converts to symbol internally)"
      (let [result (config/bb-edn-remove-tasks bb-edn [:lint])]
        (is (not (contains? (:tasks result) 'lint)))
        (is (contains? (:tasks result) 'fmt))))

    (testing "removes task when given a symbol directly"
      (let [result (config/bb-edn-remove-tasks bb-edn ['lint])]
        (is (not (contains? (:tasks result) 'lint)))))

    (testing "removes multiple tasks"
      (let [result (config/bb-edn-remove-tasks bb-edn [:lint :fmt])]
        (is (empty? (:tasks result)))))

    (testing "no-op for unknown task (does not throw)"
      (let [result (config/bb-edn-remove-tasks bb-edn [:unknown])]
        (is (= (:tasks bb-edn) (:tasks result)))))))

;;; bb-edn-ensure-path

(deftest bb-edn-ensure-path-test
  (testing "adds missing path"
    (let [result (config/bb-edn-ensure-path {:paths ["src"]} ".bbum/lib")]
      (is (= ["src" ".bbum/lib"] (:paths result)))))

  (testing "idempotent when path is already present"
    (let [bb-edn {:paths ["src" ".bbum/lib"]}
          result (config/bb-edn-ensure-path bb-edn ".bbum/lib")]
      (is (= ["src" ".bbum/lib"] (:paths result)))))

  (testing "initialises :paths when absent"
    (let [result (config/bb-edn-ensure-path {} ".bbum/lib")]
      (is (= [".bbum/lib"] (:paths result))))))

;;; lib-task-kw

(deftest lib-task-kw-test
  (testing "returns :lib-task when the task was installed with --as alias"
    (is (= :ref-report
           (config/lib-task-kw :visibility {:lib-task :ref-report :install :explicit}))))

  (testing "falls back to installed-kw when no alias was set"
    (is (= :lint
           (config/lib-task-kw :lint {:install :explicit}))))

  (testing "nil task-rec falls back to installed-kw"
    (is (= :something
           (config/lib-task-kw :something nil)))))

;;; coord-type

(deftest coord-type-test
  (testing "local path coord"
    (is (= :local (config/coord-type {:local/path "../lib"}))))

  (testing "git sha coord"
    (is (= :git/sha (config/coord-type {:git/url "https://x" :git/sha "abc"}))))

  (testing "git branch coord"
    (is (= :git/branch (config/coord-type {:git/url "https://x" :git/branch "main"}))))

  (testing "git tag coord"
    (is (= :git/tag (config/coord-type {:git/url "https://x" :git/tag "v1.0.0"})))))

;;; effective-sources

(deftest effective-sources-test
  (let [global  {:sources {:shared  {:git/url "https://g.example.com"}
                            :g-only  {:git/url "https://global-only.example.com"}}}
        project {:sources {:shared  {:local/path "../local-lib"}
                            :p-only  {:local/path "../proj-only"}}}
        result  (config/effective-sources global project)]

    (testing "project source wins over global source with same name"
      (is (= :project (get-in result [:shared :origin])))
      (is (= :local (config/coord-type (get-in result [:shared :coord])))))

    (testing "global-only source is visible"
      (is (= :global (get-in result [:g-only :origin]))))

    (testing "project-only source is visible"
      (is (= :project (get-in result [:p-only :origin]))))

    (testing "global source shadowed by project source is marked shadowed?"
      ;; The shadowed? flag is set on the global entry before merge —
      ;; after merge the project entry is visible; shadowed? false on that entry.
      (is (false? (get-in result [:shared :shadowed?])))
      ;; Global-only source is not shadowed
      (is (false? (get-in result [:g-only :shadowed?]))))))
