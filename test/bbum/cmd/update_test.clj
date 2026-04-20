(ns bbum.cmd.update-test
  "Integration tests for bbum update."
  (:require [babashka.fs       :as fs]
            [bbum.cmd.add      :as add]
            [bbum.cmd.update   :as update]
            [bbum.config       :as config]
            [bbum.test-helpers :refer [add-project-source! make-lib! make-project!
                                       with-clean-env]]
            [clojure.test      :refer [deftest is testing]]))

;;; Helpers

(defn- bb-task-order
  "Return the task symbol names from bb.edn in document order."
  [root]
  (let [content (slurp (str root "/bb.edn"))
        zloc    (rewrite-clj.zip/of-string content)
        tasks   (rewrite-clj.zip/get zloc :tasks)]
    (when tasks
      (loop [loc    (rewrite-clj.zip/down tasks)
             result []
             is-key true]
        (cond
          (nil? loc)
          result

          (or (rewrite-clj.node/whitespace? (rewrite-clj.zip/node loc))
              (rewrite-clj.node/comment? (rewrite-clj.zip/node loc)))
          (recur (rewrite-clj.zip/right loc) result is-key)

          is-key
          (recur (rewrite-clj.zip/right loc)
                 (conj result (name (rewrite-clj.zip/sexpr loc)))
                 false)

          :else
          (recur (rewrite-clj.zip/right loc) result true))))))

(defn- make-versioned-lib!
  "Build a lib dir with :hello and :world tasks whose :doc encodes the version."
  [lib-dir version]
  (make-lib! lib-dir "test-lib"
             {:hello {:doc   (str "Hello v" version)
                      :files [(str "src/test_org/test_lib/hello_" version ".clj")]
                      :task  {:doc (str "Hello v" version)
                              :requires '([test-org.test-lib.hello :as hello])
                              :task '(hello/run)}}
              :world {:doc   (str "World v" version)
                      :files [(str "src/test_org/test_lib/world_" version ".clj")]
                      :task  {:doc (str "World v" version)
                              :requires '([test-org.test-lib.world :as world])
                              :task '(world/run)}}}))

;;; Tests

(deftest update-preserves-task-order
  (testing "update rewrites updated task defs in-place, preserving bb.edn order"
    (with-clean-env [root]
      (fs/with-temp-dir [lib-dir {:prefix "bbum-lib-"}]
        (make-project! root)
        (make-versioned-lib! lib-dir "1")
        (add-project-source! root :my-lib {:local/path (str lib-dir)})

        ;; add inserts alphabetically, so [hello world] is the expected order
        (add/run ["my-lib" "world"])
        (add/run ["my-lib" "hello"])

        (let [order-before (bb-task-order root)]
          (is (= ["hello" "world"] order-before)
              "add inserts alphabetically: hello before world"))

        ;; Bump the lib to v2 (local source always re-copies)
        (make-versioned-lib! lib-dir "2")
        (update/run [])

        (let [order-after (bb-task-order root)]
          (is (= ["hello" "world"] order-after)
              "update must preserve existing task order"))

        ;; Confirm task defs were actually updated
        (let [bb-edn (config/read-bb-edn root)]
          (is (= "World v2" (:doc (get (:tasks bb-edn) 'world)))
              "world task doc should reflect v2")
          (is (= "Hello v2" (:doc (get (:tasks bb-edn) 'hello)))
              "hello task doc should reflect v2"))))))

(deftest update-single-task-preserves-order
  (testing "updating a single task leaves others untouched and preserves order"
    (with-clean-env [root]
      (fs/with-temp-dir [lib-dir {:prefix "bbum-lib-"}]
        (make-project! root)
        (make-versioned-lib! lib-dir "1")
        (add-project-source! root :my-lib {:local/path (str lib-dir)})

        (add/run ["my-lib" "world"])
        (add/run ["my-lib" "hello"])

        (make-versioned-lib! lib-dir "2")
        (update/run ["hello"])

        (is (= ["hello" "world"] (bb-task-order root))
            "order must be preserved when updating a single task")

        (let [bb-edn (config/read-bb-edn root)]
          (is (= "Hello v2" (:doc (get (:tasks bb-edn) 'hello)))
              "updated task should be v2")
          (is (= "World v1" (:doc (get (:tasks bb-edn) 'world)))
              "non-updated task should remain v1"))))))
