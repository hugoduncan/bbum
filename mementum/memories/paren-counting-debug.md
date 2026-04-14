❌ Paren counting mistake when editing Clojure manually.

When a string like `")"` ends a nested form, it looks like a close paren but isn't.
Counting strategy that works reliably:
  `bb -e "(let [src (slurp \"file.clj\")] (reduce ... {:depth 0 :in-str false} src))"`
  with proper string-aware reduce. depth should be 0 at EOF.

Quick check: `python3 -c "lines=open('f.clj').readlines(); print(repr(lines[-1]))"`
to see exact chars on last line.

When depth is N < 0: file has N too many `)`.
Use sed: `sed -i '' 's/target/replacement/' file` to fix single chars.
