✅ bbum fully implemented across 9 steps, one commit per step.

Key patterns that worked well:
- `config.clj` as single owner of all EDN I/O — clean separation, easy to test
- `with-source-dir` macro-style fn keeps git temp dirs alive through file copy phase
- Preflight-before-write discipline: all conflict checks run before any filesystem modification
- Group update tasks by source key → one clone per source → efficient batching
- `babashka.process/sh` captures stdout; `proc/shell` inherits I/O (use sh for git ls-remote)
- `babashka.fs/with-temp-dir` is a macro, works perfectly for git clones
