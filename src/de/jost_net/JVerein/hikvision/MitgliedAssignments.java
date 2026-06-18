package de.jost_net.JVerein.hikvision;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import de.jost_net.JVerein.Einstellungen;
import de.jost_net.JVerein.rmi.Felddefinition;
import de.jost_net.JVerein.rmi.Mitglied;
import de.jost_net.JVerein.rmi.Zusatzfelder;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;

/**
 * Plugin-owned per-Mitglied store. Holds the two pieces of data we no
 * longer want to live in jverein-Zusatzfeldern:
 *
 *  - transponder chip ids (replaces the {@code transponder} Zusatzfeld)
 *  - Hikvision user group ({@code Mitglieder}, {@code Vorstand},
 *    {@code Robby Bubble}, {@code BSV}) — drives userGroupNodeID on sync.
 *    Türrechte are NOT stored per Mitglied; the Hikvision controller
 *    derives access permissions from group membership automatically.
 *
 * Backed by {@code cfg/MitgliedAssignments.json}, JSON-array of objects
 * keyed by {@code jvId} (the jverein Mitglied primary key).
 *
 * Migration: {@link #migrateFromZusatzfeld} populates the store from the
 * existing transponder Zusatzfeld + the latest Hikvision UserInfo
 * (read from {@link PlanCache}). One-shot — after first run, this file
 * is the source of truth.
 */
public class MitgliedAssignments
{
  public static class Assignment
  {
    public final String jvId;
    public String externe = "";
    public String employeeNo = "";
    public final List<String> transponder = new ArrayList<>();
    /** Org userGroup. Informational only — the userGroup is applied
     *  automatically on sync (members → Mitglieder, sponsors → BSV) and is
     *  not chosen per member. Kept for visibility / migration record. */
    public String hikvisionGroup = "";
    /** Door-access Berechtigungsgruppen (region-permission group NAMES) this
     *  member should hold. Resolved to controller ids at sync time and
     *  written to the user's regionPermissionGroupIDList. Default empty (no
     *  individually-granted door access). */
    public final List<String> regionPermissionGroups = new ArrayList<>();
    /** Epoch millis of last in-store mutation. 0 = legacy entry without
     *  recorded timestamp. Used by incremental refresh to scope its
     *  Hikvision fetch to just the recently-edited assignments. */
    public long modifiedAt = 0L;

    public Assignment(String jvId) { this.jvId = jvId; }

    public boolean isComplete()
    { return !transponder.isEmpty(); }
  }

  private final File backing;
  private final LinkedHashMap<String, Assignment> byJvId = new LinkedHashMap<>();

  /** Sidecar meta — last successful full refresh + the totals the
   *  controller reported then. Kept in a separate file so the assignments
   *  JSON stays a plain array (forward-compatible with existing readers). */
  private long lastFullRefresh = 0L;
  private int lastFullUserTotal = -1;
  private int lastFullCardTotal = -1;

  private MitgliedAssignments(File backing) { this.backing = backing; }

  public static File defaultFile()
  {
    String workDir = Application.getPluginLoader().getPlugin(Plugin.class).getResources().getWorkPath();
    return new File(workDir, "MitgliedAssignments.json");
  }

  private static File metaFileFor(File backing)
  { return new File(backing.getParentFile(), "MitgliedAssignments.meta.json"); }

  public static MitgliedAssignments load() throws IOException
  {
    MitgliedAssignments s = new MitgliedAssignments(defaultFile());
    s.reload();
    s.loadMeta();
    return s;
  }

  public synchronized void reload() throws IOException
  {
    byJvId.clear();
    if (!backing.exists()) return;
    String raw = new String(Files.readAllBytes(backing.toPath()), StandardCharsets.UTF_8).trim();
    if (raw.isEmpty()) return;
    JSONArray arr = new JSONArray(raw);
    for (int i = 0; i < arr.length(); i++)
    {
      JSONObject o = arr.getJSONObject(i);
      String jvId = o.optString("jvId", "").trim();
      if (jvId.isEmpty()) continue;
      Assignment a = new Assignment(jvId);
      a.externe = o.optString("externe", "");
      a.employeeNo = o.optString("employeeNo", "");
      a.hikvisionGroup = o.optString("hikvisionGroup", "");
      a.modifiedAt = o.optLong("modifiedAt", 0L);
      JSONArray tr = o.optJSONArray("transponder");
      if (tr != null) for (int j = 0; j < tr.length(); j++)
      {
        String t = tr.optString(j, "").trim();
        if (!t.isEmpty()) a.transponder.add(t);
      }
      JSONArray rg = o.optJSONArray("regionPermissionGroups");
      if (rg != null) for (int j = 0; j < rg.length(); j++)
      {
        String t = rg.optString(j, "").trim();
        if (!t.isEmpty() && !a.regionPermissionGroups.contains(t)) a.regionPermissionGroups.add(t);
      }
      byJvId.put(jvId, a);
    }
    Logger.info("MitgliedAssignments loaded " + byJvId.size() + " entries from " + backing);
  }

  public synchronized void save() throws IOException
  {
    JSONArray arr = new JSONArray();
    for (Assignment a : byJvId.values())
    {
      JSONObject o = new JSONObject();
      o.put("jvId", a.jvId);
      o.put("externe", a.externe == null ? "" : a.externe);
      o.put("employeeNo", a.employeeNo == null ? "" : a.employeeNo);
      o.put("hikvisionGroup", a.hikvisionGroup == null ? "" : a.hikvisionGroup);
      o.put("transponder", new JSONArray(a.transponder));
      o.put("regionPermissionGroups", new JSONArray(a.regionPermissionGroups));
      if (a.modifiedAt > 0L) o.put("modifiedAt", a.modifiedAt);
      arr.put(o);
    }
    if (backing.getParentFile() != null) backing.getParentFile().mkdirs();
    File tmp = new File(backing.getParentFile(), backing.getName() + ".tmp");
    try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp, StandardCharsets.UTF_8)))
    { w.write(arr.toString(2)); }
    Files.move(tmp.toPath(), backing.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  public synchronized Assignment get(String jvId) { return byJvId.get(jvId); }
  public synchronized void put(Assignment a) { a.modifiedAt = System.currentTimeMillis(); byJvId.put(a.jvId, a); }
  /** Mark an existing Assignment as just-mutated. Required when callers
   *  modify {@code transponder}/{@code hikvisionGroup}/etc. in-place
   *  without going through {@link #put} (in-place edits and conflict
   *  resolution in AssignmentEditDialog). */
  public synchronized boolean touch(String jvId)
  {
    Assignment a = byJvId.get(jvId);
    if (a == null) return false;
    a.modifiedAt = System.currentTimeMillis();
    return true;
  }
  public synchronized boolean remove(String jvId) { return byJvId.remove(jvId) != null; }
  public synchronized int size() { return byJvId.size(); }
  public synchronized Collection<Assignment> all() { return new ArrayList<>(byJvId.values()); }

  // ---------------------------------------------------------------- meta

  public synchronized long getLastFullRefresh()  { return lastFullRefresh; }
  public synchronized int  getLastFullUserTotal() { return lastFullUserTotal; }
  public synchronized int  getLastFullCardTotal() { return lastFullCardTotal; }

  /** Record a successful full refresh. Persists to the sidecar meta file. */
  public synchronized void recordFullRefresh(int userTotal, int cardTotal) throws IOException
  {
    lastFullRefresh = System.currentTimeMillis();
    lastFullUserTotal = userTotal;
    lastFullCardTotal = cardTotal;
    saveMeta();
  }

  private synchronized void loadMeta()
  {
    File mf = metaFileFor(backing);
    if (!mf.exists()) return;
    try
    {
      String raw = new String(Files.readAllBytes(mf.toPath()), StandardCharsets.UTF_8).trim();
      if (raw.isEmpty()) return;
      JSONObject o = new JSONObject(raw);
      lastFullRefresh   = o.optLong("lastFullRefresh", 0L);
      lastFullUserTotal = o.optInt("lastFullUserTotal", -1);
      lastFullCardTotal = o.optInt("lastFullCardTotal", -1);
    }
    catch (Exception e)
    { Logger.warn("MitgliedAssignments.meta.json unlesbar — verworfen: " + e.getMessage()); }
  }

  private synchronized void saveMeta() throws IOException
  {
    JSONObject o = new JSONObject();
    o.put("lastFullRefresh", lastFullRefresh);
    o.put("lastFullUserTotal", lastFullUserTotal);
    o.put("lastFullCardTotal", lastFullCardTotal);
    File mf = metaFileFor(backing);
    if (mf.getParentFile() != null) mf.getParentFile().mkdirs();
    File tmp = new File(mf.getParentFile(), mf.getName() + ".tmp");
    try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp, StandardCharsets.UTF_8)))
    { w.write(o.toString(2)); }
    Files.move(tmp.toPath(), mf.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Reverse index: chip id → jvIds it's assigned to (usually one, but the
   *  schema doesn't forbid multiple — collisions surface in the UI). */
  public synchronized Map<String, List<String>> chipToJvIds()
  {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (Assignment a : byJvId.values())
      for (String chip : a.transponder)
        out.computeIfAbsent(chip, k -> new ArrayList<>()).add(a.jvId);
    return out;
  }

  // =====================================================================
  // Mirror write-back: plugin store → jverein transponder Zusatzfeld
  // =====================================================================
  //
  // The plugin store is the source of truth for sync, but mirroring back
  // into the Zusatzfeld keeps jverein's Mitglied detail view in sync —
  // users still see the chip list where they expect it.

  /** Write the chip list of a single Mitglied back into the Zusatzfeld.
   *  Idempotent: if the field already matches, nothing happens. Returns
   *  true if a write occurred. Silently no-ops if the Felddefinition
   *  doesn't exist (so the plugin still works in installs without it). */
  public boolean writeAssignmentToZusatzfeld(String jvId) throws Exception
  {
    Felddefinition def = findTransponderDef();
    if (def == null) return false;
    Assignment a;
    synchronized (this) { a = byJvId.get(jvId); }
    String csv = a == null ? "" : String.join(",", a.transponder);
    return writeZusatzfeldRow(jvId, def, csv);
  }

  /** Bulk write-back: for every Mitglied in the store, write its chip CSV
   *  to the Zusatzfeld. Also clears the Zusatzfeld for Mitglieder NOT in
   *  the store (so a removed assignment removes the jverein-visible value
   *  too). Returns count of rows written. */
  public int syncAllToZusatzfeld(ProgressListener pl) throws Exception
  {
    Felddefinition def = findTransponderDef();
    if (def == null)
    {
      if (pl != null) pl.log("WARN: Felddefinition '" + HikvisionSettings.getZusatzfeldName()
          + "' nicht gefunden — Rückschreiben übersprungen.");
      return 0;
    }
    Map<String, String> wantByJvId;
    synchronized (this)
    {
      wantByJvId = new HashMap<>();
      for (Assignment a : byJvId.values())
        wantByJvId.put(a.jvId, String.join(",", a.transponder));
    }
    int wrote = 0, seen = 0;
    DBIterator<Mitglied> it = Einstellungen.getDBService().createList(Mitglied.class);
    while (it.hasNext())
    {
      Mitglied m = (Mitglied) it.next();
      seen++;
      String want = wantByJvId.getOrDefault(m.getID(), "");
      String have = readZusatzfeld(m.getID(), def.getID());
      if (have == null) have = "";
      if (want.equals(have)) continue;
      if (writeZusatzfeldRow(m.getID(), def, want)) wrote++;
      if (pl != null && seen % 100 == 0) pl.progress(seen, 0, "Rückschreiben");
    }
    if (pl != null) pl.log("Rückschreiben: " + wrote + " jverein-Zusatzfelder aktualisiert (von " + seen + " geprüft).");
    return wrote;
  }

  /** Locate the configured transponder Felddefinition. Null if missing. */
  private static Felddefinition findTransponderDef() throws Exception
  {
    DBIterator<Felddefinition> defs = Einstellungen.getDBService().createList(Felddefinition.class);
    defs.addFilter("name = ?", HikvisionSettings.getZusatzfeldName());
    if (!defs.hasNext())
    {
      Logger.warn("Felddefinition '" + HikvisionSettings.getZusatzfeldName()
          + "' nicht gefunden — Rückschreiben in jverein-Zusatzfeld nicht möglich.");
      return null;
    }
    return (Felddefinition) defs.next();
  }

  /** Find-or-create the Zusatzfelder row for (mitglied, felddefinition)
   *  and set the value. Returns true if a write happened. */
  private static boolean writeZusatzfeldRow(String mitgliedId, Felddefinition def, String value) throws Exception
  {
    DBIterator<Zusatzfelder> it = Einstellungen.getDBService().createList(Zusatzfelder.class);
    it.addFilter("mitglied = ?", mitgliedId);
    it.addFilter("felddefinition = ?", def.getID());
    Zusatzfelder z;
    if (it.hasNext()) z = (Zusatzfelder) it.next();
    else
    {
      if (value == null || value.isEmpty()) return false;   // nothing to write, no row to create
      z = (Zusatzfelder) Einstellungen.getDBService().createObject(Zusatzfelder.class, null);
      z.setMitglied(Integer.parseInt(mitgliedId));
      z.setFelddefinition(Integer.parseInt(def.getID()));
    }
    z.setFeld(value == null || value.isEmpty() ? null : value);
    z.store();
    return true;
  }

  // =====================================================================
  // Migration: jverein Zusatzfeld + Hikvision UserInfo → MitgliedAssignments
  // =====================================================================

  public static class MigrationResult
  {
    public int created;
    public int updated;
    public int unchanged;
    public int matchedFromHikvision;   // group came from PlanCache
    public int defaultedActive;        // no PlanCache hit → fallback to Mitglieder group
    public int defaultedSponsor;       // no PlanCache hit → fallback to BSV group
    public int skippedNoRelevance;     // no transponder + no Hikvision record → no need to track
    public int includedDeparted;       // austritt members with Hikvision record (kept for DISABLE)
    public int orphansRemoved;         // store entries whose jvId was gone from jverein (overwrite mode only)
    public int totalInStore;
    public boolean planCacheAvailable;
    public final List<String> warnings = new ArrayList<>();
  }

  /**
   * Populates {@code MitgliedAssignments.json} from the existing
   * {@code transponder} Zusatzfeld and the latest Hikvision UserInfo
   * (read from {@link PlanCache}; if absent, falls back to defaults based
   * on the sponsor flag).
   *
   * <p>By default this is non-destructive: assignments already in the
   * store are preserved. Pass {@code overwriteExisting=true} to force a
   * re-derivation (useful after major Hikvision-side changes).
   *
   * <p>The jverein Zusatzfeld is NOT modified — the migration is read-only
   * against jverein, so it can be re-run safely.
   */
  public static MigrationResult migrateFromZusatzfeld(boolean overwriteExisting,
                                                     ProgressListener pl) throws Exception
  {
    MigrationResult r = new MigrationResult();
    MitgliedAssignments store = load();

    DBIterator<Felddefinition> defs = Einstellungen.getDBService().createList(Felddefinition.class);
    defs.addFilter("name = ?", HikvisionSettings.getZusatzfeldName());
    if (!defs.hasNext())
      throw new IllegalStateException("Felddefinition '" + HikvisionSettings.getZusatzfeldName()
          + "' nicht gefunden in jverein");
    Felddefinition transponderDef = (Felddefinition) defs.next();
    String transponderDefId = transponderDef.getID();

    // id → name for the controller's region-permission groups, so we can
    // record each user's CURRENT Berechtigungsgruppen by name (preserving
    // them — otherwise the first sync would wipe the individually-assigned
    // door access of members whose store entry has none yet).
    Map<Integer, String> regionNameById = new HashMap<>();
    for (HikvisionGroupCatalog.RegionPermissionGroup rp : HikvisionGroupCatalog.fromCache().regions)
      if (rp.name != null && !rp.name.isEmpty()) regionNameById.put(rp.id, rp.name);

    Map<String, String> groupByEmp = new HashMap<>();
    Map<String, List<String>> regionsByEmp = new HashMap<>();
    PlanCache.Cached cached = PlanCache.load();
    if (cached != null && cached.plan != null)
    {
      for (SyncEngine.PlanRow row : cached.plan.rows)
      {
        if (row.employeeNo == null || row.employeeNo.isEmpty()) continue;
        String canon = Identity.canonical(row.employeeNo);
        if (row.groupName != null && !row.groupName.isEmpty())
          groupByEmp.put(canon, row.groupName);
        if (row.currentRegionIds != null && !row.currentRegionIds.isEmpty())
        {
          List<String> names = new ArrayList<>();
          for (Integer id : row.currentRegionIds)
          {
            String nm = regionNameById.get(id);
            if (nm != null && !nm.isEmpty() && !names.contains(nm)) names.add(nm);
          }
          if (!names.isEmpty()) regionsByEmp.put(canon, names);
        }
      }
      r.planCacheAvailable = true;
      pl.log("PlanCache: " + groupByEmp.size() + " employeeNo → Organisationsgruppe, "
          + regionsByEmp.size() + " mit Berechtigungsgruppen bekannt");
    }
    else
    {
      pl.log("WARN: kein PlanCache — alle Mitglieder fallen auf Defaults (active→"
          + HikvisionSettings.getMemberGroupName() + ", sponsor→"
          + HikvisionSettings.getSponsorGroupName() + ") zurück. "
          + "Erst Benutzer-Ansicht aktualisieren empfohlen.");
    }

    DBIterator<Mitglied> it = Einstellungen.getDBService().createList(Mitglied.class);
    int seen = 0;
    java.util.Set<String> jvereinIds = new java.util.HashSet<>();
    while (it.hasNext())
    {
      Mitglied m = (Mitglied) it.next();
      seen++;

      String jvId = m.getID();
      jvereinIds.add(jvId);
      Assignment existing = store.get(jvId);
      if (existing != null && !overwriteExisting) { r.unchanged++; continue; }

      List<String> transponder = new ArrayList<>();
      String chipsRaw = readZusatzfeld(jvId, transponderDefId);
      if (chipsRaw != null && !chipsRaw.isEmpty()
          && !chipsRaw.equals("0") && !chipsRaw.equalsIgnoreCase("null"))
      {
        for (String c : chipsRaw.split(","))
        {
          String t = c.trim();
          if (!t.isEmpty()) transponder.add(t);
        }
      }

      Identity id = Identity.of(m);
      String hikGroup = groupByEmp.get(id.employeeNo);
      boolean hasHikvisionRecord = hikGroup != null && !hikGroup.isEmpty();

      // Filter: only track Mitglieder who have or had a Hikvision presence.
      // Members with no transponder AND no record on the controller are
      // irrelevant — they'll appear live in the Benutzer view from jverein
      // once they get a chip, and an assignment is created at that point.
      if (transponder.isEmpty() && !hasHikvisionRecord)
      { r.skippedNoRelevance++; continue; }

      if (m.getAustritt() != null) r.includedDeparted++;

      if (hasHikvisionRecord)
      {
        r.matchedFromHikvision++;
      }
      else
      {
        // Has transponder but no existing record yet → first-time CREATE on
        // next sync. Group default by sponsor flag.
        if (id.isSponsor)
        { hikGroup = HikvisionSettings.getSponsorGroupName(); r.defaultedSponsor++; }
        else
        { hikGroup = HikvisionSettings.getMemberGroupName();  r.defaultedActive++; }
      }

      Assignment a = new Assignment(jvId);
      a.externe = m.getExterneMitgliedsnummer() == null ? "" : m.getExterneMitgliedsnummer().trim();
      a.employeeNo = id.employeeNo;
      a.transponder.addAll(transponder);
      a.hikvisionGroup = hikGroup;
      // Preserve any door-access groups the user already has on the controller.
      List<String> seededRegions = regionsByEmp.get(id.employeeNo);
      if (seededRegions != null) a.regionPermissionGroups.addAll(seededRegions);

      store.put(a);
      if (existing == null) r.created++; else r.updated++;
    }

    // Orphan cleanup (overwrite-only): drop store entries whose jvId is no
    // longer in jverein. Required for true idempotence when a Mitglied is
    // deleted from jverein — without this, the orphan entry would linger
    // forever and run #2 would still see it.
    if (overwriteExisting)
    {
      java.util.List<String> orphanJvIds = new java.util.ArrayList<>();
      for (Assignment a : store.all())
        if (!jvereinIds.contains(a.jvId)) orphanJvIds.add(a.jvId);
      for (String j : orphanJvIds) store.remove(j);
      r.orphansRemoved = orphanJvIds.size();
    }

    store.save();
    r.totalInStore = store.size();
    pl.log("Migration: " + seen + " Mitglieder durchlaufen → " + r.totalInStore
        + " Zuweisungen im Store (created=" + r.created + " updated=" + r.updated
        + " unchanged=" + r.unchanged
        + " orphan-removed=" + r.orphansRemoved
        + " skipped(no transponder + no Hikvision record)=" + r.skippedNoRelevance
        + " austritt-mit-Hikvision-Record=" + r.includedDeparted
        + " matched-from-hikvision=" + r.matchedFromHikvision
        + " defaulted-active=" + r.defaultedActive
        + " defaulted-sponsor=" + r.defaultedSponsor + ")");
    return r;
  }

  private static String readZusatzfeld(String mitgliedId, String felddefinitionId) throws Exception
  {
    DBIterator<Zusatzfelder> it = Einstellungen.getDBService().createList(Zusatzfelder.class);
    it.addFilter("mitglied = ?", mitgliedId);
    it.addFilter("felddefinition = ?", felddefinitionId);
    if (!it.hasNext()) return null;
    Zusatzfelder z = (Zusatzfelder) it.next();
    return z.getFeld();
  }
}
