package de.jost_net.JVerein.hikvision;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import de.jost_net.JVerein.Einstellungen;
import de.jost_net.JVerein.rmi.Felddefinition;
import de.jost_net.JVerein.rmi.Mitglied;
import de.jost_net.JVerein.rmi.Zusatzfelder;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.logging.Logger;

/**
 * Computes the diff between jverein (source of truth) and the Hikvision
 * controller, and applies the diff. Manual one-shot run — triggered from
 * the Hikvision settings tab button.
 *
 * Only employeeNos this plugin "manages" are touched on the controller —
 * a managed employeeNo is either int-parseable (matched to a jverein
 * externemitgliedsnummer) or starts with 'G' followed by digits (sponsor
 * mapped to jverein id). Everything else (SKM00000NNN admin/loaner
 * entries, etc.) is ignored.
 */
public class SyncEngine
{
  /** Re-exports the top-level {@link de.jost_net.JVerein.hikvision.ProgressListener}
   *  so existing {@code de.jost_net.JVerein.hikvision.ProgressListener} call sites keep compiling. */
  public interface ProgressListener extends de.jost_net.JVerein.hikvision.ProgressListener {}

  /** A user we want to exist on Hikvision after sync. */
  public static class Desired
  {
    public final String employeeNo;
    public final String name;
    public final boolean isSponsor;
    public final Set<String> cardNos = new HashSet<>();
    public final String jvName;
    public final String jvId;
    Desired(String emp, String name, boolean sponsor, String jvId)
    { this.employeeNo = emp; this.name = name; this.isSponsor = sponsor;
      this.jvId = jvId; this.jvName = name; }
  }

  /** A user currently on Hikvision (only managed ones). */
  public static class Actual
  {
    public final String employeeNo;
    public final String name;
    public final Set<String> cardNos = new HashSet<>();
    Actual(String emp, String name) { this.employeeNo = emp; this.name = name; }
  }

  public static class Result
  {
    public int created;
    public int deleted;
    public int cardsAdded;
    public int cardsRemoved;
    public int skippedMembers;
    public int unknownCards;
    public final List<String> errors = new ArrayList<>();
    public boolean dryRun;
  }

  // ------------------------------------------------------------ Plan model

  public enum Status
  {
    OK,         // in sync — no action
    CREATE,     // new in jverein, would be created on Hikvision
    UPDATE,     // cards differ — would be added/removed on Hikvision
    DELETE,     // on Hikvision, jverein says it shouldn't be (austritt or removed)
    HIK_ONLY    // on Hikvision with an unmanaged employeeNo (SKM* etc.) — never touched
  }

  public static class PlanRow
  {
    public String employeeNo;
    public String name;
    public String userType;
    public String groupName;
    public String groupId;                                          // userGroupNodeID UUID
    public List<Integer> regionPermissionGroups = new ArrayList<>(); // regionPermissionGroupIDList
    public List<String> currentCards = new ArrayList<>();
    public List<String> desiredCards = new ArrayList<>();
    public Status status;
    public String detail = "";   // human-readable hint (e.g. "neuer Chip", "austritt 2025-01-15")
    public String jvereinName = "";
  }

  public static class Plan
  {
    public final List<PlanRow> rows = new ArrayList<>();
    public int ok, create, update, delete, hikOnly;
    public int unknownCards;
    public int membersSkipped;
  }

  /**
   * Compute the full sync plan WITHOUT touching anything. Used by both
   * the dry-run preview in the "Hikvision Benutzer" tab AND by the actual
   * apply path in {@link #run}. Result rows are stable for direct table
   * rendering — each row is one Hikvision-side employeeNo OR one
   * to-be-created jverein-side row.
   */
  public static Plan computePlan(ChipStore chips, HikvisionClient client,
                                 de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    Plan plan = new Plan();

    // Pre-resolve the Felddefinition
    DBIterator<Felddefinition> defs = Einstellungen.getDBService().createList(Felddefinition.class);
    defs.addFilter("name = ?", HikvisionSettings.getZusatzfeldName());
    if (!defs.hasNext())
      throw new IllegalStateException("Felddefinition '" + HikvisionSettings.getZusatzfeldName()
          + "' nicht gefunden in jverein");
    Felddefinition def = (Felddefinition) defs.next();
    String defId = def.getID();

    // Index jverein members: by externe (int-normalized) and by id (for G{id} sponsor lookup later)
    Map<String, Mitglied> jvByExterne = new HashMap<>();
    Map<String, Mitglied> jvById = new HashMap<>();
    DBIterator<Mitglied> jvIt = Einstellungen.getDBService().createList(Mitglied.class);
    while (jvIt.hasNext())
    {
      Mitglied m = (Mitglied) jvIt.next();
      jvById.put(m.getID(), m);
      String ext = m.getExterneMitgliedsnummer();
      if (ext != null && !ext.trim().isEmpty())
      {
        try { jvByExterne.put(String.valueOf(Integer.parseInt(ext.trim())), m); }
        catch (NumberFormatException ignored) { jvByExterne.put(ext.trim(), m); }
      }
    }
    pl.log("jverein: " + jvById.size() + " Mitglieder geladen");

    // Build desired (jverein) state — only active members with chips
    Map<String, PlanRow> desired = new TreeMap<>();
    for (Mitglied m : jvById.values())
    {
      Date austritt = m.getAustritt();
      if (austritt != null) { plan.membersSkipped++; continue; }

      String chipsRaw = readZusatzfeld(m.getID(), defId);
      if (chipsRaw == null || chipsRaw.isEmpty() || chipsRaw.equals("0") || chipsRaw.equalsIgnoreCase("null"))
      { plan.membersSkipped++; continue; }

      List<String> desiredCards = new ArrayList<>();
      for (String chip : chipsRaw.split(","))
      {
        String c = chip.trim(); if (c.isEmpty()) continue;
        String cardNo = chips.cardForChip(c);
        if (cardNo == null) { plan.unknownCards++; pl.log("WARN: chip '" + c + "' (jv_id=" + m.getID()
            + " " + safe(m.getVorname()) + " " + safe(m.getName()) + ") nicht im ChipStore"); continue; }
        desiredCards.add(cardNo);
      }
      if (desiredCards.isEmpty()) { plan.membersSkipped++; continue; }

      Identity id = Identity.of(m);
      PlanRow row = new PlanRow();
      row.employeeNo = id.employeeNo;
      row.name = (safe(m.getVorname()) + " " + safe(m.getName())).trim();
      row.userType = id.isSponsor ? "visitor" : "normal";
      row.groupName = id.isSponsor ? HikvisionSettings.getSponsorGroupName() : HikvisionSettings.getMemberGroupName();
      row.groupId = id.isSponsor ? HikvisionSettings.getSponsorGroupId() : HikvisionSettings.getMemberGroupId();
      row.regionPermissionGroups.add(HikvisionSettings.getRegionPermissionGroup());
      row.desiredCards = desiredCards;
      row.jvereinName = row.name;
      desired.put(id.employeeNo, row);
    }
    pl.log("jverein: " + desired.size() + " Mitglieder mit Transponder-Wert");

    // Pull Hikvision actual state
    pl.log("Hikvision UserInfo abrufen…");
    JSONArray users = client.listAllUsers(pl);
    pl.log("Hikvision CardInfo abrufen…");
    JSONArray cards = client.listAllCards(pl);

    Map<String, List<String>> cardsByEmp = new HashMap<>();
    for (int i = 0; i < cards.length(); i++)
    {
      JSONObject c = cards.getJSONObject(i);
      cardsByEmp.computeIfAbsent(c.optString("employeeNo"), k -> new ArrayList<>()).add(c.optString("cardNo"));
    }

    Map<String, JSONObject> actualByEmp = new TreeMap<>();
    for (int i = 0; i < users.length(); i++)
    {
      JSONObject u = users.getJSONObject(i);
      actualByEmp.put(u.optString("employeeNo"), u);
    }

    // Walk every Hikvision entry first (so the table shows everything)
    for (Map.Entry<String, JSONObject> e : actualByEmp.entrySet())
    {
      String emp = e.getKey();
      JSONObject u = e.getValue();
      List<String> cur = cardsByEmp.getOrDefault(emp, new ArrayList<>());

      if (!Identity.isManaged(emp))
      {
        PlanRow row = new PlanRow();
        row.employeeNo = emp;
        row.name = u.optString("name", "");
        row.userType = u.optString("userType", "");
        row.groupName = u.optString("userGroupNodeName", "");
        row.groupId = u.optString("userGroupNodeID", "");
        copyRegionPermissions(u, row);
        row.currentCards = cur;
        row.status = Status.HIK_ONLY;
        row.detail = "unverwalteter employeeNo (z.B. SKM*) — wird bei Sync nie angefasst";
        plan.rows.add(row); plan.hikOnly++;
        continue;
      }

      PlanRow d = desired.remove(emp);
      if (d == null)
      {
        // Hikvision-managed entry with no jverein match → would delete
        PlanRow row = new PlanRow();
        row.employeeNo = emp;
        row.name = u.optString("name", "");
        row.userType = u.optString("userType", "");
        row.groupName = u.optString("userGroupNodeName", "");
        row.groupId = u.optString("userGroupNodeID", "");
        copyRegionPermissions(u, row);
        row.currentCards = cur;
        row.status = Status.DELETE;

        // Look up jverein member by emp (numeric ⇒ externe, G… ⇒ id)
        Mitglied m = null;
        if (emp.startsWith("G")) m = jvById.get(emp.substring(1));
        else { try { m = jvByExterne.get(String.valueOf(Integer.parseInt(emp))); } catch (NumberFormatException nfe) {} }
        if (m != null)
        {
          row.jvereinName = (safe(m.getVorname()) + " " + safe(m.getName())).trim();
          Date austritt = m.getAustritt();
          if (austritt != null) row.detail = "austritt " + austritt + " — Hikvision-Eintrag noch aktiv";
          else row.detail = "jverein hat kein Transponder-Wert mehr für dieses Mitglied";
        }
        else
        {
          row.detail = "kein zugehöriges jverein-Mitglied — verwaister Eintrag";
        }
        plan.rows.add(row); plan.delete++;
        continue;
      }

      // Both sides present — compare cards. Also overwrite the desired-side
      // group / region fields with the Hikvision-actual values so the row
      // reflects what's on the controller (used by HikvisionGroupCatalog for
      // accurate member counts and by the Benutzer table for "real" state).
      d.currentCards = cur;
      d.groupId = u.optString("userGroupNodeID", d.groupId);
      d.groupName = u.optString("userGroupNodeName", d.groupName);
      d.regionPermissionGroups.clear();
      copyRegionPermissions(u, d);

      Set<String> add = new HashSet<>(d.desiredCards); add.removeAll(cur);
      Set<String> rem = new HashSet<>(cur); rem.removeAll(d.desiredCards);
      if (add.isEmpty() && rem.isEmpty())
      {
        d.status = Status.OK;
        d.detail = "in sync";
        plan.rows.add(d); plan.ok++;
      }
      else
      {
        d.status = Status.UPDATE;
        StringBuilder det = new StringBuilder();
        if (!add.isEmpty()) det.append("hinzu: ").append(String.join(",", add));
        if (!rem.isEmpty()) { if (det.length() > 0) det.append("  "); det.append("entfernen: ").append(String.join(",", rem)); }
        d.detail = det.toString();
        plan.rows.add(d); plan.update++;
      }
    }

    // Anything left in desired → not on Hikvision yet → CREATE
    for (PlanRow row : desired.values())
    {
      row.status = Status.CREATE;
      row.detail = "neu auf Hikvision anlegen";
      plan.rows.add(row); plan.create++;
    }

    pl.log("Plan: ok=" + plan.ok + " neu=" + plan.create + " geändert=" + plan.update
        + " löschen=" + plan.delete + " hik-only=" + plan.hikOnly
        + " | übersprungen=" + plan.membersSkipped + " unbekannte Transponder=" + plan.unknownCards);
    // Persist for later UI reads — Benutzer tab loads this on open instead of
    // hitting the controller. Only Aktualisieren/Sync re-fetches.
    PlanCache.save(plan);
    // Also refresh the standalone catalog so the Settings dropdowns are up
    // to date — they read from HikvisionGroups.json (the lighter file) not
    // from PlanCache.
    HikvisionGroupCatalog cat = HikvisionGroupCatalog.fromPlan(plan, System.currentTimeMillis());
    HikvisionGroupCatalog.annotateRegionNames(cat, client, pl);
    HikvisionGroupCatalog.save(cat);
    return plan;
  }

  private static String safe(String s) { return s == null ? "" : s; }

  private static void copyRegionPermissions(JSONObject u, PlanRow row)
  {
    JSONArray rp = u.optJSONArray("regionPermissionGroupIDList");
    if (rp != null) for (int i = 0; i < rp.length(); i++) row.regionPermissionGroups.add(rp.optInt(i));
  }

  /**
   * Build desired state from jverein + ChipStore. Returns map: employeeNo -> Desired.
   */
  public static Map<String, Desired> buildDesired(ChipStore chips, String zusatzfeldName,
                                                  de.jost_net.JVerein.hikvision.ProgressListener pl, Result r) throws Exception
  {
    // Pre-resolve the Felddefinition for the transponder zusatzfeld
    DBIterator<Felddefinition> defs = Einstellungen.getDBService().createList(Felddefinition.class);
    defs.addFilter("name = ?", zusatzfeldName);
    if (!defs.hasNext())
      throw new IllegalStateException("Felddefinition '" + zusatzfeldName + "' nicht gefunden in jverein");
    Felddefinition transponderDef = (Felddefinition) defs.next();
    String transponderDefId = transponderDef.getID();

    Map<String, Desired> desired = new TreeMap<>();
    DBIterator<Mitglied> it = Einstellungen.getDBService().createList(Mitglied.class);
    int seen = 0;
    while (it.hasNext())
    {
      Mitglied m = (Mitglied) it.next();
      seen++;

      // skip departed members
      Date austritt = m.getAustritt();
      if (austritt != null) { r.skippedMembers++; continue; }

      // look up the transponder zusatzfeld value
      String chipsRaw = readZusatzfeld(m.getID(), transponderDefId);
      if (chipsRaw == null || chipsRaw.isEmpty() || chipsRaw.equals("0") || chipsRaw.equalsIgnoreCase("null"))
      { r.skippedMembers++; continue; }

      // resolve each chip -> Kartennummer
      Set<String> cardNos = new HashSet<>();
      for (String chip : chipsRaw.split(","))
      {
        String c = chip.trim();
        if (c.isEmpty()) continue;
        String cardNo = chips.cardForChip(c);
        if (cardNo == null)
        {
          r.unknownCards++;
          pl.log("WARN: chip '" + c + "' (jv_id=" + m.getID() + " " + m.getVorname() + " " + m.getName()
              + ") nicht in ChipStore — übersprungen");
          continue;
        }
        cardNos.add(cardNo);
      }
      if (cardNos.isEmpty()) { r.skippedMembers++; continue; }

      Identity ident = Identity.of(m);
      String fullName = (m.getVorname() == null ? "" : m.getVorname().trim())
                      + " " + (m.getName() == null ? "" : m.getName().trim());
      fullName = fullName.trim();
      Desired d = new Desired(ident.employeeNo, fullName, ident.isSponsor, m.getID());
      d.cardNos.addAll(cardNos);
      desired.put(ident.employeeNo, d);
    }
    pl.log("jverein scanned: " + seen + " Mitglieder → desired entries: " + desired.size());
    return desired;
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

  public static Map<String, Actual> buildActual(HikvisionClient client, de.jost_net.JVerein.hikvision.ProgressListener pl)
      throws Exception
  {
    pl.log("Hikvision UserInfo abrufen…");
    JSONArray users = client.listAllUsers(pl);
    pl.log("Hikvision CardInfo abrufen…");
    JSONArray cards = client.listAllCards(pl);

    Map<String, Actual> actual = new TreeMap<>();
    int unmanaged = 0;
    for (int i = 0; i < users.length(); i++)
    {
      JSONObject u = users.getJSONObject(i);
      String emp = u.optString("employeeNo");
      if (!Identity.isManaged(emp)) { unmanaged++; continue; }
      actual.put(emp, new Actual(emp, u.optString("name", "")));
    }
    for (int i = 0; i < cards.length(); i++)
    {
      JSONObject c = cards.getJSONObject(i);
      String emp = c.optString("employeeNo");
      Actual a = actual.get(emp);
      if (a != null) a.cardNos.add(c.optString("cardNo"));
    }
    pl.log("Hikvision: " + actual.size() + " verwaltete Einträge, " + unmanaged + " unverwaltete (SKM* etc. — bleiben unangetastet)");
    return actual;
  }

  public static Result run(boolean dryRun, de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    Result r = new Result();
    r.dryRun = dryRun;

    pl.log("=== Sync " + (dryRun ? "(DRY-RUN)" : "(APPLY)") + " ===");

    ChipStore chips = ChipStore.defaultStore();
    pl.log("ChipStore: " + chips.size() + " Chip↔Kartennummer-Einträge geladen");

    HikvisionClient client = new HikvisionClient(
        HikvisionSettings.getControllerUrl(), HikvisionSettings.getControllerUser(),
        HikvisionSettings.getControllerPassword(), HikvisionSettings.getInterCallPauseMs(),
        HikvisionSettings.getVerifySsl());

    Map<String, Desired> desired = buildDesired(chips, HikvisionSettings.getZusatzfeldName(), pl, r);
    Map<String, Actual>  actual  = buildActual(client, pl);

    // Compute diff
    Set<String> toCreate = new HashSet<>(desired.keySet());
    toCreate.removeAll(actual.keySet());
    Set<String> toDelete = new HashSet<>(actual.keySet());
    toDelete.removeAll(desired.keySet());
    Set<String> overlap = new HashSet<>(desired.keySet());
    overlap.retainAll(actual.keySet());

    pl.log("Diff: create=" + toCreate.size()
        + "  delete=" + toDelete.size()
        + "  overlap=" + overlap.size());

    int total = toCreate.size() + toDelete.size() + overlap.size();
    int done = 0;
    pl.progress(done, total, "Sync läuft");

    // --- create ---
    for (String emp : new ArrayList<>(toCreate))
    {
      if (pl.isCancelled()) throw new java.io.InterruptedIOException("Abgebrochen nach " + done + "/" + total);
      Desired d = desired.get(emp);
      pl.log("CREATE " + emp + " " + d.name + "  cards=" + d.cardNos);
      if (!dryRun)
      {
        boolean okU = client.createUser(emp, d.name,
            d.isSponsor ? "visitor" : "normal",
            d.isSponsor ? HikvisionSettings.getSponsorGroupId() : HikvisionSettings.getMemberGroupId(),
            d.isSponsor ? HikvisionSettings.getSponsorGroupName() : HikvisionSettings.getMemberGroupName(),
            HikvisionSettings.getRegionPermissionGroup(), "");
        if (!okU) { r.errors.add("createUser " + emp + " failed"); pl.log("  ! createUser failed"); }
        else
        {
          r.created++;
          for (String cn : d.cardNos)
          {
            boolean okC = client.createCard(emp, cn);
            if (okC) r.cardsAdded++;
            else { r.errors.add("createCard " + emp + "/" + cn + " failed"); pl.log("  ! createCard " + cn + " failed"); }
          }
        }
      }
      pl.progress(++done, total, "Sync läuft");
    }

    // --- delete ---
    for (String emp : new ArrayList<>(toDelete))
    {
      if (pl.isCancelled()) throw new java.io.InterruptedIOException("Abgebrochen nach " + done + "/" + total);
      Actual a = actual.get(emp);
      pl.log("DELETE " + emp + " " + a.name + "  cards=" + a.cardNos);
      if (!dryRun)
      {
        for (String cn : a.cardNos)
        {
          boolean okC = client.deleteCard(cn);
          if (okC) r.cardsRemoved++;
          else { r.errors.add("deleteCard " + cn + " failed"); pl.log("  ! deleteCard " + cn + " failed"); }
        }
        boolean okU = client.deleteUser(emp);
        if (okU) r.deleted++;
        else { r.errors.add("deleteUser " + emp + " failed"); pl.log("  ! deleteUser failed"); }
      }
      pl.progress(++done, total, "Sync läuft");
    }

    // --- card diff in overlap (note: name/type/group changes NOT handled here) ---
    for (String emp : new ArrayList<>(overlap))
    {
      if (pl.isCancelled()) throw new java.io.InterruptedIOException("Abgebrochen nach " + done + "/" + total);
      Desired d = desired.get(emp);
      Actual a = actual.get(emp);
      Set<String> add = new HashSet<>(d.cardNos); add.removeAll(a.cardNos);
      Set<String> rem = new HashSet<>(a.cardNos); rem.removeAll(d.cardNos);
      if (add.isEmpty() && rem.isEmpty()) { pl.progress(++done, total, "Sync läuft"); continue; }
      pl.log("UPDATE " + emp + " " + d.name + "  +" + add + "  -" + rem);
      if (!dryRun)
      {
        for (String cn : rem) { if (client.deleteCard(cn)) r.cardsRemoved++; else r.errors.add("deleteCard " + cn); }
        for (String cn : add) { if (client.createCard(emp, cn)) r.cardsAdded++; else r.errors.add("createCard " + emp + "/" + cn); }
      }
      pl.progress(++done, total, "Sync läuft");
    }

    pl.log("=== DONE ===  created=" + r.created + " deleted=" + r.deleted
        + " cardsAdded=" + r.cardsAdded + " cardsRemoved=" + r.cardsRemoved
        + " errors=" + r.errors.size());
    if (!r.errors.isEmpty())
    {
      for (String e : r.errors) Logger.warn("sync error: " + e);
    }
    // The plan cache reflects pre-apply state; if anything was applied, the
    // cache no longer matches reality. Drop it so the Benutzer tab knows to
    // re-fetch (better to be honest than to show stale entries as still-
    // pending after they were just synced).
    if (!dryRun && (r.created > 0 || r.deleted > 0 || r.cardsAdded > 0 || r.cardsRemoved > 0))
      PlanCache.invalidate();
    return r;
  }

  // =====================================================================
  // Reverse-import: Hikvision → jverein (used for bootstrap)
  // =====================================================================

  public static class ImportResult
  {
    public int membersUpdated;
    public int membersUnchanged;
    public int hikvisionUsersUnmatched;
    public int unknownCards;
    public boolean dryRun;
    public final List<String> errors = new ArrayList<>();
  }

  /**
   * Pulls Hikvision UserInfo + CardInfo and writes the chip list (joined
   * with ChipStore) into each matched jverein member's transponder
   * zusatzfeld. Only managed employeeNos (int-parseable or G-prefix) are
   * considered.
   *
   * INTENDED for one-time bootstrap. In steady-state jverein is the source
   * of truth and the regular {@link #run} sync (jverein → Hikvision) is
   * what you want.
   */
  public static ImportResult importFromHikvision(boolean dryRun, de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    ImportResult r = new ImportResult();
    r.dryRun = dryRun;

    pl.log("=== Import " + (dryRun ? "(DRY-RUN)" : "(APPLY)") + " ===");

    ChipStore chips = ChipStore.defaultStore();
    pl.log("ChipStore: " + chips.size() + " Chip↔Kartennummer-Einträge geladen");

    HikvisionClient client = new HikvisionClient(
        HikvisionSettings.getControllerUrl(), HikvisionSettings.getControllerUser(),
        HikvisionSettings.getControllerPassword(), HikvisionSettings.getInterCallPauseMs(),
        HikvisionSettings.getVerifySsl());

    pl.log("Hikvision UserInfo abrufen…");
    JSONArray users = client.listAllUsers(pl);
    pl.log("Hikvision CardInfo abrufen…");
    JSONArray cards = client.listAllCards(pl);

    // group cards by employeeNo
    Map<String, List<String>> cardsByEmp = new HashMap<>();
    for (int i = 0; i < cards.length(); i++)
    {
      JSONObject c = cards.getJSONObject(i);
      String emp = c.optString("employeeNo");
      cardsByEmp.computeIfAbsent(emp, k -> new ArrayList<>()).add(c.optString("cardNo"));
    }

    // index jverein members by externe (int-normalized) and by jv_id (for G-prefix)
    Map<String, Mitglied> byExterne = new HashMap<>();
    Map<String, Mitglied> byId = new HashMap<>();
    DBIterator<Mitglied> it = Einstellungen.getDBService().createList(Mitglied.class);
    while (it.hasNext())
    {
      Mitglied m = (Mitglied) it.next();
      byId.put(m.getID(), m);
      String ext = m.getExterneMitgliedsnummer();
      if (ext != null && !ext.trim().isEmpty())
      {
        try { byExterne.put(String.valueOf(Integer.parseInt(ext.trim())), m); }
        catch (NumberFormatException ignore) { byExterne.put(ext.trim(), m); }
      }
    }

    // pre-resolve the Felddefinition
    DBIterator<Felddefinition> defs = Einstellungen.getDBService().createList(Felddefinition.class);
    defs.addFilter("name = ?", HikvisionSettings.getZusatzfeldName());
    if (!defs.hasNext())
      throw new IllegalStateException("Felddefinition '" + HikvisionSettings.getZusatzfeldName() + "' nicht gefunden in jverein");
    Felddefinition transponderDef = (Felddefinition) defs.next();
    String transponderDefId = transponderDef.getID();

    int total = users.length(), done = 0;
    for (int i = 0; i < users.length(); i++)
    {
      if (pl.isCancelled()) throw new java.io.InterruptedIOException("Abgebrochen nach " + done + "/" + total);
      JSONObject u = users.getJSONObject(i);
      String emp = u.optString("employeeNo");
      done++; pl.progress(done, total, "Import läuft");

      if (!Identity.isManaged(emp)) continue;

      Mitglied member;
      if (emp.startsWith("G")) member = byId.get(emp.substring(1));
      else
      {
        try { member = byExterne.get(String.valueOf(Integer.parseInt(emp))); }
        catch (NumberFormatException nfe) { member = byExterne.get(emp); }
      }
      if (member == null)
      {
        r.hikvisionUsersUnmatched++;
        pl.log("kein jverein-Match für employeeNo=" + emp + " (" + u.optString("name") + ")");
        continue;
      }

      // resolve each cardNo -> chip via ChipStore
      List<String> userCards = cardsByEmp.getOrDefault(emp, new ArrayList<>());
      List<String> resolvedChips = new ArrayList<>();
      for (String cardNo : userCards)
      {
        String chip = chips.chipForCard(cardNo);
        if (chip == null)
        {
          r.unknownCards++;
          pl.log("WARN: Kartennummer " + cardNo + " (emp=" + emp + " " + u.optString("name")
              + ") nicht im ChipStore — übersprungen");
          continue;
        }
        resolvedChips.add(chip);
      }
      String proposed = String.join(",", resolvedChips);
      String current = readZusatzfeld(member.getID(), transponderDefId);
      if (current == null) current = "";
      if (current.equals(proposed)) { r.membersUnchanged++; continue; }

      pl.log("UPDATE jv_id=" + member.getID() + " " + member.getVorname() + " " + member.getName()
          + ": '" + current + "' → '" + proposed + "'");
      if (!dryRun)
      {
        try { writeZusatzfeld(member.getID(), transponderDef, proposed); r.membersUpdated++; }
        catch (Exception e) { r.errors.add("write jv_id=" + member.getID() + ": " + e.getMessage()); }
      }
      else
      {
        r.membersUpdated++;   // counted as "would update" in dry-run
      }
    }

    pl.log("=== DONE ===  updated=" + r.membersUpdated
        + " unchanged=" + r.membersUnchanged
        + " hikUnmatched=" + r.hikvisionUsersUnmatched
        + " unknownCards=" + r.unknownCards
        + " errors=" + r.errors.size());
    return r;
  }

  /** Find-or-create the Zusatzfelder row for (mitglied, felddefinition) and write the string value. */
  private static void writeZusatzfeld(String mitgliedId, Felddefinition def, String value) throws Exception
  {
    DBIterator<Zusatzfelder> it = Einstellungen.getDBService().createList(Zusatzfelder.class);
    it.addFilter("mitglied = ?", mitgliedId);
    it.addFilter("felddefinition = ?", def.getID());
    Zusatzfelder z;
    if (it.hasNext()) z = (Zusatzfelder) it.next();
    else
    {
      z = (Zusatzfelder) Einstellungen.getDBService().createObject(Zusatzfelder.class, null);
      z.setMitglied(Integer.parseInt(mitgliedId));
      z.setFelddefinition(Integer.parseInt(def.getID()));
    }
    z.setFeld(value == null || value.isEmpty() ? null : value);
    z.store();
  }
}
