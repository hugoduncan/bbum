(ns bbum.cmd.source-test
  (:require [clojure.test      :refer [deftest testing is]]
            [babashka.fs       :as fs]
            [bbum.config       :as config]
            [bbum.cmd.source   :as cmd-source]
            [bbum.test-helpers :as h]))

;;; Private fn access

(def ^:private parse-coord-args @#'bbum.cmd.source/parse-coord-args)
(def ^:private cmd-add          @#'bbum.cmd.source/cmd-add)

;;; parse-coord-args unit tests

(deftest parse-coord-args-test
  (testing "local path coord"
    (is (= {:local/path "../my-lib"}
           (parse-coord-args ["local/path=../my-lib"]))))

  (testing "git branch coord"
    (is (= {:git/url "https://github.com/org/lib" :git/branch "master"}
           (parse-coord-args ["git/url=https://github.com/org/lib"
                               "git/branch=master"]))))

  (testing "git tag coord"
    (is (= {:git/url "https://github.com/org/lib" :git/tag "v1.0.0"}
           (parse-coord-args ["git/url=https://github.com/org/lib"
                               "git/tag=v1.0.0"]))))

  (testing "git sha coord"
    (is (= {:git/url "https://github.com/org/lib" :git/sha "abc123"}
           (parse-coord-args ["git/url=https://github.com/org/lib"
                               "git/sha=abc123"]))))

  (testing "empty args throws"
    (is (thrown? Exception (parse-coord-args []))))

  (testing "malformed arg without = throws"
    (is (thrown? Exception (parse-coord-args ["no-equals-sign"])))))

;;; source add — structural validation (no network)

(deftest source-add-local-no-verify-test
  (h/with-clean-env [root]
    (h/make-project! root)
    (testing "adds local source with --no-verify"
      (cmd-add ["--no-verify" "my-lib" "local/path=../some-lib"])
      (let [manifest (config/read-project-manifest root)]
        (is (= {:local/path "../some-lib"}
               (get-in manifest [:sources :my-lib])))))))

(deftest source-add-global-no-verify-test
  (h/with-clean-env [root]
    (h/make-project! root)
    (testing "adds global source with --no-verify and --global"
      (cmd-add ["--global" "--no-verify" "my-lib" "local/path=../some-lib"])
      (let [global (config/read-global-config)]
        (is (= {:local/path "../some-lib"}
               (get-in global [:sources :my-lib]))))
      (testing "project manifest is untouched"
        (let [manifest (config/read-project-manifest root)]
          (is (nil? (get-in manifest [:sources :my-lib]))))))))

(deftest source-add-flags-any-order-test
  (h/with-clean-env [root]
    (h/make-project! root)
    (testing "--no-verify and --global can appear in any order"
      (cmd-add ["my-lib" "--no-verify" "--global" "local/path=../some-lib"])
      (let [global (config/read-global-config)]
        (is (= {:local/path "../some-lib"}
               (get-in global [:sources :my-lib])))))))

(deftest source-add-missing-name-throws-test
  (h/with-clean-env [root]
    (h/make-project! root)
    (is (thrown? Exception (cmd-add ["--no-verify"])))))

;;; source add — reachability validation against a real local path

(deftest source-add-local-valid-path-test
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/simple-tasks)
      (testing "adds local source when path exists (validation passes)"
        (cmd-add ["my-lib" (str "local/path=" lib)])
        (let [manifest (config/read-project-manifest root)]
          (is (= {:local/path (str lib)}
                 (get-in manifest [:sources :my-lib]))))))))

(deftest source-add-local-missing-path-throws-test
  (h/with-clean-env [root]
    (h/make-project! root)
    (testing "throws when local path does not exist"
      (is (thrown? Exception
            (cmd-add ["my-lib" "local/path=/nonexistent/path/that/cannot/exist"]))))))

(deftest source-add-no-verify-skips-path-check-test
  (h/with-clean-env [root]
    (h/make-project! root)
    (testing "--no-verify skips the path-existence check"
      (cmd-add ["--no-verify" "my-lib" "local/path=/nonexistent/path/that/cannot/exist"])
      (let [manifest (config/read-project-manifest root)]
        (is (= {:local/path "/nonexistent/path/that/cannot/exist"}
               (get-in manifest [:sources :my-lib])))))))

;;; source remove

(deftest source-remove-test
  (h/with-clean-env [root]
    (h/make-project! root)
    (cmd-add ["--no-verify" "my-lib" "local/path=../some-lib"])
    (testing "source is present before remove"
      (is (some? (get-in (config/read-project-manifest root) [:sources :my-lib]))))
    (cmd-source/run ["remove" "my-lib"])
    (testing "source is gone after remove"
      (is (nil? (get-in (config/read-project-manifest root) [:sources :my-lib]))))))

(deftest source-remove-with-installed-tasks-throws-test
  (h/with-clean-env [root]
    (fs/with-temp-dir [lib {:prefix "bbum-lib-"}]
      (h/make-project! root)
      (h/make-lib! (str lib) 'test-org/test-lib h/simple-tasks)
      (h/add-project-source! root :test-lib {:local/path (str lib)})
      ;; Simulate a task installed from the source
      (let [manifest (config/read-project-manifest root)]
        (config/write-project-manifest root
          (assoc-in manifest [:tasks :hello] {:source :test-lib :install :explicit})))
      (testing "throws when tasks are still installed from the source"
        (is (thrown? Exception
              (cmd-source/run ["remove" "test-lib"])))))))

;;; source list

(deftest source-list-no-sources-test
  (h/with-clean-env [root]
    (h/make-project! root)
    (testing "list prints no-sources message without throwing"
      (is (nil? (cmd-source/run ["list"]))))))

(deftest source-list-with-sources-test
  (h/with-clean-env [root]
    (h/make-project! root)
    (cmd-add ["--no-verify" "my-lib" "local/path=../some-lib"])
    (testing "list prints table without throwing"
      (is (nil? (cmd-source/run ["list"]))))))

;;; unknown subcommand

(deftest source-unknown-subcommand-throws-test
  (h/with-clean-env [root]
    (h/make-project! root)
    (is (thrown? Exception (cmd-source/run ["frobnicate"])))))
