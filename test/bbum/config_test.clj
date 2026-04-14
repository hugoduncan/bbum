(ns bbum.config-test
  (:require [clojure.test   :refer [deftest testing is]]
            [bbum.config    :as config]
            [rewrite-clj.zip :as z]))

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

;;; z-splice-tasks

(deftest z-splice-tasks-adds-task-to-existing-tasks-map-test
  (testing "adds a new task; preserves other keys and outer formatting"
    (let [input  "{:paths [\"src\"], :tasks {}}"
          result (-> input z/of-string
                     (config/z-splice-tasks {'lint {:doc "Lint" :task '(lint/run)}})
                     z/root-string)]
      (is (= {:doc "Lint" :task '(lint/run)}
             (-> result z/of-string (z/get :tasks) (z/get 'lint) z/sexpr)))
      (is (clojure.string/includes? result ":paths [\"src\"]")
          "outer :paths key is untouched")))

  (testing "keyword keys are coerced to symbols"
    (let [result (-> "{:tasks {}}" z/of-string
                     (config/z-splice-tasks {:my-task {:doc "test"}})
                     z/root-string
                     z/of-string)]
      (is (z/get (z/get result :tasks) 'my-task)
          "installed key should be a symbol")))

  (testing "creates :tasks {} when absent"
    (let [result (-> "{:paths [\"src\"]}" z/of-string
                     (config/z-splice-tasks {'hello {:doc "hi"}})
                     z/root-string
                     z/of-string)]
      (is (z/get (z/get result :tasks) 'hello))))

  (testing "splices multiple tasks in one call"
    (let [result (-> "{:tasks {}}" z/of-string
                     (config/z-splice-tasks {'a {:doc "A"} 'b {:doc "B"}})
                     z/root-string
                     z/of-string
                     (z/get :tasks)
                     z/sexpr)]
      (is (= #{'a 'b} (set (keys result))))))

  (testing "existing content outside :tasks is preserved literally"
    ;; A comment before :paths must survive the splice
    (let [input  ";; my project\n{:paths [\"src\"] :tasks {}}"
          result (-> input z/of-string
                     (config/z-splice-tasks {'lint {:doc "Lint"}})
                     z/root-string)]
      (is (clojure.string/starts-with? result ";; my project"))))

  (testing "new task key is placed on its own indented line"
    ;; Key must appear after a newline with the same leading whitespace as siblings.
    ;; z/append-child inserts a trailing space before each subsequent child, so the
    ;; key line looks like '\n  added ' in the raw string.
    (let [tasks-map "{test\n  {:doc \"T\"}}"
          input     (str "{:tasks " tasks-map "}")
          result    (-> input z/of-string
                        (config/z-splice-tasks {'added {:doc "A"}})
                        z/root-string)]
      (is (re-find #"\n  added" result)
          "new key must appear after a newline with detected indentation")))

  (testing "new task value is on its own indented line after the key"
    (let [tasks-map "{test\n  {:doc \"T\"}}"
          input     (str "{:tasks " tasks-map "}")
          result    (-> input z/of-string
                        (config/z-splice-tasks {'added {:doc "A"}})
                        z/root-string)]
      (is (re-find #"\n  added\s+\n\s*\{" result)
          "value must follow the key on its own indented line")))

  (testing "complex task value has aligned map keys"
    ;; pprint + re-indent must align :requires and :task with :doc.
    ;; Use a long doc string to ensure pprint wraps to multiple lines.
    (let [task-def  {:doc     "Report public fns used only by tests and fns that could be private"
                    :requires '([some.ns :as s])
                    :task    '(s/run)}
          tasks-map "{test\n  {:doc \"T\"}}"
          lines     (-> (str "{:tasks " tasks-map "}") z/of-string
                        (config/z-splice-tasks {'my-task task-def})
                        z/root-string
                        clojure.string/split-lines)
          ;; Lines inside the new task value start with whitespace then ':'
          key-cols  (->> lines
                         (filter #(re-find #"^\s{3,}:" %))
                         (map #(count (re-find #"^\s+" %))))]
      (is (seq key-cols) "at least one indented key line should be present")
      (is (apply = key-cols)
          "all map keys in the value should be at the same column")))

  (testing "replacing an existing task does not insert extra whitespace"
    (let [input  "{:tasks {lint {:doc \"old\"}}}"
          result (-> input z/of-string
                     (config/z-splice-tasks {'lint {:doc "new"}})
                     z/root-string)]
      (is (= {:doc "new"} (-> result z/of-string (z/get :tasks) (z/get 'lint) z/sexpr)))
      ;; No extra newline injected before an existing key
      (is (not (re-find #"\nlint" result))))))

;;; z-remove-tasks

(deftest z-remove-tasks-removes-task-from-tasks-map-test
  (testing "removes an existing task"
    (let [input  "{:tasks {lint {:doc \"Lint\"} fmt {:doc \"Fmt\"}}}"
          result (-> input z/of-string
                     (config/z-remove-tasks ['lint])
                     z/root-string
                     z/of-string
                     (z/get :tasks)
                     z/sexpr)]
      (is (not (contains? result 'lint)))
      (is (contains? result 'fmt))))

  (testing "removes multiple tasks"
    (let [result (-> "{:tasks {a {} b {} c {}}}" z/of-string
                     (config/z-remove-tasks [:a :b])
                     z/root-string
                     z/of-string
                     (z/get :tasks)
                     z/sexpr)]
      (is (= #{'c} (set (keys result))))))

  (testing "no-op when :tasks is absent"
    (let [input "{:paths [\"src\"]}"
          result (-> input z/of-string
                     (config/z-remove-tasks ['missing])
                     z/root-string)]
      (is (= input result))))

  (testing "keyword task-keys are coerced to symbols"
    (let [result (-> "{:tasks {lint {}}}" z/of-string
                     (config/z-remove-tasks [:lint])
                     z/root-string
                     z/of-string
                     (z/get :tasks)
                     z/sexpr)]
      (is (empty? result))))

  (testing "content outside :tasks is preserved literally"
    (let [input  ";; project\n{:paths [\"src\"] :tasks {lint {}}}"
          result (-> input z/of-string
                     (config/z-remove-tasks ['lint])
                     z/root-string)]
      (is (clojure.string/starts-with? result ";; project"))
      (is (clojure.string/includes? result ":paths [\"src\"]"))))

  (testing "surviving tasks retain their original token text"
    ;; z/edit + coerce would reformat remaining tasks; surgical removal must not.
    (let [input  "{:tasks {kept {:doc \"K\"\n              :task (kept/run)}\n         gone {}}}"
          result (-> input z/of-string
                     (config/z-remove-tasks ['gone])
                     z/root-string)]
      (is (clojure.string/includes? result ":doc \"K\"\n              :task (kept/run)")
          "multi-line formatting of surviving task is unchanged"))))

;;; z-ensure-path

(deftest z-ensure-path-test
  (testing "appends path when :paths exists and is missing the entry"
    (let [result (-> "{:paths [\"src\"]}" z/of-string
                     (config/z-ensure-path ".bbum/lib")
                     z/root-string
                     z/of-string
                     (z/get :paths)
                     z/sexpr)]
      (is (= ["src" ".bbum/lib"] result))))

  (testing "idempotent when path is already present"
    (let [input  "{:paths [\"src\" \".bbum/lib\"]}"
          result (-> input z/of-string
                     (config/z-ensure-path ".bbum/lib")
                     z/root-string)]
      (is (= input result))))

  (testing "creates :paths when absent"
    (let [result (-> "{}" z/of-string
                     (config/z-ensure-path ".bbum/lib")
                     z/root-string
                     z/of-string
                     (z/get :paths)
                     z/sexpr)]
      (is (= [".bbum/lib"] result))))

  (testing "existing content outside :paths is preserved literally"
    (let [input  ";; project\n{:tasks {}}"
          result (-> input z/of-string
                     (config/z-ensure-path ".bbum/lib")
                     z/root-string)]
      (is (clojure.string/starts-with? result ";; project")))))
