❌ bbum remove with --as alias silently skipped file deletion.

task-files used the installed name (alias) as the lib manifest key.
Since the manifest only knows the lib name, get-in returned [] and
no files were removed.

Fix: use config/lib-task-kw to translate installed-kw → lib-kw before
looking up :files in the manifest.

Pattern: any code that maps installed task keys to lib manifest entries
must go through lib-task-kw, not use the installed key directly.
Already applied in: add (preflight file check), update (file copy).
