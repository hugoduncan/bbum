💡 Single-seam aliasing: contain name translation in one helper, invisible to commands that don't need it.

When adding rename/alias support to a system where entities have both a
"stored name" and an "external name", introduce one lookup helper:

  (defn lib-task-kw [installed-kw task-rec]
    (get task-rec :lib-task installed-kw))

All code that maps installed-name → lib-name routes through it.
Commands that don't need the mapping are completely untouched.

In bbum: remove, list, source needed zero changes. Only add, update, status
touched the seam — and only at the exact points where the translation matters.

The pattern generalises: one extra field on the record + one fallback helper
= alias support with minimal blast radius.
