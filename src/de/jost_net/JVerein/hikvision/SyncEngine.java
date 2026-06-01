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
  public interface ProgressListener
  {
    void log(String msg);
    void progress(int done, int total);
  }

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

  /**
   * Build desired state from jverein + ChipStore. Returns map: employeeNo -> Desired.
   */
  public static Map<String, Desired> buildDesired(ChipStore chips, String zusatzfeldName,
                                                  ProgressListener pl, Result r) throws Exception
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

  public static Map<String, Actual> buildActual(HikvisionClient client, ProgressListener pl)
      throws Exception
  {
    pl.log("Hikvision UserInfo abrufen…");
    JSONArray users = client.listAllUsers();
    pl.log("Hikvision CardInfo abrufen…");
    JSONArray cards = client.listAllCards();

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

  public static Result run(boolean dryRun, ProgressListener pl) throws Exception
  {
    Result r = new Result();
    r.dryRun = dryRun;

    pl.log("=== Sync " + (dryRun ? "(DRY-RUN)" : "(APPLY)") + " ===");

    ChipStore chips = ChipStore.defaultStore();
    pl.log("ChipStore: " + chips.size() + " Chip↔Kartennummer-Einträge geladen");

    HikvisionClient client = new HikvisionClient(
        HikvisionSettings.getControllerUrl(), HikvisionSettings.getControllerUser(),
        HikvisionSettings.getControllerPassword(), HikvisionSettings.getInterCallPauseMs());

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

    // --- create ---
    for (String emp : new ArrayList<>(toCreate))
    {
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
      pl.progress(++done, total);
    }

    // --- delete ---
    for (String emp : new ArrayList<>(toDelete))
    {
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
      pl.progress(++done, total);
    }

    // --- card diff in overlap (note: name/type/group changes NOT handled here) ---
    for (String emp : new ArrayList<>(overlap))
    {
      Desired d = desired.get(emp);
      Actual a = actual.get(emp);
      Set<String> add = new HashSet<>(d.cardNos); add.removeAll(a.cardNos);
      Set<String> rem = new HashSet<>(a.cardNos); rem.removeAll(d.cardNos);
      if (add.isEmpty() && rem.isEmpty()) { pl.progress(++done, total); continue; }
      pl.log("UPDATE " + emp + " " + d.name + "  +" + add + "  -" + rem);
      if (!dryRun)
      {
        for (String cn : rem) { if (client.deleteCard(cn)) r.cardsRemoved++; else r.errors.add("deleteCard " + cn); }
        for (String cn : add) { if (client.createCard(emp, cn)) r.cardsAdded++; else r.errors.add("createCard " + emp + "/" + cn); }
      }
      pl.progress(++done, total);
    }

    pl.log("=== DONE ===  created=" + r.created + " deleted=" + r.deleted
        + " cardsAdded=" + r.cardsAdded + " cardsRemoved=" + r.cardsRemoved
        + " errors=" + r.errors.size());
    if (!r.errors.isEmpty())
    {
      for (String e : r.errors) Logger.warn("sync error: " + e);
    }
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
  public static ImportResult importFromHikvision(boolean dryRun, ProgressListener pl) throws Exception
  {
    ImportResult r = new ImportResult();
    r.dryRun = dryRun;

    pl.log("=== Import " + (dryRun ? "(DRY-RUN)" : "(APPLY)") + " ===");

    ChipStore chips = ChipStore.defaultStore();
    pl.log("ChipStore: " + chips.size() + " Chip↔Kartennummer-Einträge geladen");

    HikvisionClient client = new HikvisionClient(
        HikvisionSettings.getControllerUrl(), HikvisionSettings.getControllerUser(),
        HikvisionSettings.getControllerPassword(), HikvisionSettings.getInterCallPauseMs());

    pl.log("Hikvision UserInfo abrufen…");
    JSONArray users = client.listAllUsers();
    pl.log("Hikvision CardInfo abrufen…");
    JSONArray cards = client.listAllCards();

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
      JSONObject u = users.getJSONObject(i);
      String emp = u.optString("employeeNo");
      done++; pl.progress(done, total);

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
