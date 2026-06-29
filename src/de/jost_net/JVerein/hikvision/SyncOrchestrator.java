package de.jost_net.JVerein.hikvision;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import de.jost_net.JVerein.Einstellungen;
import de.jost_net.JVerein.rmi.Mitglied;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.logging.Logger;

/**
 * UI-free driver for the continuous, minimal-traffic, scheduled delta sync
 * (jverein → DS-K2702WX). One tick:
 * <ol>
 *   <li>bootstrap / forced full reconcile (nightly backstop) when due;</li>
 *   <li>otherwise a cheap drift gate (count probes) — auth failure aborts the
 *       tick, a genuine {@code -1} forces a full;</li>
 *   <li>build a local scope (zero controller traffic) from the existing
 *       incremental terms PLUS a per-member desired-state fingerprint that
 *       catches source-side changes made outside the plugin UI (austritt, name,
 *       externe/identity move, transponder, group);</li>
 *   <li>scoped diff via {@link SyncEngine#computePlanFor}, then (optionally)
 *       apply via {@link SyncEngine#applyCached}.</li>
 * </ol>
 * Fingerprints are advanced only after a clean (error-free, non-cancelled)
 * tick so a partial/aborted apply forces re-evaluation next tick. Reuses the
 * existing engine verbatim — no controller-side delta API exists on this
 * firmware (validated), so "minimal traffic" comes from local delta detection
 * + cheap probes + scoped writes (and the session-token auth in
 * {@link HikvisionClient}).
 */
public class SyncOrchestrator
{
  private SyncOrchestrator() {}

  /** Plugin-wide guard: a scheduled tick and a user-triggered task never run
   *  concurrently, and two ticks never overlap. compareAndSet before starting;
   *  reset in a finally. */
  public static final AtomicBoolean SYNC_IN_PROGRESS = new AtomicBoolean(false);

  private static final SimpleDateFormat ISO_D = new SimpleDateFormat("yyyy-MM-dd");

  /** Per-member desired-state fingerprint: a content hash + the member's
   *  current derived employeeNo (so an identity move can revoke the old one). */
  public static final class Fingerprint
  {
    public final String hash;
    public final String employeeNo;
    public Fingerprint(String hash, String employeeNo) { this.hash = hash; this.employeeNo = employeeNo; }
  }

  // ================= lifted from HikvisionBenutzerView (single source) =================

  /** Reason to escalate an incremental refresh to a full one, or null if
   *  incremental is fine. Throws on a controller-unreachable / auth-class
   *  failure (the caller treats that as "abort", NOT "escalate"). */
  public static String decideEscalation(MitgliedAssignments asn, PlanCache.Cached cached, HikvisionClient client)
      throws IOException
  {
    if (cached == null || cached.plan == null) return "kein Cache";
    if (asn.getLastFullRefresh() <= 0)         return "noch nie vollständig aktualisiert";
    long ageMs = System.currentTimeMillis() - asn.getLastFullRefresh();
    if (ageMs > 7L * 24 * 3600 * 1000)         return "letzte volle Aktualisierung > 7 Tage her";

    int curUsers = client.getTotalUsers();
    int curCards = client.getTotalCards();
    int knownUsers = asn.getLastFullUserTotal();
    int knownCards = asn.getLastFullCardTotal();
    if (knownUsers != curUsers || knownCards != curCards)
      return "Hikvision Gesamtzahl abweichend (Benutzer " + knownUsers + "→" + curUsers
          + ", Karten " + knownCards + "→" + curCards + ")";
    return null;
  }

  /** Scope = cached non-OK/non-HIK_ONLY rows ∪ assignments modified since the
   *  last full refresh ∪ assignments whose employeeNo isn't in the cached plan. */
  public static Set<String> buildIncrementalScope(MitgliedAssignments asn, SyncEngine.Plan cached)
  {
    Set<String> cachedEmp = new HashSet<>();
    Set<String> scope = new HashSet<>();
    for (SyncEngine.PlanRow r : cached.rows)
    {
      String canon = Identity.canonical(r.employeeNo);
      cachedEmp.add(canon);
      if (r.status != null && r.status != SyncEngine.Status.OK && r.status != SyncEngine.Status.HIK_ONLY)
        scope.add(canon);
    }
    long fullAt = asn.getLastFullRefresh();
    for (MitgliedAssignments.Assignment a : asn.all())
    {
      if (a.employeeNo == null || a.employeeNo.isEmpty()) continue;
      String canon = Identity.canonical(a.employeeNo);
      if (a.modifiedAt > fullAt || !cachedEmp.contains(canon)) scope.add(canon);
    }
    return scope;
  }

  public static int countActionableInCache(SyncEngine.Plan p)
  {
    int n = 0;
    for (SyncEngine.PlanRow r : p.rows)
      if (r.status != null && r.status != SyncEngine.Status.OK && r.status != SyncEngine.Status.HIK_ONLY) n++;
    return n;
  }

  // ================= fingerprint scan / dirty =================

  private static Map<String, Mitglied> indexMembers() throws Exception
  {
    Map<String, Mitglied> byId = new HashMap<>();
    DBIterator<Mitglied> it = Einstellungen.getDBService().createList(Mitglied.class);
    while (it.hasNext()) { Mitglied m = (Mitglied) it.next(); byId.put(m.getID(), m); }
    return byId;
  }

  /** Per-assignment desired-state fingerprint keyed by jvId, hashing exactly
   *  the jverein/store fields the plan consumes (via the same {@code Identity}
   *  / getter paths), so any real source-side delta flips the hash — including
   *  changes made outside the plugin UI. Only assignment-backed live members
   *  are scanned (members without an assignment yield no desired row →
   *  full-reconcile-only). */
  public static Map<String, Fingerprint> scanFingerprints(MitgliedAssignments asn, Map<String, Mitglied> jvById)
      throws Exception
  {
    Map<String, Fingerprint> out = new HashMap<>();
    for (MitgliedAssignments.Assignment a : asn.all())
    {
      Mitglied m = jvById.get(a.jvId);
      if (m == null) continue;   // orphan assignment / deleted member → nightly full + buildIncrementalScope
      Identity id = Identity.of(m);
      String austrittDay = m.getAustritt() == null ? "" : ISO_D.format(m.getAustritt());
      List<String> tr = new ArrayList<>(a.transponder); Collections.sort(tr);
      List<String> rg = new ArrayList<>(a.regionPermissionGroups); Collections.sort(rg);
      String name  = (safe(m.getVorname()) + " " + safe(m.getName())).trim();
      String group = a.groupManaged ? safe(a.hikvisionGroup) : "<auto>";
      String payload = String.join("",
          id.employeeNo, Boolean.toString(id.isSponsor), austrittDay, name,
          String.join(",", tr), String.join(",", rg), group);
      out.put(a.jvId, new Fingerprint(sha256Hex(payload), id.employeeNo));
    }
    return out;
  }

  /** employeeNos to (re)evaluate because their source fingerprint changed,
   *  moved identity (→ scope BOTH old and new), or vanished. */
  public static Set<String> fingerprintDirty(Map<String, Fingerprint> stored, Map<String, Fingerprint> current)
  {
    Set<String> scope = new HashSet<>();
    for (Map.Entry<String, Fingerprint> e : current.entrySet())
    {
      Fingerprint prev = stored.get(e.getKey());
      Fingerprint cur = e.getValue();
      if (prev == null || !prev.hash.equals(cur.hash))
      {
        scope.add(Identity.canonical(cur.employeeNo));
        if (prev != null && prev.employeeNo != null
            && !Identity.canonical(prev.employeeNo).equals(Identity.canonical(cur.employeeNo)))
          scope.add(Identity.canonical(prev.employeeNo));   // identity move → revoke old record too
      }
    }
    for (Map.Entry<String, Fingerprint> e : stored.entrySet())
      if (!current.containsKey(e.getKey()) && e.getValue().employeeNo != null
          && !e.getValue().employeeNo.isEmpty())
        scope.add(Identity.canonical(e.getValue().employeeNo));   // assignment removed / member gone
    return scope;
  }

  // ================= the tick =================

  /** Run one delta-sync tick. Guarded by {@link #SYNC_IN_PROGRESS} (skips if a
   *  task is already running). {@code cancelled} lets the scheduler abort a
   *  wedged run on shutdown. Never throws — logs and returns. */
  public static void tick(BooleanSupplier cancelled)
  {
    if (!SYNC_IN_PROGRESS.compareAndSet(false, true))
    { Logger.info("[hik-sync] Sync läuft bereits — geplanter Tick übersprungen."); return; }
    ProgressListener pl = logSink(cancelled);
    try { runTick(pl); }
    catch (InterruptedIOException ie) { Logger.info("[hik-sync] Tick abgebrochen: " + ie.getMessage()); }
    catch (Throwable t) { Logger.error("[hik-sync] Tick fehlgeschlagen", t); }
    finally { SYNC_IN_PROGRESS.set(false); }
  }

  private static void runTick(ProgressListener pl) throws Exception
  {
    ChipStore chips = ChipStore.defaultStore();
    MitgliedAssignments asn = MitgliedAssignments.load();
    HikvisionClient client = newClient(pl::isCancelled);
    PlanCache.Cached cached = PlanCache.load();
    boolean autoApply   = HikvisionSettings.getAutoApply();
    boolean allowDelete = HikvisionSettings.getAutoApplyDeletes();

    Map<String, Mitglied> jvById = indexMembers();
    Map<String, Fingerprint> scan = scanFingerprints(asn, jvById);

    // Phase 0 — bootstrap or forced full reconcile (nightly backstop).
    if (cached == null || cached.plan == null || forcedFullDue(asn))
    {
      pl.log("vollständige Aktualisierung "
          + (cached == null || cached.plan == null ? "(kein Cache)" : "(Voll-Intervall fällig)"));
      SyncEngine.Plan plan = SyncEngine.computePlan(chips, client, pl);
      if (plan.userTotal >= 0 && plan.cardTotal >= 0) asn.recordFullRefresh(plan.userTotal, plan.cardTotal);
      if (!autoApply || applyClean(plan, allowDelete, pl)) asn.recordMemberFingerprints(toMeta(scan));
      return;
    }

    // Phase 1 — cheap drift gate. Auth/network failure aborts; -1 (unknown) /
    // count drift escalates to a full.
    String escalate;
    try { escalate = decideEscalation(asn, cached, client); }
    catch (InterruptedIOException ie) { throw ie; }
    catch (IOException e) { pl.log("Controller nicht erreichbar / Auth — Tick abgebrochen: " + e.getMessage()); return; }
    if (escalate != null)
    {
      pl.log("Eskalation auf vollständig — " + escalate);
      SyncEngine.Plan plan = SyncEngine.computePlan(chips, client, pl);
      if (plan.userTotal >= 0 && plan.cardTotal >= 0) asn.recordFullRefresh(plan.userTotal, plan.cardTotal);
      if (!autoApply || applyClean(plan, allowDelete, pl)) asn.recordMemberFingerprints(toMeta(scan));
      return;
    }

    // Phase 2 — local scope (zero controller traffic).
    Map<String, Fingerprint> stored = fromMeta(asn.getMemberFingerprints());
    Set<String> scope = buildIncrementalScope(asn, cached.plan);
    scope.addAll(fingerprintDirty(stored, scan));
    if (scope.isEmpty())
    {
      pl.log("keine Deltas (ruhig); " + countActionableInCache(cached.plan) + " offene Aktion(en) im Cache.");
      asn.recordMemberFingerprints(toMeta(scan));
      return;
    }

    // Phase 3 — scoped diff + (optional) apply.
    pl.log(scope.size() + " employeeNo(s) im Scope (Fingerprint/Cache-Delta).");
    SyncEngine.Plan plan = SyncEngine.computePlanFor(scope, cached.plan, chips, client, pl);
    if (!autoApply || applyClean(plan, allowDelete, pl)) asn.recordMemberFingerprints(toMeta(scan));
  }

  /** Apply the plan; true on a clean (error-free) apply. A cancel throws
   *  InterruptedIOException (propagates → fingerprints NOT advanced). */
  private static boolean applyClean(SyncEngine.Plan plan, boolean allowDelete, ProgressListener pl) throws Exception
  {
    SyncEngine.Result r = SyncEngine.applyCached(plan, false, allowDelete, pl);
    if (!r.errors.isEmpty())
    {
      pl.log("Apply mit " + r.errors.size() + " Fehler(n) — Fingerprints werden NICHT fortgeschrieben.");
      return false;
    }
    return true;
  }

  private static boolean forcedFullDue(MitgliedAssignments asn)
  {
    long last = asn.getLastFullRefresh();
    if (last <= 0) return true;
    long maxAgeMs = (long) HikvisionSettings.getForcedFullIntervalMinutes() * 60_000L;
    return (System.currentTimeMillis() - last) > maxAgeMs;
  }

  static HikvisionClient newClient(BooleanSupplier cancelled)
  {
    HikvisionClient client = new HikvisionClient(
        HikvisionSettings.getControllerUrl(), HikvisionSettings.getControllerUser(),
        HikvisionSettings.getControllerPassword(), HikvisionSettings.getInterCallPauseMs(),
        HikvisionSettings.getVerifySsl());
    if (cancelled != null) client.setCancelCheck(cancelled);
    client.setResilience(HikvisionSettings.getMaxAttempts(), HikvisionSettings.getCallDeadlineMs());
    client.setUseSession(HikvisionSettings.getUseSessionAuth());
    return client;
  }

  private static ProgressListener logSink(BooleanSupplier cancelled)
  {
    return new ProgressListener() {
      @Override public void log(String msg) { Logger.info("[hik-sync] " + msg); }
      @Override public void progress(int done, int total) {}
      @Override public void progress(int done, int total, String phase) {}
      @Override public boolean isCancelled() { return cancelled != null && cancelled.getAsBoolean(); }
    };
  }

  private static Map<String, Fingerprint> fromMeta(Map<String, String[]> meta)
  {
    Map<String, Fingerprint> out = new HashMap<>();
    if (meta != null) for (Map.Entry<String, String[]> e : meta.entrySet())
    {
      String[] v = e.getValue();
      if (v != null && v.length >= 2) out.put(e.getKey(), new Fingerprint(v[0], v[1]));
    }
    return out;
  }

  private static Map<String, String[]> toMeta(Map<String, Fingerprint> fps)
  {
    Map<String, String[]> out = new HashMap<>();
    for (Map.Entry<String, Fingerprint> e : fps.entrySet())
      out.put(e.getKey(), new String[] { e.getValue().hash, e.getValue().employeeNo });
    return out;
  }

  private static String sha256Hex(String s)
  {
    try
    {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(64);
      for (byte b : d) sb.append(String.format("%02x", b));
      return sb.toString();
    }
    catch (Exception e) { throw new RuntimeException(e); }
  }

  private static String safe(String s) { return s == null ? "" : s; }
}
