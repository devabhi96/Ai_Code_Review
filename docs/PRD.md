# PRD: AI Code Review System (v2, scope-trimmed)

**Owner:** Abhi (3rd-year B.Tech CSE student; knows Java, Spring, some React, C++)
**Purpose:** Portfolio project for software development / backend internships.
**One line:** A Spring Boot service that reviews GitHub pull requests with an LLM, finds likely bugs and security issues, and posts correctly-anchored inline comments with suggested fixes. Quality is **measured**, not assumed.

---

## 0. HANDOFF SECTION (read first, update as you progress)

> **For any AI reading this file:** this PRD is the single source of truth. The user may be resuming after a break or switching AIs.
> 1. Read sections 1 to 11.
> 2. Look at the Progress Tracker and ask the user: "Which step are you on, what already works, and what is the blocker?" Ask to see their current folder structure/code/error.
> 3. Do not redo finished steps. Check the previous step's "Done when" line, then continue.
> 4. Work **one step at a time**, small pieces of code, brief explanations with simple analogies. The user writes casual English/Hinglish, prefers concise answers and a few options when a real choice exists.
> 5. Never expand scope silently. Anything under "Later" (section 3) needs the user's approval. Record any design change in section 12.

### Progress Tracker (user edits this)

**Current step:** `Step __`  **Last thing that worked:** `______`  **Blocker:** `______`

| # | Step | Phase | Status |
|---|---|---|---|
| 1 | Project setup | A. Engine | [ ] |
| 2 | Diff parser, file filter, numbered diff | A. Engine | [ ] |
| 3 | Secret scanner + **redaction gate** | A. Engine | [ ] |
| 4 | LLM client, prompt, validation, anchor checks | A. Engine | [ ] |
| 5 | Playground endpoint + eval set v0 + baseline | A. Engine | [ ] |
| | **CHECKPOINT 1: engine works on playground, eval numbers exist** | | |
| 6 | Webhook receiver + job queue | B. GitHub | [ ] |
| 7 | GitHub App auth + GitHub client | B. GitHub | [ ] |
| 8 | Publish review to PR (in 4 small checkpoints) | B. GitHub | [ ] |
| | **CHECKPOINT 2: bot reviews a real PR on GitHub** | | |
| 9 | Reliability: publication state machine, stale-SHA handling | C. Harden | [ ] |
| 10 | Quality: dedupe, ranking, chunking, repo config, batched verification | C. Harden | [ ] |
| 11 | Eval set grown to 40+ cases + regression run | C. Harden | [ ] |
| | **CHECKPOINT 3: resume-worthy project (you may stop here)** | | |
| 12 | Minimal dashboard backend (login + REST) | D. UI | [ ] |
| 13 | Minimal frontend (4 pages) | D. UI | [ ] |
| 14 | Deploy, README, demo video | E. Launch | [ ] |

---

## 1. What I am building

An **AI Code Review System**. When a developer opens or updates a pull request on GitHub, the system reads the code changes, finds **potential bugs and security issues**, and posts **inline comments with suggested fixes** on the PR, plus a short summary. A small web dashboard lists reviews and findings.

**Analogy:** a senior engineer who reads every PR within minutes and does the first pass ("line 42 can throw a NullPointerException", "this SQL is built by string concatenation"). Humans then review what matters. It never approves, blocks, or merges anything.

**What makes it different:** most AI reviewers are noisy. This one is built around **precision** (few, correct, well-anchored comments) and **proves it with an evaluation set**.

---

## 2. How it works

```
Developer opens/updates PR
        │
GitHub ──webhook──▶ Backend (Spring Boot)
                      1. verify signature, save job, reply 2xx immediately
                      2. worker claims job (one running job per PR)
                      3. fetch PR + changed files (pinned to one head SHA)
                      4. filter files, parse diff, number lines
                      5. SCAN FOR SECRETS and REDACT them (before any AI call)
                      6. LLM review (structured JSON) + validate every finding
                      7. rank/dedupe, cap comments
                      8. re-check PR head SHA unchanged, then publish ONE review
                      ▼
                 PostgreSQL  ◀──  React dashboard (later phase)
```

Key words: *webhook* = GitHub calls our URL on events. *Diff* = changed lines. *Finding* = one reported issue (file, line, severity, explanation, evidence, fix). *Anchor* = the exact diff line a comment attaches to.

---

## 3. Scope

### V1: what I will actually build
- Verify and receive GitHub PR webhooks (asynchronous, deduplicated)
- Fetch PR metadata and changed files
- Review diffs with Claude; **validate** structured findings
- **Redact secrets before any model call**; secret findings reported locally
- Post a summary + correctly anchored inline comments (with suggestion blocks where the fix replaces exact lines)
- Persist review runs and findings in PostgreSQL
- Playground endpoint for testing diffs without GitHub
- Automated tests + a documented **evaluation set with measured precision/recall**
- Minimal dashboard: login, PR list, review detail, playground

**Supported languages in V1:** Java (primary), JavaScript/TypeScript. Other file types are **skipped and reported** in the summary ("3 Python files not reviewed: unsupported").

### Later (NOT committed, needs approval)
Analytics charts, admin page, live progress (SSE), Semgrep, Check Runs, `/review` PR commands, multiple specialist reviewers, codebase-wide context (embeddings), merge gating, multi-provider LLM, public multi-tenant SaaS.

### Non-goals
Auto-approve/merge, running or building PR code, GitLab/Bitbucket, billing, fine-tuning.

---

## 4. Why it will be better, and how I will prove it

| Problem with simple "send diff to an LLM" bots | What this project does |
|---|---|
| Noisy: dozens of nitpicks | Precision-first prompt, confidence/severity thresholds, max ~12 comments, no style comments by default |
| Wrong line numbers | Lines are numbered for the model; every finding is checked against the parsed diff and quoted evidence |
| Invented bugs | Evidence quote required; batched verification pass kept **only if the eval shows it improves precision** |
| Same comments on every push | Fingerprints; no repost; suppressed findings stay suppressed |
| Secrets leave the machine | Secrets are detected and redacted **before** any outbound call |
| Hard to trust | Measured results on a labeled evaluation set, published in the README |

### Success metrics (measured by the eval harness; targets are initial and will be revised after the first baseline run)

| Metric | Initial target |
|---|---|
| Precision on labeled findings (synthetic set) | ≥ 80% |
| Recall on seeded, supported bug classes | ≥ 70% |
| Invalid inline anchors | 0 in the eval suite |
| Duplicate reviews/comments after retry/crash tests | 0 |
| Secret value present in any outbound LLM request or log | 0 (unit test) |
| Clean (no-bug) cases: false positives | Report count; set target after baseline |
| Cost per review | Measured, capped by budget |
| Baseline comparison | Beat a plain single-prompt "review this diff" reviewer on precision |

**Definitions:** *True positive* = finding on the expected file, within ±2 lines, expected category (or judged valid by a human label). *False positive* = finding labeled not valid. *Miss* = seeded issue not reported. Always report counts with percentages (for example "31/40 = 77.5%").
**Honest expectation:** published third-party benchmarks of commercial AI reviewers on real PRs report roughly 50 to 66% precision, so do **not** claim 80% on real code without evidence. Report the synthetic-set number and the real-PR number separately.

---

## 5. Tech stack (pinned choices)

| Part | Choice |
|---|---|
| Language | Java 21 (LTS) |
| Framework | **Spring Boot 4.1.x** (newest 4.1 patch from start.spring.io at project creation; pin exact version in `pom.xml`). Do not start on 3.5 (free support ended June 2026). Spring Boot 4 renamed some starters and changed JSON library packages, so **generate the project from start.spring.io and follow its output, not old tutorials** |
| Build | Maven |
| Database | PostgreSQL 16, Flyway migrations, Spring Data JPA |
| Queue | Postgres `job` table + `FOR UPDATE SKIP LOCKED` |
| Workers | Small fixed pool (2 to 4 threads). Virtual threads optional later |
| HTTP | Spring `RestClient` (GitHub, Claude); own small retry helper (3 attempts, exponential backoff + jitter, honor `retry-after`). Resilience4j only if needed later |
| AI | Claude Messages API behind `LlmPort`; model names in config (small model for summary/verify, mid model for review) |
| Auth (dashboard) | Spring Security + GitHub OAuth, session cookie, allowlist of GitHub logins |
| Frontend | React + TypeScript + Vite, Tailwind CSS, React Router, TanStack Query |
| Tests | JUnit 5, Mockito, AssertJ, Testcontainers (Postgres), WireMock |
| DevOps | Docker + Docker Compose, GitHub Actions, deploy to Render/Railway/Fly.io (backend+DB) and Vercel/Netlify (frontend) |
| Dev tunnel | smee.io or ngrok |

**Architecture:** one modular monolith. GitHub, LLM, scanners sit behind interfaces (`CodeHostPort`, `LlmPort`, `ScannerPort`) so the core review logic can be tested with fakes.

---

## 6. Setup checklist

**Install:** JDK 21, Maven, Docker Desktop, Node.js (current LTS), Git, IDE, `curl.exe`/Thunder Client, ngrok or smee-client.

**Accounts and keys**
1. GitHub account and a **test repo** (`webhook-practice` works). Create 2 to 3 test PRs now with deliberately buggy Java code.
2. Anthropic API key (console.anthropic.com), stored as env var `ANTHROPIC_API_KEY`.
3. **GitHub App** (needed from Step 6): Settings → Developer settings → GitHub Apps → New
   - Webhook URL = tunnel URL + `/webhooks/github`; webhook secret = long random string
   - Permissions (each justified): *Pull requests: Read & write* (read PRs, post reviews), *Contents: Read* (read changed files and config), *Metadata: Read* (mandatory). Add *Checks* or *Issues* only if a later feature needs them
   - Events: *Pull request* (later: Installation)
   - Save the **App ID**, download the **private key (.pem)**, install the App on the test repo
4. GitHub OAuth client ID/secret (Step 12 only).

**Environment variables** (`.env` is gitignored; commit only `.env.example`)
```
GITHUB_APP_ID=
GITHUB_APP_PRIVATE_KEY=       # PEM contents or file path
GITHUB_WEBHOOK_SECRET=
GITHUB_OAUTH_CLIENT_ID=       # step 12
GITHUB_OAUTH_CLIENT_SECRET=   # step 12
DASHBOARD_ALLOWED_LOGINS=devabhi96
ANTHROPIC_API_KEY=
DB_URL=jdbc:postgresql://localhost:5432/aicodereview
DB_USER=aicodereview
DB_PASSWORD=dev
```

---

## 7. Backend design

### 7.1 Packages (`com.devabhi.aicodereview`)

```
webhook/    controller, SignatureVerifier, event router
github/     AppAuthService (JWT -> installation token, cached), GitHubClient, DTOs
jobs/       JobQueue, Worker, Watchdog
review/
  diff/     DiffParser, DiffModel (old/new line maps), NumberedDiffFormatter
  filter/   FileFilter, language support check, RiskScorer
  secrets/  SecretScanner, Redactor
  pipeline/ ReviewPipeline, stages, ReviewContext
  validate/ FindingValidator, AnchorValidator
  rank/     Deduper, Ranker, SuppressionService
  model/    Finding, Severity, Category, Fingerprint
llm/        LlmPort, AnthropicAdapter, prompts loader, UsageRecorder
publish/    Publisher, CommentFormatter, PublicationService (state machine)
domain/     entities + repositories
api/        dashboard controllers + DTOs (later phases)
auth/       OAuth, session, access checks
config/     properties, security
```
`review` owns the interfaces; `github` and `llm` implement them. Core logic never imports GitHub or Claude classes.

### 7.2 Review pipeline (order matters)

1. **ContextFetch**: PR metadata, changed files with `patch`, file contents at the **pinned head SHA** (size capped). Remember `reviewed_head_sha`.
2. **Filter**: skip lockfiles, `dist/`, minified/generated/vendored, binaries, files without `patch`, deleted files, unsupported languages (reported in summary), user-ignored paths. Renamed files use the new path.
3. **DiffParse**: per file, per hunk: old and new line numbers; set of valid RIGHT-side lines (added + context).
4. **SecretScan + Redact (hard gate)**: scan all text that could be sent out (added lines **and** file context). Replace matches with `[REDACTED:<type>]`. Create a local secret finding. **Nothing is sent to the LLM, logged, or stored before this stage.**
5. **Chunk + budget**: batches that fit the model context; per-PR token budget; risky files first; remember skipped files.
6. **LLM review**: summary + bugs + security via forced tool-use structured output.
7. **Validate** (treat model output as untrusted): schema, enums, ranges; `file` must be in the PR; `line` in valid RIGHT lines (ranges must be contiguous lines in one hunk); `evidence` must match (whitespace-normalized) the reported line, or exactly one nearby line within ±2 (if the text matches several lines, prefer the reported line, else drop); drop anything else with a recorded `drop_reason`.
8. **(Optional, Step 10) Batched verification**: one cheap call per chunk checks all its findings; enabled only if eval shows a precision gain.
9. **Rank/dedupe/cap**: fingerprint dedupe, `min_confidence`, `min_severity`, suppression, top N.
10. **Pre-publish check**: re-fetch PR; if head SHA ≠ `reviewed_head_sha`, mark run `SUPERSEDED` and do not publish.
11. **Publish** (7.4).

### 7.3 Job queue rules (must be implemented exactly)

- **The webhook handler only verifies, stores the delivery, upserts a job, and returns 2xx.** It never calls GitHub or the LLM (GitHub gives up after 10 seconds and retries).
- Job states: `PENDING → RUNNING → DONE | FAILED | SUPERSEDED | CANCELLED`.
- Columns: `repo_id`, `pr_number`, `head_sha`, `status`, `run_after`, `attempts`, `max_attempts`, `locked_by`, `locked_at`, `last_error`.
- **Debounce upsert:** partial unique index on `(repo_id, pr_number) WHERE status='PENDING'`. New push → `INSERT ... ON CONFLICT (repo_id, pr_number) WHERE status='PENDING' DO UPDATE SET head_sha = EXCLUDED.head_sha, run_after = now() + interval '20 seconds'`. Only PENDING rows are unique, so finished jobs never block future reviews.
- **One running job per PR:** second partial unique index on `(repo_id, pr_number) WHERE status='RUNNING'`; the claim query skips PRs that already have a RUNNING job. A push during a running review creates a new PENDING job; the running one is marked `SUPERSEDED` at its pre-publish check.
- **Claim (single transaction):**
  ```sql
  SELECT * FROM job
  WHERE status='PENDING' AND run_after <= now()
    AND NOT EXISTS (SELECT 1 FROM job r WHERE r.status='RUNNING'
                    AND r.repo_id=job.repo_id AND r.pr_number=job.pr_number)
  ORDER BY run_after
  LIMIT 1
  FOR UPDATE SKIP LOCKED;
  ```
  then `UPDATE ... SET status='RUNNING', locked_by, locked_at, attempts=attempts+1` and commit. (Postgres accepts the locking clause around `LIMIT` in either order as far as I know; use this documented order.)
- **Watchdog:** RUNNING longer than the timeout (for example 10 min) goes back to PENDING if attempts remain, else FAILED.
- **Do not hold a DB transaction or connection during LLM/GitHub calls.** Load data, release the connection, do the slow calls, then write results in a short transaction. Keep HikariCP pool small (about 10) and worker count 2 to 4.

### 7.4 Publication state machine (effectively-once, not "exactly-once")

Realistic guarantee: **a retry or crash never produces duplicate comments**, because publication is recorded and reconciled.

- Table `publication`: `review_run_id` (UNIQUE), `status` (`PREPARED → SENDING → PUBLISHED | FAILED`), `intended_fingerprints`, `github_review_id`, `attempts`.
- Review body contains a hidden marker `<!-- aicodereview:run=<review_run_id> -->`; each comment contains `<!-- aicodereview:fp=<fingerprint> -->`.
- Flow: (1) one transaction inserts `PREPARED` with the finding list; (2) set `SENDING`; (3) one call `POST /repos/{o}/{r}/pulls/{n}/reviews` with `commit_id=reviewed_head_sha`, `event=COMMENT`, `comments[]` using `path`, `line`, `side=RIGHT` (and `start_line`/`start_side` for ranges; **not** the deprecated `position`); (4) on success store `github_review_id`, `PUBLISHED`.
- **Ambiguous outcome** (timeout, crash while `SENDING`): on retry, list the PR's reviews, look for the run marker; found → mark `PUBLISHED`, not found → send again.
- If GitHub rejects the batch with 422, retry by removing the bad comment(s), moving them into the summary body.
- Per-PR single worker (7.3) removes the two-workers race.
- Fingerprints already published on earlier runs for this PR are not posted again.

### 7.5 Finding format (contract between model, validator, DB, UI)

```json
{
  "findings": [{
    "file": "src/main/java/com/acme/UserController.java",
    "line": 41, "end_line": 42, "side": "RIGHT",
    "category": "bug | security | performance | maintainability | test_gap",
    "severity": "critical | high | medium | low | info",
    "confidence": 0.9,
    "cwe": "CWE-89",
    "title": "SQL injection via string concatenation",
    "explanation": "User input is concatenated into the query.",
    "evidence": "String q = \"SELECT * FROM users WHERE id=\" + id;",
    "suggested_fix": { "description": "Use a parameterized query.", "replacement_code": "..." }
  }],
  "summary": "Adds user lookup endpoint. One high-severity injection risk."
}
```
Fingerprint = hash(repo + path + category + normalized title + normalized evidence), **excluding line number**.

### 7.6 Database (Flyway) and integrity rules

| Table | Key columns | Constraints |
|---|---|---|
| `webhook_delivery` | delivery_id, event, action, received_at, status | PK `delivery_id` |
| `job` | see 7.3 | partial unique indexes (7.3); index `(status, run_after)` |
| `installation` | github_installation_id, account_login, status | UNIQUE `github_installation_id` |
| `repository` | installation_id, github_repo_id, full_name, enabled | UNIQUE `github_repo_id`; FK installation |
| `repo_settings` | repository_id, min_severity, min_confidence, max_comments, ignore_globs, custom_rules | PK/FK repository |
| `pull_request` | repository_id, number, title, author, latest_head_sha, state | UNIQUE `(repository_id, number)` |
| `review_run` | pull_request_id, head_sha, status, summary, files_reviewed, files_skipped, started_at, finished_at, error, prompt_version | UNIQUE `(pull_request_id, head_sha, trigger)`; FK; index `(pull_request_id, started_at desc)` |
| `finding` | review_run_id, repository_id, fingerprint, source, file_path, line, category, severity, confidence, cwe, title, explanation, evidence, suggested_fix (jsonb), status, drop_reason, github_comment_id | FK; index `(repository_id, fingerprint)`, `(review_run_id)`, `(severity, category)` |
| `publication` | see 7.4 | UNIQUE `review_run_id` |
| `finding_feedback` | finding_id, kind, created_at | FK |
| `suppression` | repository_id, fingerprint, created_at | UNIQUE `(repository_id, fingerprint)` |
| `llm_call` | review_run_id, stage, model, input_tokens, output_tokens, latency_ms, est_cost | FK |
| `app_user`, `user_installation` | for dashboard access | UNIQUE `github_user_id`; PK `(user_id, installation_id)` |

**Data retention:** `webhook_delivery` raw payloads deleted after 7 days. `evidence`/`suggested_fix` (code snippets) kept only as long as the retention setting (default 30 days), then blanked. Raw secrets are never stored (only redacted text).
**Transactions:** claim job (short tx), save results (short tx), publication state changes (own tx each). No transaction spans a network call.

### 7.7 REST API contracts (Phase D, `/api/v1`)

- **Auth:** every endpoint requires a session. Repo-scoped endpoints check the user has access to the repo's installation; otherwise respond **404** (do not reveal existence). Checked in the service layer, not only the UI.
- **Pagination:** `?page=0&size=20`; response `{ "items": [...], "page": 0, "size": 20, "total": 134 }`; max size 100.
- **Errors:** RFC 7807 `application/problem+json` (`type`, `title`, `status`, `detail`).
- **Endpoints (V1):** `GET /auth/me`, `GET /repositories`, `GET /repositories/{id}/pull-requests`, `GET /reviews/{runId}` (summary, status, files reviewed/skipped), `GET /reviews/{runId}/findings`, `POST /pull-requests/{id}/reviews` (re-run), `POST /findings/{id}/feedback` (`helpful | not_helpful`), `POST /findings/{id}/dismiss`, `POST /playground/review` (rate-limited).
- **Dismissed finding lifecycle:** dismiss writes `suppression(repo, fingerprint)`. Later runs drop matching findings (`drop_reason=SUPPRESSED`). Un-dismiss deletes the suppression row.
- OpenAPI via springdoc; generate the TypeScript client from it so frontend and backend never drift.

### 7.8 Repo config `.aicodereview.yml` (read from the **default branch**, never from the PR)

```yaml
version: 1
review:
  min_severity: low
  min_confidence: 0.7
  max_comments: 12
paths:
  ignore: ["**/*.lock", "dist/**", "**/generated/**"]
rules:
  - "Every controller endpoint must check authorization."
llm:
  enabled: true       # false = secret scanning only
```
Invalid file → fall back to defaults and mention it in the summary.

---

## 8. Frontend design (minimal, Phase D only)

**Style (assumption, easy to change):** light and restrained, white background, black text, light-grey sections, thin borders, one monospace font for code. Severity shown with a small colored dot **plus a text label** (never color alone). Dark mode only later.

| Route | Content |
|---|---|
| `/login` | "Sign in with GitHub" |
| `/app` | Repositories list; clicking a repo shows a PR table (title, status, last review time, finding count by severity) |
| `/app/reviews/:runId` | **Main page.** Summary + status + files reviewed/skipped. For each file, the diff lines (simple custom rendering of the patch with line numbers) and each finding shown directly under its line: title, severity, confidence, explanation, evidence, suggested fix, buttons Helpful / Not helpful / Dismiss. "Re-run review" button |
| `/app/playground` | Paste a diff or pick a sample (SQL injection, NPE, leaked key, clean refactor) → findings |

```
frontend/src/
  app/         router, layout, auth guard
  api/         generated client + query hooks
  components/  SeverityBadge, FindingCard, DiffView
  features/    repositories, reviews, playground
```
Not in V1: charts, analytics, admin page, live progress, diff-viewer libraries. GitHub PR comments are the primary UI; the dashboard is a viewer.

---

## 9. BUILD STEPS (main plan)

Estimates assume 10 to 15 hours/week and include buffer; they are guesses. **Re-estimate at each checkpoint.** Approximate calendar: Checkpoint 1 ≈ week 5, Checkpoint 2 ≈ week 9, Checkpoint 3 ≈ week 13, finish ≈ week 17 to 18. You can stop at Checkpoint 3 and still have a strong project.

### PHASE A: Review engine, no GitHub needed

**Step 1: Project setup (about 0.5 to 1 week)**
- Create repo `ai-code-review-system`; README stub, `.gitignore`, `.env.example`
- Generate Spring Boot 4.1.x (Java 21, Maven) from start.spring.io: Web, Validation, Data JPA, Flyway, Actuator, PostgreSQL driver. Pin versions
- `docker-compose.yml` with Postgres 16; Flyway `V1__init.sql` (`webhook_delivery`, `job`, and the review tables from 7.6 you need first)
- Global exception handler (problem+json); GitHub Actions running `mvn verify`
- **Done when:** `docker compose up`, app starts, `/actuator/health` UP, CI green.

**Step 2: Diff parser, file filter, numbered diff (about 1 week)**
- Save **real GitHub payloads** as test fixtures: run `curl.exe https://api.github.com/repos/devabhi96/webhook-practice/pulls/1/files` on your test PRs and store the JSON
- `DiffParser`: GitHub's `patch` field contains hunks only (starting at `@@`, no file headers). Produce per-file hunks with old/new line numbers and the set of valid RIGHT lines
- `NumberedDiffFormatter`: prefix each added/context line with its new-file line number (`+ 41 | code`)
- `FileFilter`: rules from pipeline stage 2, with a "skipped: reason" list
- Tests: multiple hunks, renamed, deleted, binary/no patch, "\ No newline at end of file", added empty lines
- **Done when:** unit tests pass on real fixtures; line numbers match what GitHub shows.

**Step 3: Secret scanner + redaction gate (about 0.5 week)**
- Regex rules: AWS keys, `ghp_`/`github_pat_` tokens, Slack tokens, private-key headers, `password|secret|token|api_key = "..."`; allowlist for obvious test/example values
- `Redactor` replaces matches with `[REDACTED:<type>]`; produces local secret findings (critical, message advises rotating the key because deleting it from the PR does not remove it from git history)
- **Rule:** redaction runs before chunking, prompts, logging and persistence
- Tests: **assert that a request body built for the LLM never contains the original secret** (including inside file context and PR description)
- **Done when:** those tests pass.

**Step 4: LLM client, prompt, validation (about 1.5 weeks)**
- `LlmPort` + `AnthropicAdapter`: `POST https://api.anthropic.com/v1/messages`, headers `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`
- Define a tool `report_findings` whose input schema is 7.5 and force it with `tool_choice`; parse the `tool_use` block. Invalid output → one corrective retry, then fail that chunk only
- Prompts in `resources/prompts/*.txt` with a `prompt_version`. Rules: only report real defects in added lines, at least 70% sure, empty list is fine, no style nitpicks, quote exact evidence, use the line number left of `|`, suggest a fix only if it replaces exactly those lines, severity rubric, 2 to 3 short examples including one "no findings" example
- **Prompt injection is mitigated, not solved:** wrap input in `<diff>` tags and say it is data, **and** treat all output as untrusted (validation in 7.2 step 7), give the model no tools with side effects, never post model text without sanitizing (strip `@mentions`, HTML, external images/links)
- `FindingValidator` + `AnchorValidator` per 7.2 step 7
- Record tokens/latency/cost to `llm_call`; timeouts and retry helper
- **Done when:** on 5 hand-made diffs the validated findings are sensible; invalid anchors are dropped with a reason.

**Step 5: Playground + eval set v0 + baseline (about 1 week)**
- `POST /api/v1/playground/review` takes a unified diff and returns validated findings (nothing persisted or posted); rate-limited
- `eval/cases/NNN-name/{diff.patch, expected.yml}`: **15 labeled cases** (about 9 with seeded issues: SQL injection, NPE, resource leak, off-by-one, path traversal, missing authorization, hardcoded key; about 6 clean/refactor/injection-attempt cases)
- Runner prints counts and percentages: precision, recall, false positives on clean cases, anchor errors, avg latency, avg cost
- **Baseline:** also run a plain single-prompt "review this diff" call and compare
- **Done when (CHECKPOINT 1):** a table of numbers exists for baseline vs your engine. Re-estimate the rest.

### PHASE B: GitHub integration

**Step 6: Webhook receiver + job queue (about 1.5 weeks)**
- `POST /webhooks/github` reads the **raw body as `byte[]`** (never re-serialize before verifying); enforce a request size limit
- `SignatureVerifier`: HMAC-SHA256 over raw bytes with the webhook secret, compare with `X-Hub-Signature-256` (`sha256=<hex>`) via `MessageDigest.isEqual`; invalid → 401
- Store `X-GitHub-Delivery` (unique) to ignore redeliveries; handle only `pull_request` actions `opened`, `synchronize`, `reopened`, `ready_for_review`; skip drafts and bot-authored PRs by default
- Queue exactly per 7.3 (upsert, one RUNNING per PR, claim query, watchdog); worker pool 2 to 4 threads
- Run app + tunnel; register the tunnel in the GitHub App
- Tests: valid/invalid/tampered signature, duplicate delivery, 3 rapid pushes → 1 pending job, a push while RUNNING → new PENDING job, finished jobs never block a new review
- **Done when:** opening a PR creates one job and the worker logs it.

**Step 7: GitHub App auth + client (about 1 week)**
- `AppAuthService`: JWT (RS256, `iss`=App ID, `exp` ≤ 10 min) → `POST /app/installations/{id}/access_tokens` → cache the ~1 hour token, refresh early
- `GitHubClient`: get PR, list files (`per_page=100`, paginate), get file content at ref, list reviews, create review. Handle rate-limit headers; back off on 403/429
- Tests with WireMock: pagination, token refresh, 429
- **Done when:** the worker fetches a real PR's files and logs counts.

**Step 8: Publish a review, in four small checkpoints (about 1.5 weeks)**
- **8a:** post a **static** review ("Hello") with one inline comment on a line of the first changed file
- **8b:** persist `pull_request`, `review_run`, `publication` for that review
- **8c:** run the Step 2 to 4 engine on the PR and publish real findings (summary + inline comments); basic 422 fallback to summary
- **8d:** add suggestion blocks (fenced block labeled `suggestion`, only when the fix replaces exactly the commented lines; otherwise a normal code block), secret findings with masked values, hidden fingerprint markers
- **Done when (CHECKPOINT 2):** a PR with a seeded SQL injection and a fake key gets a summary and correctly anchored inline comments, and DB rows exist. Re-estimate the rest.

### PHASE C: Harden and measure

**Step 9: Reliability (about 1.5 weeks)**
- Full publication state machine and ambiguous-timeout reconciliation (7.4)
- Pre-publish head-SHA check → `SUPERSEDED` (7.2 step 10)
- Retry/backoff on GitHub and LLM calls; watchdog tested
- Tests: crash after `SENDING`, timeout after GitHub accepted the review, two workers racing, push during review. **Zero duplicates.**
- **Done when:** these recovery tests pass.

**Step 10: Quality features (about 1.5 weeks)**
- Dedupe, ranker, thresholds, max comments, fingerprint suppression, no-repost across pushes
- Chunking, token estimator, per-PR token budget, risk-ordered files, skipped-files notice
- `.aicodereview.yml` (7.8)
- Batched verification pass as an **experiment**: keep it only if the eval shows better precision at acceptable cost
- **Done when:** 30-file PR finishes with skipped files explained; eval numbers recorded for each change.

**Step 11: Eval set to 40+ and regression run (about 1 week)**
- Grow to at least 40 labeled cases (about 25 positive, 15 negative), including **several from real open-source bug-fix commits** (use the code before the fix as the diff) and prompt-injection attempts
- Run baseline vs engine; write results to `eval/RESULTS.md` with counts and percentages
- GitHub Action to run the eval manually/on prompt changes (uses a secret key and a small spend cap)
- **Done when (CHECKPOINT 3):** published numbers; README table drafted. This is already a resume-worthy project.

### PHASE D: Minimal dashboard

**Step 12: Dashboard backend (about 1.5 weeks)**
- GitHub OAuth login (Spring Security `oauth2Login`: validates `state`), `HttpOnly; Secure; SameSite` session cookie, CSRF protection on state-changing requests, access limited to `DASHBOARD_ALLOWED_LOGINS`
- `installation`/`repository` sync from the `installation` events; access checks per 7.7
- Endpoints from 7.7; OpenAPI + generated TS client
- **Done when:** a second GitHub user (or test) gets 404 on your repos.

**Step 13: Frontend (about 2 weeks)**
- Scaffold Vite + TS + Tailwind + React Router + TanStack Query; auth guard
- Build in this order: review detail page (diff + findings + feedback) → repository/PR list → playground → login
- Loading, empty and error states; accessible labels; a banner "AI review is a first pass and can be wrong"
- Two or three Vitest tests; no charts
- **Done when:** log in, open a review, give feedback, dismiss a finding, use playground.

### PHASE E: Launch

**Step 14: Deploy, README, demo (about 1 week)**
- Deploy backend + Postgres (always-on tier or small VPS; sleeping free tiers miss webhooks) and frontend; HTTPS; production GitHub App
- Install the App on this project's own repo (it reviews its own PRs)
- README: pitch, demo GIF, architecture diagram, **eval results table**, privacy notice, setup guide; 3-minute demo video
- **Done when:** a stranger can install the App on a test repo and get a review.

---

## 10. Security and privacy requirements (concrete)

1. **Tenant isolation:** every repo-scoped query filters by the user's installations; enforced in the service layer; tests that try cross-user access must return 404.
2. **Secrets:** redact before any outbound call, log or DB write (Step 3). Never log request bodies at INFO. Secrets only in env vars; run gitleaks in CI on this repo.
3. **Disclosure:** README and landing text say plainly that PR code (after secret redaction) is sent to Anthropic's API; state what is sent (diff, changed-file context, PR title/description) and the retention/deletion policy (section 7.6). Provide a per-repo `llm.enabled: false` switch.
4. **Webhook:** signature check on raw bytes, constant-time compare, request size limit, malformed JSON → 400, delivery dedupe.
5. **OAuth/session:** validate `state`; secure cookie flags; CSRF protection on writes; allowlist in V1.
6. **GitHub permissions:** minimum set (section 6); remove any not used.
7. **Untrusted input:** not running PR code reduces risk but diffs still reach the parser, model, DB and GitHub; so validate all model output, sanitize comment text (no `@mentions`, raw HTML, external images/links), escape in the UI (never `dangerouslySetInnerHTML`), cap sizes.
8. **Playground abuse:** rate limit per user, diff size cap.
9. **Actuator:** only `health` public; everything else protected.
10. **Config from default branch only**, so a PR cannot weaken its own review.

---

## 11. Gotchas (save hours)

1. Webhook signature fails almost always because the body was parsed and re-serialized. Verify raw bytes first.
2. LLMs miscount lines: number lines in the prompt and validate afterward. Use `line` + `side`, not `position`.
3. One bad inline comment can make the whole review call return 422. Retry without it and move it to the summary.
4. Post **one review with many comments**, not many single comments (secondary rate limits).
5. Keep review `event` as `COMMENT`. Never approve or request changes in V1.
6. Installation tokens expire in about an hour. Cache and refresh.
7. Never hold a DB connection while waiting for the LLM or GitHub.
8. Put model names, prices and limits in config; check current model IDs in the Anthropic docs at build time.
9. A second AI call is not proof of correctness. Only the eval set tells you whether a change helped.
10. Windows PowerShell: use `curl.exe`; `echo > file` writes UTF-16 (use `Set-Content -Encoding utf8`).

---

## 12. Decisions and change log

| Decision | Choice | Why |
|---|---|---|
| Build order | Engine + playground first, GitHub second | Prove review quality before building infrastructure around it |
| Architecture | Modular monolith, ports/adapters | One developer; testable core |
| Queue | Postgres + `SKIP LOCKED`, partial unique indexes | No extra infra; lifecycle defined in 7.3 |
| Workers | Fixed pool 2 to 4, virtual threads optional | Avoid pool-starvation surprises |
| AI provider | Claude behind `LlmPort` | Learning target; swappable |
| Review action | `COMMENT` only | Never blocks merges |
| Verification | Batched per chunk, kept only if eval improves precision | Cost and honesty |
| Static analysis | Regex secrets in V1; Semgrep later (installed in the backend image or run in GitHub Actions, never Docker-in-Docker) | Deployment risk |
| Dashboard | 4 pages, login allowlist, no charts | Backend skills are the point |
| Languages | Java, JS/TS in V1; others skipped and reported | Keep scope honest |
| Theme | Light, monochrome, restrained severity indicators (assumption) | Change if you prefer dark |

**Changes made after external review (v1 → v2):** MVP reordered (engine first), Step 6 split into small checkpoints, job queue lifecycle and per-PR concurrency specified, secret redaction made a hard gate before any LLM call, publication state machine added (effectively-once), stale-SHA handling added, anchor validation strengthened, prompt-injection wording corrected (mitigation, not prevention), DB integrity rules and API contracts specified, measurable success metrics + baseline comparison added, Semgrep/analytics/admin/SSE moved to Later, Spring Boot pinned to 4.1.x, timeline re-estimated with checkpoints.

**Open questions (defaults apply):** hours per week and deadline; hosting budget (free vs about $5/month); personal tool or public; light vs dark theme.

---

## 13. Prompt to give a new AI when resuming

```
I'm building the "AI Code Review System" (Spring Boot 4.1 + PostgreSQL + GitHub App + Claude API,
minimal React dashboard later). The attached PRD.md is the full plan.
I am currently at Step __ of 14. What already works: ______
What I'm stuck on / want next: ______
My project structure / relevant code / error: ______
Read the PRD (sections 1 to 11), confirm where I am, then guide me through the next step only.
Keep answers concise, use simple analogies, give code in small pieces, and do not change
the scope or plan without telling me.
```
