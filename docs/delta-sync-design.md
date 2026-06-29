# Design Document — Continuous Minimal-Traffic Delta Sync (jverein → DS-K2702WX)

*Final. Grounds every mechanism in the current code. Incorporates the adversarial critique: the fingerprint's true coverage boundary, both-employeeNo scoping on identity moves, partial-apply fingerprint safety, auth-vs-`-1` escalation split, manual-action feedback, shutdown timeout/idempotency, and re-toned traffic and Phase-B claims.*

---

## 1. Goal & Constraints

Run an unattended, scheduled (every 1–4 h) sync from jverein (source of truth) plus the plugin-owned stores to a Hikvision DS-K2702WX access controller (firmware reported as ~V1.7.4), acting **only on real deltas** and never pulling all users/cards on a quiet tick. Direction is one-way (jverein → Hikvision), but it must also notice a bounded class of out-of-band edits made directly on the controller.

**Hard constraint (verified in code + ISAPI research):** the core path must work with **zero new ISAPI dependencies**. There is **no server-side delta/changed-since query**, **no per-record modify timestamp** on `UserInfo`/`CardInfo`, and **no version/ETag**. The only cheap controller signals *verified on this device* are the two O(1) count probes already in code (`HikvisionClient.getTotalUsers:596`, `getTotalCards:607`, each `maxResults=1`, reading `totalMatches`). Push/subscribe (`alertStream`/`subscribeEvent`/`httpHosts`) and `AcsEvent`-since-time polling are **UNVERIFIED for this model/firmware** and config-edit event emission is rated *uncertain*, so they are **optional enhancements only**, gated on live probes (§6 Phase B).

Auth is not cheaper either: digest nonce is **single-use** on this firmware family (verified multiple ways including the plugin's own header comment), so every logical call costs **2 HTTP round-trips**. Keep-alive via the single pooled `HttpClient` (`HikvisionClient.java:65,78`) amortizes TLS but not the per-call 401.

---

## 2. The Delta Model

Two independent delta sources with different cost characteristics, handled by different mechanisms.

### 2.1 Source-side (jverein/store) deltas — local, zero controller traffic

Today the incremental scope is `buildIncrementalScope` (`HikvisionBenutzerView.java:679-699`): cached non-OK/non-HIK_ONLY rows, plus assignments with `modifiedAt > lastFullRefresh`, plus assignment employeeNos not yet in the cached plan. `Assignment.modifiedAt` is bumped only on `put()`/`touch()` (plugin-UI edits). This **structurally misses** every jverein-side change made outside the plugin UI:

- **Austritt set/cleared directly in jverein** (the headline revocation case → should DISABLE / REACTIVATE). No `modifiedAt` bump, no count change, cached row stays OK → never enters scope. Only the 7-day age escalation (`decideEscalation:664`) eventually catches it — far too loose for unattended access revocation.
- **Name/Vorname change** → pushed display name drifts, never re-pushed.
- **ExterneMitgliedsnummer change** → changes the derived `employeeNo` (`Identity.of`), i.e. the member's controller identity; the old employeeNo orphans, the new one is a CREATE — both invisible incrementally.
- **Member deleted in jverein while OK on the controller** → should become DELETE; controller count is unchanged.

**Fix: a per-member desired-state fingerprint, computed locally and persisted, compared each tick.** This is the central new mechanism and costs **zero controller traffic** — it rides the `DBIterator<Mitglied>` scan that the plan already performs (`computePlanFor:591-602`).

#### 2.1.1 Fingerprint definition (must reuse the exact plan inputs)

Key by **`jvId`** (stable across externe changes). The hashed payload must include **every jverein/store field the plan consumes**, computed via the *same* code paths the plan uses, so any real source delta flips the hash and no normalization drift creates spurious churn or silent misses:

```
fingerprint(jvId) = hash(
    Identity.of(m).employeeNo,            // EXACT same call as the plan (computePlanFor:610);
                                          //   captures externe change + identity move + leading-zero norm
    Identity.of(m).isSponsor,             // drives userType (visitor/normal) + auto-group
    austrittDay,                          // ISO yyyy-MM-dd from m.getAustritt(), or "" if null —
                                          //   drives desiredEnabled + desiredValidEnd (computePlanFor:631-633)
    safe(getVorname()) + " " + safe(getName()),   // exactly row.name (computePlanFor:614)
    // store-side fields (superset of modifiedAt — a store edit also flips the fp):
    sorted(assignment.transponder),
    sorted(assignment.regionPermissionGroups),
    assignment.groupManaged ? assignment.hikvisionGroup : "<auto>"
)
```

Mandatory implementation rules (critique #6):

- **Externe normalization must call `Identity.of(m)` / `Identity.canonical` directly** (`Identity.java:25-34,61-69`). Do **not** re-derive the int-normalization in fingerprint code — `Identity.of` does `Integer.parseInt(ext.trim())` with a raw-trim fallback (`:30-31`); the fingerprint must produce the identical string or it will churn or miss on leading-zero / non-numeric externe.
- `austrittDay` uses **day granularity** so time-of-day jitter on the `Date` produces no spurious churn (the plan compares endTime at day level).
- Name and austritt are read with the exact getters/branches the plan uses (`m.getVorname()/getName()` `:614`, `m.getAustritt()` `:631`), guaranteeing fingerprint and plan see the same inputs.

#### 2.1.2 Coverage boundary — what the fingerprint can and cannot drive (critique #4, #9 — corrected)

**Verified code fact:** `computePlanFor` builds its `desired` map **exclusively by iterating `asn.all()`** (`SyncEngine.java:606`) and **skips any assignment whose employeeNo is not in scope** (`:611`). A member with **no Assignment** produces **no desired row** — the `desired.remove(emp) == null` branches `continue` without emitting an actionable row (`:710-712`), and an out-of-scope/no-Hik member yields nothing.

Consequently, putting an employeeNo into scope only drives a source-side change **if a live Assignment exists for that jvId**. The earlier draft's claim that the fingerprint "iterates all Mitglieder that map to a managed employeeNo, joining the Assignment where present" and thereby "closes the austritt gap" is **wrong for members without an Assignment**. The corrected, precise coverage statement:

| Member shape | Austritt set in jverein out-of-band | Caught by fingerprint in Phase A? |
|---|---|---|
| Has a live Assignment, currently OK on controller | fp flips (austrittDay), emp enters scope, `computePlanFor` builds desired row with `desiredEnabled=false` → **DISABLE** | **Yes** |
| Has a live Assignment, name/externe/cards changed | fp flips → UPDATE/CREATE/identity-move | **Yes** |
| **INCOMPLETE / HIK_ONLY (controller record, no Assignment)** | no Assignment to read → no desired row even if scoped (`:710-712`) | **No — full-reconcile-only** |
| Deleted in jverein, OK on controller | handled by the vanished-jvId term (§2.1.4) → DELETE | Yes (via §2.1.4) |

**Therefore Phase A delivers unattended-tight austritt revocation only for members with a live Assignment.** For INCOMPLETE / no-Assignment members, revocation latency is bounded by the **nightly forced full** (§2.2), not by the tick interval. This is stated honestly in §6 and §8; Phase A is **not** marketed as closing the austritt gap for that subset.

> **Optional Phase-A+ hardening (recommended, small):** extend `computePlanFor` to **synthesize a desired DISABLE row from `Mitglied.getAustritt()` even when no Assignment exists**, for any in-scope managed employeeNo that has a controller record. This would close the INCOMPLETE-member austritt gap at tick cadence. It touches the engine's desired-row construction (`:707-721` else-branch and the `INCOMPLETE` classification at `:685`) and so is gated behind its own review/flag, not bundled into the mechanical lift.

#### 2.1.3 Identity-move (externe change): scope BOTH old and new employeeNo (critique #5)

The fingerprint is keyed by `jvId`; scope is a set of `employeeNo`s. When a jvId's recomputed `Identity.of(m).employeeNo` differs from the employeeNo embedded in its **stored** fingerprint, the member's controller identity moved. The **new** employeeNo must be scoped (it becomes CREATE) **and the old employeeNo must be scoped in the same tick** so its now-orphaned controller record is driven to DELETE immediately — otherwise the old record stays **enabled with a live card** until the nightly full, which is an access-revocation gap, not mere cleanup latency.

This is **free**: the stored fingerprint map holds the old employeeNo. `fingerprintDirty` emits, for each jvId whose fingerprint changed, the recomputed employeeNo **and** the previously-stored employeeNo when they differ.

> Note: the old employeeNo has no Assignment under the new jvId mapping, so it reaches DELETE via the orphan branch (`computePlanFor:680-683`, `m == null` → DELETE) only if no other member now owns it. If the externe was *reassigned* to another jvId, that other member's row governs it — correct.

#### 2.1.4 DELETE / vanished-member detection

A member removed from jverein has no DB row to fingerprint. Cheapest detection, **no controller call**: track the set of `jvId`s seen in this scan; any `jvId` present in the **stored** fingerprint map but **absent** from the current scan, whose last-known employeeNo still has an OK cached row, contributes that employeeNo to scope. `computePlanFor`'s orphan branch (`:680-683`) then classifies it DELETE.

#### 2.1.5 Persistence

Store `jvId → fingerprintHash` (and the embedded last-known employeeNo) in a sidecar beside `MitgliedAssignments.meta.json`, using the **identical atomic tmp + `ATOMIC_MOVE`** pattern as `saveMeta()` (`MitgliedAssignments.java:221-233`). Suggested API mirroring the existing `getLastFull*`/`recordFullRefresh` accessors:

```java
Map<String,FpEntry> getMemberFingerprints();           // loaded in loadMeta()
void recordMemberFingerprints(Map<String,FpEntry> fp);  // atomic saveMeta()-style write
```

**Partial-apply safety (critique #7 — verified gap).** In `applyPlan`, a cancel throws `InterruptedIOException` from the pass loops (`SyncEngine.java:1083,1103`) and propagates **before** the fold/invalidate block at `:1142`. So on cancel **neither** `foldAppliedIntoCache` **nor** `PlanCache.invalidate()` runs — the cache is left stale. If the tick blindly wrote `recordMemberFingerprints` for the whole scope after a cancelled/partial apply, the next tick would see fp == stored → **no rescope**, freezing a half-applied controller state (e.g. PASS 1 detached a card, PASS 2 never re-added it) until the nightly full — a real loss-of-access window.

**Rule:** advance the stored fingerprint **only for jvIds whose rows reached `Status.OK` this tick** (i.e. only when the apply completed fully with no errors and was not cancelled). Concretely:
- On a **refresh-only** tick (`autoApply=false`): advance fingerprints for the whole scan, since nothing was written and the diff is now reflected in the cache. (Refresh recomputes the plan but performs no writes, so source state == evaluated state.)
- On an **apply** tick: advance fingerprints for the whole scan **only if** `applyCached` returned with `errors.isEmpty()` **and** the tick was not cancelled/deadlined. On **any** abort/partial/error, advance **nothing** for the in-flight scope, forcing full re-evaluation next tick. (This composes with the existing `PlanCache.invalidate()` on partial *error* at `:1152`; the new rule additionally covers the *cancel* path that bypasses that block.)

Write the fingerprint map **after** the tick's terminal outcome is known, in the same `finally`/guard that releases `syncInProgress` (§5.4).

### 2.2 Target-side (controller) drift — cheap count probe + bounded reconciliation

For in-scope members, out-of-band controller edits are **already caught**: `computePlanFor` overlays the live `currentCards`/`groupId`/`currentRegionIds`/`Valid.enable`/`currentValidEnd` onto the desired row before classifying (`:692-704`), so a card/group/validity edit made directly on the controller surfaces as UPDATE/REACTIVATE/DISABLE for any employeeNo in scope.

The gap is **out-of-scope in-place edits** (group/region/Valid changed on a member jverein didn't touch and whose count is unchanged). The only verified cheap signal is **net count drift**:

- `decideEscalation` (`HikvisionBenutzerView.java:658-674`) probes `getTotalUsers`/`getTotalCards` (O(1)) against `asn.getLastFullUserTotal()`/`getLastFullCardTotal()`. Drift → escalate to full.
- **Verified limitation:** counts catch net add/delete but **cannot** catch an in-place edit, nor a compensating add+delete that nets to the same count.

**No-deps closure of the in-place-edit gap = periodic forced full reconcile**, replacing the loose 7-day age check:

- **Cheap incremental** every 1–4 h: count probe + fingerprint scope + scoped diff + (optional) apply. Catches store edits, fingerprint-detected jverein deltas, count drift, and in-scope controller edits.
- **Forced full `computePlan`** on a tighter cadence (e.g. **nightly**) to catch same-count out-of-band controller edits that probes structurally cannot see, plus INCOMPLETE-member austritt (§2.1.2), and as catch-all backstop.

**Escalation must distinguish auth failure from a genuine `-1` (critique #11 — verified).** `getTotalUsers`/`getTotalCards` return `-1` **only** when a valid JSON response lacks `totalMatches` (`:603,:614`). An unreachable controller or a 401 storm instead **throws `IOException`** out of `postJson`/`send`. So:
- A thrown `IOException`/`HttpTimeoutException`/`InterruptedIOException` from the probe = **controller-unreachable / auth-class failure → ABORT the tick** (log + leave watermark and fingerprints untouched). Do **not** escalate to full — a skew-induced 401 storm must not silently convert every quiet tick into a max-traffic full pull.
- A returned **`-1`** (valid response, missing `totalMatches`) = unknown count → **force full** (treat as drift), per the original design.

This keeps the retry logic from "papering over" clock skew into a traffic explosion.

#### Per-card out-of-band probe — dropped from Phase A (critique #10)

`findCardOwner(cardNo)` exists (`HikvisionClient.java:572`) but in Phase A it is **redundant with the nightly full** (which already re-reads all cards and detects a moved card directly), and it is O(managed cards) so it cannot be a per-tick probe. It is **not part of Phase A** and is removed from the steady-state design. (A future targeted use — probing only a small explicit watchlist per tick — could be specified separately, but is out of scope here.)

---

## 3. Tick Algorithm (no-deps, Phase A)

A `SyncOrchestrator` (new, UI-free) drives each tick, reusing the existing headless engine verbatim:

```
tick():
  asn    = MitgliedAssignments.load()
  chips  = ChipStore.load()
  cached = PlanCache.load()                       // PlanCache.Cached
  client = buildClient(cancelFlag)                // §5.3 (Supplier/AtomicBoolean, not BackgroundTask)

  # --- Phase 0: bootstrap / forced full ---
  if cached == null || cached.plan == null || forcedFullDue(asn):   # nightly cadence OR never-full
     plan = SyncEngine.computePlan(chips, client, log)              # FULL
     if plan.userTotal>=0 && plan.cardTotal>=0:
        asn.recordFullRefresh(plan.userTotal, plan.cardTotal)       # drift watermark
     maybeApply(plan)                                               # §7
     PlanCache.save(plan)
     if tick completed clean: asn.recordMemberFingerprints(scanFingerprints())   # §2.1.5 rule
     return

  # --- Phase 1: cheap drift gate (2 controller calls) ---
  try:
     reason = decideEscalation(asn, cached, client)   # getTotalUsers + getTotalCards
  catch IOException/HttpTimeout/InterruptedIO:
     log("Controller nicht erreichbar / Auth — Tick abgebrochen, kein Full"); return   # §2.2 critique #11
  if reason != null:                                  # count drift OR -1 (unknown) OR never-full
     escalate to FULL (as Phase 0); return

  # --- Phase 2: local scope (ZERO controller traffic) ---
  scan  = scanFingerprints()                          # one DBIterator<Mitglied> pass
  scope = buildIncrementalScope(asn, cached.plan)              # existing 3 union terms
        ∪ fingerprintDirty(asn, scan)                         # NEW: emits new AND old employeeNo (§2.1.3)
        ∪ vanishedJvIdsWithOkRow(asn, scan, cached.plan)      # NEW: DELETE detection (§2.1.4)
  if scope.isEmpty(): return                          # quiet tick — ~2 calls total, no writes

  # --- Phase 3: scoped diff + apply ---
  plan = SyncEngine.computePlanFor(scope, cached.plan, chips, client, log)   # 2·ceil(N/100) reads
  maybeApply(plan)                                    # SyncEngine.applyCached — no fetch (§7)
  if tick completed clean (no cancel, no errors): asn.recordMemberFingerprints(scan)   # §2.1.5
```

`fingerprintDirty` is the **fourth scope union term** added to a *lifted* `buildIncrementalScope`: for each jvId whose recomputed fingerprint ≠ stored, it emits the canonical recomputed employeeNo **and** the stored-but-now-different employeeNo (§2.1.3). It explicitly closes the austritt/name/externe gaps that `modifiedAt` and counts miss **for Assignment-backed members** (§2.1.2).

**Engine reuse — all headless, all `ProgressListener`-driven, zero SWT / `org.eclipse.*` / `de.willuhn.jameica.gui` imports:**

- `SyncEngine.computePlan(chips, client, pl)` — `:292` — bootstrap / full reconcile.
- `SyncEngine.computePlanFor(scope, cachedBase, chips, client, pl)` — `:568` — scoped diff (requires non-null `cachedBase`, `:572`; the bootstrap branch covers the empty-cache case).
- `SyncEngine.applyCached(plan, dryRun, pl)` — `:1033` — minimal-traffic apply; builds its own client (`:1043,1048`), **no fetch**; folds desired→current into cache on full success (`foldAppliedIntoCache:1162`), invalidates on partial *error* (`:1152`).
- `PlanCache.load()/save()` — persists the plan between ticks.
- `MitgliedAssignments.recordFullRefresh` / `getLastFull*` — drift watermarks, survive restart.
- `ProgressListener` — log-only impl (the `HikvisionGruppenView.logOnly()` pattern, `:141`).

**Refactor required (mechanical).** Lift `decideEscalation` (`:658`), `buildIncrementalScope` (`:679`), `countActionableInCache` (`:701`) and the `runFullRefresh` orchestration body (`:644-648` — the headless part, *not* the `Display.getDefault().asyncExec` UI block `:649-654`) out of `HikvisionBenutzerView` (private instance methods) into a UI-free `SyncOrchestrator` / `DeltaPlanner` static helper. Their args are already pure (`MitgliedAssignments`, `PlanCache.Cached`, `HikvisionClient`, `SyncEngine.Plan`), so the lift is mechanical; the view then calls the same helper, guaranteeing one implementation. The view keeps only its SWT rendering (`renderRows`, `countLabel`, `asyncExec`).

---

## 4. Traffic Budget Per Tick (no-deps) — best-case, with caveats

"Logical call" = 1 controller operation = **2 HTTP round-trips** (single-use nonce). `pace()` (`getInterCallPauseMs`, default 2000 ms) sits between paged/batched/write calls. The numbers below are **best case**; the caveats below them are load-bearing (critique #13, #14, #12).

| Tick outcome | Logical calls (best case) | HTTP exchanges | Local cost |
|---|---|---|---|
| **Quiet** (no fp delta, no drift) | `getTotalUsers` + `getTotalCards` = **2** | 4 (+1 `pace()` ≈ 2 s) | 1 DB scan + fp compare, 0 writes |
| **N source-changed members, no drift** | 2 probe + `2·ceil(N/100)` scoped reads (+ writes) | ≈ 4 + 2·ceil(N/100)·2 + writes | scoped diff in-memory |
| **Writes for K actionable rows** | + ~1–3 per changed member (`createUser`/`setUserValid`/`setUserGroup`/`setUserRegionPermissionGroups` + 1 per card add/remove; a card MOVE is handled by PASS-1 detach then PASS-2 re-add, `applyPlan:1073-1098`) | each ×2 + a `pace()` between writes | — |
| **Escalation / nightly full** | `ceil(users/200)` user reads + `ceil(managed/100)` card reads + 1 count probe (+rarely 1 group-catalog fetch) ≈ **~10** for ~560 users; ~80 s wall incl. pacing | ≈ 20 + pacing | full DB scan |

**Caveats (must be stated, not buried):**

1. **Not a hard floor (critique #13).** `setResilience(maxAttempts, callDeadlineMs)` (`:634`) means a transient 401/timeout multiplies each probe by up to `maxAttempts`. The quiet tick is **2 logical calls best case**; a flaky controller costs up to `2·maxAttempts`.
2. **Full size depends on unvalidated caps (critique #13).** The ~10-call full assumes the hardcoded page sizes (200 users / 100 cards) are honored. If the device clamps `maxResults` lower (see §9 `UserInfo/capabilities`) or managed-card count grows, the full is more calls.
3. **`pace()` dominates wall time on writes (critique #14).** A quiet tick is 4 RT + one ~2 s pause; a write-heavy tick's wall time is dominated by `pace()`×(batched calls), not by 401 overhead. "2 calls and out" means *two logical calls*, not instant.
4. **"Quiet tick is steady state" is conditional (critique #12).** `decideEscalation` compares **device-total** card/user counts (incl. unmanaged `SKM*`/admin/visitor cards) against the last-full totals. **Any** out-of-band unmanaged-card activity flips the count and forces a full. So the quiet-tick steady state holds **only on a device with no autonomous/unmanaged-card activity** — this is exactly the §9 checklist item on autonomous changes. If the device self-enrolls cards, expect more full pulls and budget accordingly.

**Bottom line:** a quiet tick is **2 logical calls / 4 HTTP exchanges best case, no writes, no record payload**, on an otherwise-idle device; a typical small delta is **2 probe + 2 scoped + a few writes, < ~10 logical calls**; the ~80 s full fires on the nightly cadence or on count drift. This satisfies "minimal traffic" with a predictable best-case floor and the caveats above.

---

## 5. Scheduler Design in Jameica

### 5.1 Lifecycle hook

The `de.willuhn.jameica.plugin.Plugin` contract is exactly `init / install / update / shutDown / uninstall` — **there is no `started()` hook** (verified by `javap`). `Plugin.java` currently overrides nothing.

- **Start** the scheduler in `Plugin.init()`.
- **Stop** it in `Plugin.shutDown()`.
- `init()` runs at boot **before GUI/DB are fully up**. Each tick touches the JVerein `DBService` (`Einstellungen.getDBService()` in `scanFingerprints` and `computePlan*`), so **defer the first tick** until `SystemMessage.SYSTEM_STARTED` (constant = 1). Register a `MessageConsumer` (the `ScriptingService` idiom: `getExpectedMessageTypes()` → `SystemMessage.class`; `handleMessage` checks `getStatusCode() == SYSTEM_STARTED`) that arms the executor. Also handle `SYSTEM_SHUTDOWN` (= 2) to flip the cancel flag as belt-and-braces alongside `shutDown()`.

### 5.2 Daemon executor (no built-in scheduler exists)

`javap` confirms jameica.jar has **no** scheduler/cron/work-queue class. The canonical precedent is `ReminderService` (`new Timer(true)` daemon + `timer.cancel()`).

- Use a **`ScheduledThreadPoolExecutor` with a daemon `ThreadFactory`** (`setDaemon(true)`, name `"hikvision-sync-scheduler"`), preferred over a raw `Timer`: a `Timer` thread dies permanently on one uncaught exception and silently stops all future ticks; the executor isolates a failed run.
- Use **`scheduleWithFixedDelay`** (delay measured from the *end* of the previous run) — never `scheduleAtFixedRate` — so a slow/wedged tick can never trigger back-to-back catch-up runs.
- Wrap the **entire** tick body in **`try/catch (Throwable)`** so a transient ISAPI/DB error never silences the schedule.
- The scheduler thread **only dispatches** — it must do **zero ISAPI I/O** itself in headless mode it runs the tick on its own worker; in GUI mode it hands off (§5.4).

### 5.3 Cancellation / deadline wiring (wedge-safe)

- Build the client via the lifted `buildClient` pattern (`HikvisionBenutzerView.java:627`) but pass a **`Supplier<Boolean>` / `AtomicBoolean cancelFlag`** instead of a `BackgroundTask`. Wire `client.setCancelCheck(cancelFlag::get)` and `client.setResilience(getMaxAttempts(), getCallDeadlineMs())`.
- **Verified timeout layers** that bound a wedged call (critique #15a): the client sets `connectTimeout(10s)` (`:78`) and a per-request `timeout(20s)` (`:225`), and — because the header comment notes the JDK request timeout *"has been observed NOT to fire on this controller"* (`:130-133`) — `send()` runs its **own polling loop** that abandons the future on cancel or on the `callDeadlineMs` hard deadline (`:250-266`), throwing `HttpTimeoutException`/`InterruptedIOException` and cancelling the orphaned future (`f.cancel(true)`). So a wedged call self-bounds to ~`callDeadlineMs × maxAttempts` even if the underlying socket read never returns. **Confirm in §9 that an actual socket read-timeout is in force** so the abandoned future cannot keep writing.
- `shutDown()` and `SYSTEM_SHUTDOWN`: flip `cancelFlag`, then `executor.shutdownNow()` and a brief `awaitTermination(...)`. An in-flight tick aborts within the per-call deadline; Jameica shutdown won't hang on a wedged controller call. Because the future is *abandoned* rather than guaranteed-interrupted, an in-flight write may still complete shortly after `shutdownNow`; the `syncInProgress` guard (§5.4) and the partial-apply fingerprint rule (§2.1.5) ensure this can only leave cache/fingerprints in a *re-evaluate-next-tick* state, never a falsely-advanced one.

### 5.4 Single-background-task slot + wedge guard

**Verified:** the single-task-slot constraint is **GUI-only**. `de.willuhn.jameica.gui.GUI.start(BackgroundTask)` holds one `task` field and **silently drops** a second submission ("Es wird bereits eine Hintergrund-Aufgabe ausgeführt"); `de.willuhn.jameica.system.Server.start()` spawns a thread per call with no slot check. A wedged controller call holds that slot (memory: `wedged-task-locks-jameica`, mitigated v0.16.7).

Guard design:

1. A **plugin-scoped `AtomicBoolean syncInProgress`** (or a `tryLock`). Both the scheduled tick **and** `HikvisionBenutzerView.startTask()` (`:918`) must `compareAndSet(false,true)` before submitting, and reset in `finally`.
2. The scheduler **skips (never queues)** its tick if `syncInProgress` is already set — never waits, never stacks. This prevents the scheduled tick from contending with a user *Aktualisieren*/*Übertragen* and prevents two ticks overlapping.
3. **Manual-action feedback (critique #8 — required).** When the **user** path loses the CAS because the scheduler holds the lock, show a status-bar message (e.g. *"Hikvision-Sync läuft bereits — bitte kurz warten"*) — **never a silent no-op**. A silent drop here would reintroduce exactly the confusion v0.16.7 fixed. The scheduled path, by contrast, logs-and-skips silently (no user is waiting on it).
4. **GUI mode:** after a successful CAS, route the scheduled tick through `Application.getController().start(new HikvisionBackgroundTask(){…})` so the existing cancel/deadline/status-bar plumbing applies — the CAS guarantees it can't hit the silent "already running" drop. **Server/headless mode:** run the tick on the scheduler's own worker after the same CAS (no slot contention).
5. **Disposed-view safety:** the tick references **no SWT widgets** (the view may be closed). All output goes to a `Logger`-backed `ProgressListener`. A live view reflects results by polling `PlanCache.load()` itself, guarded by `isDisposed()`; the tick never calls `Display.asyncExec` into the view.
6. **Concurrent persistence:** the same `syncInProgress` lock serializes a tick's `PlanCache.save`/`recordMemberFingerprints` against the UI saving an assignment (`openAssignmentDialogFor` saves PlanCache at `:855`), avoiding a file-level race on top of the stores' internal synchronization.

### 5.5 Settings (copy the existing pattern)

`HikvisionSettings` uses static `getX/setX` over `SETTINGS.get/set` with clamped defaults (e.g. `getCallDeadlineMs:97` clamps `Math.max(1000,…)`). Add, by copying that pattern:

- `getSyncScheduleEnabled()` / `setSyncScheduleEnabled(boolean)` — master on/off (default **false** until validated).
- `getSyncIntervalMinutes()` clamped to **60–240** (1–4 h) / `setSyncIntervalMinutes(int)`.
- `getForcedFullIntervalMinutes()` (nightly default ~1440) — the in-place-edit / INCOMPLETE-austritt backstop cadence.
- `getAutoApply()` / `setAutoApply(boolean)` — **separate** from the interactive `getDryRun()` (`:104`), so a scheduled tick has its own apply decision (§7). Default **false** (refresh-only) until trusted.

Settings file: `~/.jameica/cfg/de.jost_net.JVerein.hikvision.Settings.properties`. Fields are exposed in the existing SettingsView (already in `plugin.xml`).

**Runtime reschedule semantics (critique #16).** Changing the interval cancels the current `scheduleWithFixedDelay` future and re-schedules — this affects only the **next** delay; it does **not** interrupt an in-flight tick (cancelling the future is no-op on a running task). Disabling the schedule mid-tick likewise lets the current tick finish (bounded by §5.3) and prevents the next. Document both. Make the `SYSTEM_SHUTDOWN` consumer and `shutDown()` **idempotent and null-safe** (critique #15b): there is no ordering guarantee between them, so whichever runs second must tolerate an already-cancelled flag / already-shut-down (possibly null) executor.

---

## 6. Phased Rollout

### Phase A — ship now (NO new ISAPI dependencies)

1. **Per-member desired-state fingerprint** (§2.1): scan + hash via the exact `Identity.of`/getter paths + atomic sidecar persistence (`recordMemberFingerprints`/`getMemberFingerprints`, `saveMeta()`-style). Honor the **partial-apply advance rule** (§2.1.5).
2. **Lift** `decideEscalation` / `buildIncrementalScope` / `countActionableInCache` / the headless `runFullRefresh` body into a UI-free `SyncOrchestrator`; add the **4th scope union** (`fingerprintDirty`, emitting **old + new** employeeNo) and the **vanished-jvId DELETE** term.
3. **Cadence policy:** cheap incremental every 1–4 h + **nightly forced full** (`forcedFullDue`) replacing the 7-day escalation as the unattended backstop. **Auth-class probe failure → abort tick; genuine `-1` → force full** (§2.2).
4. **Scheduler:** daemon `ScheduledThreadPoolExecutor` in `Plugin.init()`, first tick deferred to `SYSTEM_STARTED`, `scheduleWithFixedDelay`, `try/catch(Throwable)`, idempotent/null-safe `shutDown()` + `SYSTEM_SHUTDOWN`.
5. **Guard:** `syncInProgress` CAS shared with `startTask`; **skip-not-queue**; **manual-loss status-bar message** (not silent); headless `ProgressListener`; cancel-flag client.
6. **Settings + bootstrap branch:** enable/interval/forced-full/auto-apply; empty `PlanCache` → `computePlan` before any `computePlanFor`.

**Honest scope of Phase A:** true unattended delta sync with a 2-call best-case quiet floor and a bounded nightly full. Austritt revocation is **tick-tight for Assignment-backed members** and **nightly-bounded for INCOMPLETE/no-Assignment members** (§2.1.2) — unless the optional Phase-A+ synthesized-DISABLE engine change is taken. It depends only on **verified primitives** (count probes + local fingerprint + scoped `EmployeeNoList` search + scoped writes).

### Phase B — optional enhancements (gated on live device probes; none required for correctness)

- **B1 — `AcsEvent`-since-time edit detection** (`POST /ISAPI/AccessControl/AcsEvent?format=json`, `AcsEventCond` `major=3`, `startTime/endTime`, `searchID`/`searchResultPosition`, page until `responseStatusStrg != MORE`, dedupe by `serialNo`). If the device emits config events, store last-seen event time and feed only affected `employeeNoString` into `computePlanFor`. **Re-toned (critique #1):** B1 **can only *shorten* the forced-full interval for the subset of edits empirically confirmed to emit queryable events**; the periodic full **remains mandatory** because config-edit emission is rated *uncertain*, one source frames `MAJOR_OPERATION` as admin/reboot logging rather than person/card edits, and **local edits (keypad/SADP/iVMS) may emit a different code or none** — so B1 can never fully replace the full reconcile. **Unlock probes:** `GET /ISAPI/AccessControl/AcsEvent/capabilities`; then empirically edit a user on the web UI and confirm a queryable event carries the right `employeeNoString`; repeat for a *local* edit; measure on-device event-ring retention vs worst-case tick gap.
- **B2 — Event push as a change *signal*** (`alertStream` arming, or `subscribeEvent` `eventMode=list` gated on `isSupportSubscribeEvent`, or `httpHosts` listening). Use only as a trigger for targeted reconcile, never as sole truth (streams drop). Must be a **separate daemon thread**, never the single Jameica task slot. **Unlock probes:** `GET /ISAPI/System/capabilities` (`isSupportSubscribeEvent`), `GET /ISAPI/Event/notification/subscribeEventCap` (exact `minorOperation` hex). Mitigate the documented `alertStream` reconnect-loop (single connection + backoff + clean teardown + heartbeat timeout). Filter self-writes via `netUser`/`remoteHostAddr`.
- **B3 — Session login** to drop the per-call 401. **Re-toned (critique #2):** the hash scheme is **not settled** — the reported firmware string and the AES IV/iteration details are unverified/loosely-corroborated, and session-login on this model is unconfirmed. **Unlock probe:** `GET /ISAPI/Security/sessionLogin/capabilities`; branch on whatever scheme it actually returns; fall back to digest on 404/reject. Most valuable on write-heavy/full ticks; negligible on a 2-call quiet tick.
- **B4 — Capabilities-driven batch tuning:** `GET /ISAPI/AccessControl/UserInfo/capabilities?format=json` (`supportFunction`, `maxRecordNum`) to validate the hardcoded 200/100 page sizes (§4 caveat 2) instead of relying on field-observed caps.

---

## 7. Auto-Apply Policy

A scheduled tick that only refreshes the diff never writes. The scheduler uses an explicit **`getAutoApply()`** (separate from interactive `getDryRun()`). Unattended-write safety:

- Default **`autoApply=false`** (refresh-only) during initial rollout; surface actionable counts (`countActionableInCache`) in the log.
- When enabled, `applyCached(plan, dryRun=!autoApply, log)` runs the existing two-pass writer (`applyPlan:1062`), which frees moved cards in PASS 1 before re-adding in PASS 2 and is a no-op for OK/HIK_ONLY/INCOMPLETE.
- **Withhold auto-DELETE for the scheduler** (and consider a per-tick write cap): a mis-scoped DELETE is the highest-blast-radius write. Defer DELETE/orphan removal to a reviewed/manual action; INCOMPLETE/HIK_ONLY are already no-ops.
- **Fingerprint advance is gated on apply outcome** (§2.1.5): advance only on a clean, error-free, non-cancelled apply; advance nothing for in-flight scope on any abort.

---

## 8. Risks

- **INCOMPLETE / no-Assignment austritt (critique #4, #9):** revocation for members without a live Assignment is **full-reconcile-only** (`computePlanFor` emits no desired row for them, `:710-712`), so latency is bounded by the nightly cadence, not the tick interval. Mitigate fully only via the optional Phase-A+ synthesized-DISABLE engine change.
- **In-place out-of-band controller edits on out-of-scope members** are invisible to count probes (verified). The nightly full is the backstop; the gap is up to one full-cycle wide. B1/B2 would shrink it but are unverified and cannot replace it (§6 B1).
- **Compensating add+delete** in one interval nets zero count drift → missed until nightly full.
- **Identity move (externe change)** is mitigated in Phase A by scoping **both** old and new employeeNo the same tick (§2.1.3); without that fix the old record would stay enabled with a live card until the nightly full.
- **Fingerprint completeness:** any plan-consumed jverein field omitted from the hash is a silent miss; the hash must track `Identity.of`, `getAustritt`, name, and the three store fields, computed via the *same* code paths (§2.1.1).
- **Partial/cancelled apply (critique #7):** mitigated by advancing fingerprints only for rows that reached OK and only on a clean tick; on cancel the engine runs **neither** fold nor invalidate (`:1083/1103` throw before `:1142`), so the next tick must re-evaluate — guaranteed by not advancing in-flight fingerprints.
- **Wedge / shutdown (critique #15):** bounded by `callDeadlineMs × maxAttempts` via the `send()` polling loop (`:250-266`) even when the JDK request timeout misbehaves (`:130-133`); an abandoned future may complete a write shortly after `shutdownNow`, but the guard + fingerprint rule keep that to a re-evaluate-next-tick state. Confirm a real socket read-timeout (§9). Keep the interval comfortably greater than the worst-case wedge.
- **Clock skew (critique #11):** >5 min controller drift breaks digest auth → 401s. These now **abort the tick** (thrown `IOException`), not escalate to full, so skew can't convert quiet ticks into max-traffic pulls. Keep the controller on NTP (runbook).
- **Quiet-tick steady state is conditional (critique #12):** device-total count probes are flipped by any unmanaged-card (SKM*/admin/visitor) activity, forcing fulls; confirm the device does no autonomous enrollment (§9).
- **Firmware/model uncertainty:** the reported "V1.7.4" could not be reconciled with the published DS-K270X line (V1.1.x); re-confirm against live `GET /ISAPI/System/deviceInfo` (`getDeviceInfoXml:755`). This also undercuts the B3 hash-scheme assumptions.
- **`membersSkipped`/`unknownCards` are approximate in the scoped path** (`computePlanFor` counts are recomputed from the merged set; scoped `unknownCards` reflects only what was seen, `:560-563`) — exact totals only right after a full.

---

## 9. Checklist to Confirm Against the Real DS-K2702WX

**Required before enabling Phase A auto-apply:**
- [ ] `getTotalUsers()`/`getTotalCards()` return correct `totalMatches` (no spurious `-1`) under steady load; confirm an unreachable/401 condition **throws** (so the §2.2 auth-vs-`-1` split holds).
- [ ] Live firmware/model via `GET /ISAPI/System/deviceInfo` matches assumptions (reconcile V1.7.4 vs V1.1.x).
- [ ] Controller on NTP / clock within 5 min.
- [ ] Confirm a real **socket read-timeout** is in effect (not only the application-level `send()` deadline poll), so an abandoned future cannot keep writing after `shutdownNow` (critique #15a).
- [ ] Determine whether the controller ever performs **autonomous** record changes (self-enrollment, card-on-reader registration) and whether **unmanaged** cards (SKM*/admin/visitor) get added out-of-band; if not, count-gate + nightly full is fully sufficient, the quiet-tick steady state holds, and B1/B2 are pure optimizations (critique #12).
- [ ] Validate `maxResults` caps (200 users / 100 scoped) via `UserInfo/capabilities` (`maxRecordNum`, `supportFunction`) — affects the full-tick budget (§4 caveat 2).

**Required before relying on any Phase B enhancement:**
- [ ] **B1:** `GET /ISAPI/AccessControl/AcsEvent/capabilities` exists; editing a person/card on the web UI produces a `MAJOR_OPERATION(0x3)` event queryable by `startTime/endTime` carrying the right `employeeNoString`; **a local (keypad/SADP/iVMS) edit emits the same**; measure event-ring retention vs worst-case tick gap. (If local edits don't emit, B1 only shortens — never replaces — the full reconcile.)
- [ ] **B2:** `GET /ISAPI/System/capabilities` → `isSupportSubscribeEvent=true`; `GET /ISAPI/Event/notification/subscribeEventCap` → exact `minorOperation` hex for add/modify/delete person+card; self-writes distinguishable via `netUser`/`remoteHostAddr`; verify `alertStream`/`httpHosts` stability under reconnect.
- [ ] **B3:** `GET /ISAPI/Security/sessionLogin/capabilities` exists and returns a usable scheme; empirically confirm a session cookie works across calls and its idle timeout (do not assume the AES/iteration details).
- [ ] **Digest nonce (only if attempting nc-reuse as a fallback):** send 3 sequential calls reusing one nonce with `nc=00000001/2/3`; if calls 2–3 return bare 401 (expected per verified single-use behavior), do **not** pursue nc-reuse.

**Relevant files (absolute):**
- `/Users/pstrawder/Developer/jverein.hikvision/src/de/jost_net/JVerein/hikvision/SyncEngine.java` — `computePlan:292`, `computePlanFor:568` (desired-from-`asn.all()` `:606`, scope-skip `:611`, no-row branches `:710-712`, austritt classification `:631-633`), `applyCached:1033`, `applyPlan:1062` (cancel-throws `:1083,1103`; fold/invalidate block `:1142-1153`), `foldAppliedIntoCache:1162`.
- `/Users/pstrawder/Developer/jverein.hikvision/src/de/jost_net/JVerein/hikvision/HikvisionClient.java` — `getTotalUsers:596`, `getTotalCards:607` (`-1` only on missing `totalMatches`), `setCancelCheck:110`, `setResilience:143`, `send()` polling-loop deadline `:248-266`, request/connect timeouts `:78,:225`, JDK-timeout caveat `:130-133`, `findCardOwner:572`.
- `/Users/pstrawder/Developer/jverein.hikvision/src/de/jost_net/JVerein/hikvision/MitgliedAssignments.java` — `modifiedAt`, `recordFullRefresh`, `getLastFull*`, `loadMeta/saveMeta:204/221` (ATOMIC_MOVE pattern to extend for fingerprints `:221-233`).
- `/Users/pstrawder/Developer/jverein.hikvision/src/de/jost_net/JVerein/hikvision/gui/view/HikvisionBenutzerView.java` — `buildClient:627`, `runFullRefresh:641` (headless body `:644-648` to lift; UI block `:649-654` stays), `decideEscalation:658`, `buildIncrementalScope:679`, `countActionableInCache:701`, `startTask:918`.
- `/Users/pstrawder/Developer/jverein.hikvision/src/de/jost_net/JVerein/hikvision/Identity.java` — `of:25`, `canonical:61`, `isManaged:72` (fingerprint MUST reuse these).
- `/Users/pstrawder/Developer/jverein.hikvision/src/de/jost_net/JVerein/hikvision/Plugin.java` — `init`/`shutDown` hooks.
- `/Users/pstrawder/Developer/jverein.hikvision/src/de/jost_net/JVerein/hikvision/HikvisionSettings.java` — settings pattern (`getCallDeadlineMs:97`, `getDryRun:104`).
- `/Users/pstrawder/Developer/jverein.hikvision/src/de/jost_net/JVerein/hikvision/ProgressListener.java` — log-only sink.
