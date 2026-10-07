# Handwritten Content — copy onto 4 pages by hand

## Page 1 — Overview & approach

(Page 1 / 4)

Task tracker patch exercise — SuperrDev

Application overview:
- Full-stack task tracker.
- Frontend: React 18 + Vite. Single page with search bar, status dropdown, task table, and pagination controls (prev/page of/next).
- Backend: Spring Boot 3.2 on Java 17. One controller `TaskController` exposing `GET /api/tasks?q=&status=&page=&pageSize=`. One repository `TaskRepository` with a native SQL search over H2.
- Database: in-memory H2 initialised from `schema.sql` + 46 seed rows in `data.sql`. `tasks` table has id/title/description/status/priority/archived/assignee/created_at. Archived tasks exist in the seed and are supposed to be hidden from search.
- Communication: Vite dev server proxies `/api/*` to `http://localhost:8080`. Frontend calls the endpoint via a small `fetchTasks()` helper in `api.js`; a custom `useTasks(query, status, page, pageSize)` hook drives it from `App.jsx`.

Approach to debugging:
1. Read every file in the repo first (README, backend source, resources, frontend source, db/ SQL artifacts, pom.xml, package.json, vite config, .gitignore).
2. Identify layers: entry points, controllers, services/repos, entities, components, custom hooks, API client.
3. Reason about each layer statically:
   - What are the preconditions, postconditions, invariants for each function/query?
   - Is every possible branch handled (error paths, empty, null, invalid input)?
   - Is there non-obvious state shared across calls?
4. Then run the app and exercise the concrete URLs I was suspicious of with reproducible inputs taken directly from the seed data. Recording actual behaviour against expected behaviour is what turned "code smell" into confirmed bugs.
5. Prioritise: correctness and data integrity first (archived rows leaking, status filter ignored), then reliability (500 on invalid input, stuck loading state, race conditions), then performance/UX (Thread.sleep, missing debounce, stale page on filter change). Cosmetic/style issues get dropped on the floor — patch exercise, not a rewrite.
6. For each selected fix: write smallest possible change, add a focused test, rerun full test suite, re-hit the original reproduction URL, then document.

---

## Page 2 — Highest-value bug #1: SQL operator precedence

(Page 2 / 4)

Bug title: Archived tasks leak through search; status filter is bypassed on title matches.

Where:
- File: `backend/src/main/java/com/internal/tasktracker/TaskRepository.java` — the `@Query` string on `searchTasks()`.
- Same bug replicated in `db/queries/search_tasks.sql` and both WHERE clauses of `db/oracle/task_search_package.sql`.
- Severity: P0 — data-integrity/correctness bug.

How I found it:
1. Static reading: the WHERE clause is written as `archived = FALSE AND LOWER(title) LIKE :term OR LOWER(description) LIKE :term AND (:status IS NULL OR status = :status)`.
2. AND binds tighter than OR in SQL. So I mentally rewrote it with parentheses added by the parser:
   `(archived = FALSE AND LOWER(title) LIKE :term) OR (LOWER(description) LIKE :term AND (:status IS NULL OR status = :status))`
3. From this I predicted two failures:
   - A match on description alone will bypass `archived=FALSE` → archived rows leak.
   - A match on title alone will bypass the `status` filter → status filter works only for description-matching rows.
4. Picked concrete rows from seed data that exercise each branch:
   - Archived description-only match: task "Legacy API cleanup" (id 21), `archived=TRUE`, status DONE, description contains the word "deprecated". Hit URL `/api/tasks?q=deprecated` → returned total = 1, archived = true in the body. Confirmed.
   - Title match + status mismatch: task "Fix login redirect bug", status OPEN. Hit URL `/api/tasks?q=login&status=DONE` → returned total = 1, status OPEN in the body. Confirmed.

Root cause:
- Missing parentheses around the `title LIKE OR description LIKE` disjunction. The two LIKE predicates are meant to be a single search condition that is then ANDed with both filters. Instead, operator precedence turned one intended conjunction into two separate disjunctive branches, each missing one filter.

Fix:
- Rewrote the WHERE clause to:
  `archived = FALSE AND (LOWER(title) LIKE :term OR LOWER(description) LIKE :term) AND (:status IS NULL OR status = :status) ORDER BY created_at DESC`
- The two LIKE predicates are now wrapped first, then conjoined with `archived = FALSE` and the status filter.
- Applied the same structural fix to the two SQL reference files. For the Oracle package I fixed this in BOTH the `SELECT COUNT(*)` total-count query and the inner paginated `SELECT` — a common secondary bug is to fix one half and leave the other reporting a wrong `total` with correct `items` (or vice versa).

Why this fix over alternatives:
- Smallest possible diff: literally adding two pairs of parentheses per WHERE clause.
- Does not change the query plan materially (LIKE with leading wildcard is a table scan either way) and does not require switching to JPQL, `CriteriaQuery`, `Specification`, or a view — all of which would be larger refactors without any compensating benefit for this patch.

Verification:
- Added 3 `@SpringBootTest` repository tests (`TaskRepositoryIntegrationTest`):
  1. description-only match for an archived row → 0 results.
  2. title match with mismatching status filter → 0 results.
  3. no filter → every returned row has `archived = false`.
- Added 2 `MockMvc` controller tests asserting the same reproduction URLs return `total = 0`.
- Re-hit both original reproduction URLs after the fix; both now return 0 rows.
- Unfiltered query still returns 44 non-archived rows (2 archived rows removed), confirming the fix narrowed results only where it should.

---

## Page 3 — Highest-value bug #2: Latency injection + invalid status returns 500

(Page 3 / 4)

Bug title (part A): Every API call sleeps artificially for up to 1 second.

Where:
- `backend/src/main/java/com/internal/tasktracker/TaskController.java`, `searchTasks()` method.
- Lines that computed `complexityScore = max(0, 10 - query.length())`, multiplied by 100 ms, then `Thread.sleep(queryWeight)`.
- Severity: P1 — terrible UX, wasteful of server request threads, and completely spurious.

How I found it:
- Read the controller from top to bottom. The `Thread.sleep` on the hot path stood out immediately.
- Backed it up with a measurement: `Measure-Command { /api/tasks?q= }` on localhost consistently reported ~1.3 s elapsed. Even accounting for Spring bootstrap + Hibernate, an empty primary-key-range SELECT should be single-digit milliseconds. That 1 s delta exactly matched `max(0, 10 - 0) * 100 ms = 1 000 ms` — smoking gun.

Root cause:
- A fake "complexity estimation" block that did not actually throttle, protect the database, log any metric beyond itself, or sit behind a feature flag. It looks like deliberately seeded bad code.

Fix:
- Deleted the whole block (score calculation, `Thread.sleep`, and the `System.out.println` that went with it).

Why:
- No legitimate purpose. A real rate-limiter or query cost estimator would use token buckets, circuit breakers, DB-level `statement_timeout`, or at minimum a config flag and proper SLF4J metrics — none of which are present here.

Bug title (part B): Invalid `status` parameter returns HTTP 500.

Where:
- Same controller method, line `TaskStatus.valueOf(status.toUpperCase())`.
- Severity: P1 — incorrect HTTP semantics, pollutes error metrics, alerts on-call for user mistakes.

How I found it:
- Curled `/api/tasks?status=INVALID` → raw 500 with no body.
- `Enum.valueOf()` throws unchecked `IllegalArgumentException` on unknown names, and Spring MVC converts uncaught unchecked exceptions to 500 by default.

Root cause:
- No try/catch around `TaskStatus.valueOf(...)`. Invalid user input propagated as a server-side exception.

Fix:
- Wrapped the `valueOf` call in `try { ... } catch (IllegalArgumentException ex) { return 400 }`.
- Returned a small JSON body: `{ "error": "Invalid status", "message": "Status must be one of: OPEN, IN_PROGRESS, DONE" }`.
- Also hardened pagination inputs: clamped `page` to at least 1, clamped `pageSize` to [1, 100] (a hard upper bound prevents a client from accidentally asking for a million rows and OOMing the JVM with an in-memory `subList`). Clamping rather than 400 keeps existing well-behaved clients untouched.

Verification:
- Latency: after removing sleep, empty-query round-trip dropped from ~1.3 s to ~80 ms locally.
- Invalid status: new `MockMvc` test `searchTasks_invalidStatus_returns400` asserts 400 + the `error` field in the body.
- Valid status: `searchTasks_validStatus_returns200` asserts OPEN still returns a 200 with an items array.
- Full `.\mvnw.cmd test` run: Tests run: 7, Failures: 0, Errors: 0, Skipped: 0.

---

## Page 4 — Testing, other improvements, key lessons

(Page 4 / 4)

Frontend fixes (P1/P2):

1. `useTasks` stuck loading on error, never cleared error, and had no stale-response guard.
   - Where: `frontend/src/hooks/useTasks.js`.
   - Fix: set `loading=false` in `.finally` (guarded by request id), set `error=null` at effect start and again in `.then`, use an `AbortController` (abort on cleanup) + a `requestIdRef` monotonic counter to drop state writes from superseded requests. Also accept and forward the `AbortSignal` through `api.js`.
   - Why: standard React 18 pattern — cancelling AND ignoring writes covers both the network cancellation case and the race-where-cancellation-is-no-op case.

2. Search fired on every keystroke; changing filters did not reset page.
   - Where: `frontend/src/App.jsx`, plus new tiny `useDebounce` hook.
   - Fix: debounced the search query (300 ms) before it reaches `useTasks`; query/status change handlers now also call `setPage(1)`; DRYed `PAGE_SIZE = 10` constant.

Testing performed:
- Backend unit + integration: 7 tests added (3 repository, 4 controller). All green. Command: `backend\mvnw.cmd test`.
- Backend compile: `backend\mvnw.cmd compile` — BUILD SUCCESS.
- Frontend build: `frontend\npm run build` — Vite 5, 37 modules transformed, no errors.
- Manual API regression checks (all pass, as tabulated in NOTES.md):
  - `/api/tasks?q=deprecated` → total 0.
  - `/api/tasks?q=login&status=DONE` → total 0.
  - `/api/tasks?q=` latency < 200 ms.
  - `/api/tasks?status=INVALID` → 400.
  - `/api/tasks?status=OPEN` → 200, items present.
- What I didn't run: no browser E2E harness existed (no Cypress/Playwright), so no visual smoke test; frontend correctness is validated by production build + code review of hook transitions. Oracle package cannot run locally against H2; verified symmetrically by inspection.

Key lessons:
1. Parenthesise every mixed AND/OR in SQL, even when you "know" precedence. This is one of the most frequent silent-correctness bugs I see, and every ORM/layer that generates SQL is vulnerable if you hand-write a WHERE clause. Pair it with tests that exercise EACH branch of the OR individually with the other filters set to the opposite value so you cannot pass by coincidence.
2. Never trust latency numbers you didn't measure. I would have missed the `Thread.sleep` if I'd only looked at the query plan — the slowness was purely synthetic in the controller and invisible to the DB layer.
3. For input validation on enums: catch `IllegalArgumentException` explicitly at the call site and translate to 400 before Spring turns it into 500. A generic `@ControllerAdvice` handler would scale this to more endpoints, but a local try/catch at the single call site is the minimal patch.
4. In React async hooks: always combine (a) cleanup that cancels in-flight work (AbortController) with (b) a write-guard so responses you cannot cancel still don't overwrite newer state. Either alone has known edge cases — both together are bulletproof for a patch like this.
5. The best diffs are the smallest. Six of the seven fixes I made are under ~10 changed lines in a single file each. A patch exercise rewards focused, proven fixes, not enthusiasm. When in doubt: fix less, explain more.
