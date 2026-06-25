package de.jost_net.JVerein.hikvision;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
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
import de.jost_net.JVerein.rmi.Mitglied;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.logging.Logger;

/**
 * Computes the diff between jverein + {@link MitgliedAssignments} (source
 * of truth) and the Hikvision controller, and applies the diff.
 *
 * <p>v0.15 rewire: transponder + group assignments now live in the
 * plugin-owned {@link MitgliedAssignments} store instead of the jverein
 * transponder Zusatzfeld. Per-user Türrechte (regionPermissionGroupIDList)
 * are NO LONGER synced — Hikvision derives access permissions from the
 * user's group membership automatically.
 *
 * <h2>Status semantics</h2>
 * <ul>
 *   <li>{@link Status#OK} — nothing to do</li>
 *   <li>{@link Status#CREATE} — managed jverein member has assignment with
 *       at least one transponder, but no Hikvision record yet</li>
 *   <li>{@link Status#UPDATE} — card list / group / endTime differs; no
 *       enable-state flip</li>
 *   <li>{@link Status#DISABLE} — jverein austritt is set (today or past),
 *       Hikvision record is still enabled → flip {@code Valid.enable=false}
 *       and set {@code Valid.endTime=austritt}. Cards stay attached (history
 *       preservation). Applies to blackList users too — both flags coexist.</li>
 *   <li>{@link Status#REACTIVATE} — austritt cleared, Hikvision record is
 *       disabled → flip {@code Valid.enable=true}</li>
 *   <li>{@link Status#DELETE} — Hikvision record has a managed employeeNo
 *       but the corresponding jverein Mitglied has been deleted entirely
 *       (truly orphaned). Sets are uncommon — the user wanted this kept
 *       narrow so accidental austritt doesn't purge history.</li>
 *   <li>{@link Status#INCOMPLETE} — Hikvision record exists for a known
 *       jverein Mitglied, but {@link MitgliedAssignments} doesn't have an
 *       entry yet. Surfaced so the user can run migrate or assign in UI.</li>
 *   <li>{@link Status#HIK_ONLY} — non-managed employeeNo (SKM* etc.) —
 *       never touched by sync.</li>
 * </ul>
 */
public class SyncEngine
{
  /** Re-exports the top-level {@link de.jost_net.JVerein.hikvision.ProgressListener} */
  public interface ProgressListener extends de.jost_net.JVerein.hikvision.ProgressListener {}

  public static class Result
  {
    public int created;
    public int disabled;
    public int reactivated;
    public int updated;
    public int deleted;
    public int cardsAdded;
    public int cardsRemoved;
    public int cardsMoved;         // cards reclaimed from a donor not in this plan, then re-attached
    public int groupsChanged;      // org userGroup (userGroupNodeID) moves
    public int regionsChanged;     // Berechtigungsgruppen (regionPermissionGroupIDList) changes
    public int validChanged;
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
    UPDATE,     // cards / group / endTime differ — no enable flip
    DISABLE,    // austritt set today/past → enable=false
    REACTIVATE, // austritt cleared → enable=true
    DELETE,     // orphan — jverein Mitglied truly gone
    INCOMPLETE, // Hikvision has it + jverein has Mitglied, but no store assignment
    HIK_ONLY    // non-managed employeeNo (SKM* etc.) — never touched
  }

  public static class PlanRow
  {
    public String employeeNo;
    public String name;
    public String userType;        // normal / visitor / blackList — preserved as-is
    public String groupName;       // current (Hikvision side)
    public String groupId;
    public String desiredGroupName;
    public String desiredGroupId;
    // inputs for the automatic org-group rule (resolved once current is known)
    public boolean isSponsor;
    public boolean groupManaged;       // user explicitly chose the Org-Gruppe
    public String storedGroupName = "";// the assignment's chosen group (only used when groupManaged)
    public List<String> currentCards = new ArrayList<>();
    public List<String> desiredCards = new ArrayList<>();
    /** Door-access region-permission groups. current = what's on the
     *  controller's regionPermissionGroupIDList; desired = resolved from the
     *  member's MitgliedAssignments mapping. Names are carried alongside the
     *  ids for display / cache. */
    public List<Integer> currentRegionIds = new ArrayList<>();
    public List<Integer> desiredRegionIds = new ArrayList<>();
    public List<String> desiredRegionNames = new ArrayList<>();
    public boolean currentEnabled = true;
    public Date currentValidEnd;            // null if Hikvision has no record or far-future sentinel
    public boolean desiredEnabled = true;
    public Date desiredValidEnd;            // = austritt, null = no end
    public Status status;
    public String detail = "";
    public String jvereinName = "";
    /** Derived: controller is actively blocking this user on an expired
     *  validity window (enable=true + endTime in the past) — typically a
     *  departed member kept on the controller for swipe-history. Recomputed
     *  from current* via {@link #computeAccessEnded}; not part of the sync
     *  action {@link #status}. */
    public boolean accessEnded = false;
  }

  public static class Plan
  {
    public final List<PlanRow> rows = new ArrayList<>();
    public int ok, create, update, disable, reactivate, delete, incomplete, hikOnly;
    public int unknownCards;
    public int membersSkipped;
    /** Total counts the controller reported during the fetch that built
     *  this plan. Used by the count-probe drift check on incremental
     *  refreshes. -1 = not recorded (scoped/legacy plan). */
    public int userTotal = -1;
    public int cardTotal = -1;
  }

  // ============================================================ ISO/Date helpers

  private static final SimpleDateFormat ISO_DT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
  private static final SimpleDateFormat ISO_D  = new SimpleDateFormat("yyyy-MM-dd");
  /** Hikvision treats any endTime >= this as "no expiry" effectively. */
  private static final int FAR_FUTURE_YEAR = 2037;

  /** Format jverein Date → Hikvision ISO endTime. Null/far-future → max sentinel. */
  static String toHikvisionEndTime(Date d)
  {
    if (d == null) return "2037-12-31T23:59:59";
    Calendar cal = Calendar.getInstance();
    cal.setTime(d);
    cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59);
    cal.set(Calendar.SECOND, 59);       cal.set(Calendar.MILLISECOND, 0);
    return ISO_DT.format(cal.getTime());
  }

  /** Parse Hikvision Valid.endTime → Date. Far-future sentinels → null. */
  static Date parseValidEnd(String s)
  {
    if (s == null || s.isEmpty()) return null;
    try
    {
      Date d = ISO_DT.parse(s);
      Calendar cal = Calendar.getInstance(); cal.setTime(d);
      if (cal.get(Calendar.YEAR) >= FAR_FUTURE_YEAR) return null;
      return d;
    }
    catch (Exception e)
    { Logger.warn("Konnte Hikvision endTime nicht parsen: '" + s + "': " + e.getMessage()); return null; }
  }

  /** Day-level equality so a 23:59:59 vs 00:00:00 mismatch on the same day doesn't trigger UPDATE. */
  static boolean sameDay(Date a, Date b)
  {
    if (a == null && b == null) return true;
    if (a == null || b == null) return false;
    return ISO_D.format(a).equals(ISO_D.format(b));
  }

  /** Seed the name→UUID map with the configured member / sponsor default
   *  groups so they resolve even on a brand-new install whose catalog cache is
   *  still empty (the two groups always have a UUID in the Einstellungen). */
  private static void seedSettingsGroups(Map<String, String> uuidByGroupName)
  {
    putIfBoth(uuidByGroupName, HikvisionSettings.getMemberGroupName(),  HikvisionSettings.getMemberGroupId());
    putIfBoth(uuidByGroupName, HikvisionSettings.getSponsorGroupName(), HikvisionSettings.getSponsorGroupId());
  }

  private static void putIfBoth(Map<String, String> m, String name, String uuid)
  {
    if (name != null && !name.isEmpty() && uuid != null && !uuid.isEmpty()) m.putIfAbsent(name, uuid);
  }

  /** True when an org group we might need to assign a member <i>to</i> can't be
   *  resolved to a UUID yet: the configured member-default / sponsor groups, or
   *  a managed assignment's chosen group. A member's <i>current</i> group always
   *  resolves from the live UserInfo, so it isn't checked here. Only an empty
   *  target group — one that never appears on any live user and isn't cached —
   *  trips this, which is when we fetch the org-group list on the fly. */
  private static boolean anyAutoTargetGroupUnresolved(MitgliedAssignments asn,
      Map<String, String> uuidByGroupName)
  {
    if (groupUnresolved(HikvisionSettings.getMemberGroupName(),  uuidByGroupName)) return true;
    if (groupUnresolved(HikvisionSettings.getSponsorGroupName(), uuidByGroupName)) return true;
    for (MitgliedAssignments.Assignment a : asn.all())
      if (a.groupManaged && groupUnresolved(a.hikvisionGroup, uuidByGroupName)) return true;
    return false;
  }

  private static boolean groupUnresolved(String name, Map<String, String> uuidByGroupName)
  {
    return name != null && !name.isEmpty() && !uuidByGroupName.containsKey(name);
  }

  /** Desired org userGroup name for a row.
   *  <ul>
   *    <li>explicitly managed → the user's chosen group;</li>
   *    <li>sponsor → the configured guest group (BSV);</li>
   *    <li>member currently in the guest group → the member default (Mitglieder);</li>
   *    <li>member already in a member group (Mitglieder/Vorstand/…) → unchanged;</li>
   *    <li>new member with no controller record → member default.</li>
   *  </ul>
   *  This flags only guest/member boundary violations, never legitimate
   *  Vorstand/Robby-Bubble membership. */
  private static String autoGroupName(boolean managed, String storedGroup, boolean sponsor, String currentGroup)
  {
    if (managed && storedGroup != null && !storedGroup.isEmpty()) return storedGroup;
    String guestGroup  = HikvisionSettings.getSponsorGroupName();
    String memberGroup = HikvisionSettings.getMemberGroupName();
    if (sponsor) return guestGroup;
    if (currentGroup == null || currentGroup.isEmpty()) return memberGroup;   // new/unknown
    if (currentGroup.equals(guestGroup)) return memberGroup;                  // member in guest group → move
    return currentGroup;                                                      // already a member group → keep
  }

  /** org userGroup name → UUID lookup, for resolving a Mitglied's assigned
   *  Organisationsgruppe to the controller's userGroupNodeID. */
  private static Map<String, String> uuidByGroupName(HikvisionGroupCatalog cat)
  {
    Map<String, String> m = new HashMap<>();
    for (HikvisionGroupCatalog.Group g : cat.groups)
      if (g.name != null && !g.name.isEmpty() && g.uuid != null && !g.uuid.isEmpty())
        m.put(g.name, g.uuid);
    return m;
  }

  /** regionPermissionGroupName → id lookup from a catalog, for resolving a
   *  Mitglied's assigned Berechtigungsgruppen names to controller ids. */
  private static Map<String, Integer> regionIdByName(HikvisionGroupCatalog cat)
  {
    Map<String, Integer> m = new HashMap<>();
    for (HikvisionGroupCatalog.RegionPermissionGroup r : cat.regions)
      if (r.name != null && !r.name.isEmpty()) m.put(r.name, r.id);
    return m;
  }

  /** id → regionPermissionGroupName lookup, for rendering current ids. */
  private static Map<Integer, String> regionNameById(HikvisionGroupCatalog cat)
  {
    Map<Integer, String> m = new HashMap<>();
    for (HikvisionGroupCatalog.RegionPermissionGroup r : cat.regions)
      m.put(r.id, r.name == null || r.name.isEmpty() ? ("Gruppe " + r.id) : r.name);
    return m;
  }

  /** Resolve a member's desired Berechtigungsgruppen names → controller ids.
   *  Unresolvable names are logged and dropped (never silently granted). */
  private static List<Integer> resolveRegionIds(List<String> names, Map<String, Integer> idByName,
      String who, de.jost_net.JVerein.hikvision.ProgressListener pl)
  {
    List<Integer> ids = new ArrayList<>();
    if (names == null) return ids;
    for (String nm : names)
    {
      if (nm == null || nm.trim().isEmpty()) continue;
      Integer id = idByName.get(nm.trim());
      if (id == null)
      { if (pl != null) pl.log("WARN " + who + ": Berechtigungsgruppe '" + nm
          + "' nicht auflösbar — wird übersprungen"); continue; }
      if (!ids.contains(id)) ids.add(id);
    }
    return ids;
  }

  // ============================================================ computePlan

  public static Plan computePlan(ChipStore chips, HikvisionClient client,
                                 de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    Plan plan = new Plan();

    MitgliedAssignments asn = MitgliedAssignments.load();
    pl.log("MitgliedAssignments: " + asn.size() + " Zuweisungen geladen");

    // Groups + Berechtigungsgruppen come from the local catalog CACHE — not a
    // controller fetch. The org-group list is refreshed on demand in the
    // Organisationsgruppen view, the Berechtigungsgruppen list in the
    // Berechtigungsgruppen view (or both via the Einstellungen). The name→UUID
    // map is augmented below from the live UserInfo records; if a needed org
    // group still can't be resolved we fetch the group list once, on the fly.
    HikvisionGroupCatalog cat = HikvisionGroupCatalog.fromCache();
    Map<String, String>  uuidByGroupName = uuidByGroupName(cat);
    Map<String, Integer> regionIdByName  = regionIdByName(cat);
    Map<Integer, String> regionNameById  = regionNameById(cat);
    seedSettingsGroups(uuidByGroupName);
    if (cat.regions.isEmpty())
      pl.log("Hinweis: Berechtigungsgruppen-Cache leer — in der Berechtigungsgruppen-Sicht "
          + "'Aktualisieren' klicken, falls Türrechte zugewiesen werden sollen.");

    // Index jverein members
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

    // Build desired rows from MitgliedAssignments + jverein
    Map<String, PlanRow> desired = new TreeMap<>();
    for (MitgliedAssignments.Assignment a : asn.all())
    {
      Mitglied m = jvById.get(a.jvId);
      if (m == null) continue;     // assignment exists but jverein Mitglied gone — handled in Hikvision-walk

      Identity id = Identity.of(m);
      PlanRow row = new PlanRow();
      row.employeeNo = id.employeeNo;
      row.name = (safe(m.getVorname()) + " " + safe(m.getName())).trim();
      row.userType = id.isSponsor ? "visitor" : "normal";    // default; preserved from Hikvision side if record exists
      // org userGroup: decided once the controller's current group is known
      // (overlay / CREATE branch below). Capture the inputs here.
      row.isSponsor = id.isSponsor;
      row.groupManaged = a.groupManaged;
      row.storedGroupName = a.hikvisionGroup == null ? "" : a.hikvisionGroup;
      // door access = Berechtigungsgruppen mapped per member (default none)
      row.desiredRegionNames = new ArrayList<>(a.regionPermissionGroups);
      row.desiredRegionIds = resolveRegionIds(row.desiredRegionNames, regionIdByName,
          row.name + " (jv_id=" + m.getID() + ")", pl);

      for (String chip : a.transponder)
      {
        String cardNo = chips.cardForChip(chip);
        if (cardNo == null)
        {
          plan.unknownCards++;
          pl.log("WARN: chip '" + chip + "' (jv_id=" + m.getID() + " " + row.name + ") nicht im ChipStore");
          continue;
        }
        row.desiredCards.add(cardNo);
      }

      // Hikvision Valid semantics: enable=true → enforce time restriction
      // (deny outside beginTime/endTime); enable=false → no restriction
      // (always allow, modulo group permissions). For active members we
      // don't enforce a restriction (desiredValidEnd=null). For departed
      // members we want enforcement so the controller auto-blocks after
      // the austritt date.
      Date austritt = m.getAustritt();
      row.desiredValidEnd = austritt;
      row.desiredEnabled = (austritt != null);
      row.jvereinName = row.name;

      desired.put(id.employeeNo, row);
    }

    // Pull Hikvision actual state. Users FIRST — each UserInfo carries its org
    // group inline (userGroupNodeID / userGroupNodeName), so we resolve group
    // names → UUIDs from the live users rather than a separate group fetch.
    pl.log("Hikvision UserInfo abrufen…");
    JSONArray users = client.listAllUsers(pl);
    plan.userTotal = users.length();

    // Index users by *canonical* employeeNo so leading-zero variants
    // (e.g. "0497") match jverein-derived ids ("497"); the literal employeeNo
    // stays in the UserInfo JSON for write operations. Augment the name→UUID
    // map from each user's inline group so any member-bearing group resolves.
    Map<String, JSONObject> actualByEmp = new TreeMap<>();
    for (int i = 0; i < users.length(); i++)
    {
      JSONObject u = users.getJSONObject(i);
      actualByEmp.put(Identity.canonical(u.optString("employeeNo")), u);
      String gid = u.optString("userGroupNodeID", "");
      String gnm = u.optString("userGroupNodeName", "");
      if (!gid.isEmpty() && !gnm.isEmpty()) uuidByGroupName.putIfAbsent(gnm, gid);
    }

    // Only if a group we actually need to assign someone TO (member default,
    // sponsor group, or a managed stored group) still isn't resolvable — i.e.
    // an empty group that never appears on a live user and isn't cached — do we
    // fetch the org-group list, once.
    if (anyAutoTargetGroupUnresolved(asn, uuidByGroupName))
    {
      pl.log("Unbekannte Organisationsgruppe — lade Gruppenliste vom Controller…");
      HikvisionGroupCatalog fresh = HikvisionGroupCatalog.refreshFromHikvision(client, pl, true, false);
      uuidByGroupName = uuidByGroupName(fresh);
      seedSettingsGroups(uuidByGroupName);
      for (JSONObject u : actualByEmp.values())
      {
        String gid = u.optString("userGroupNodeID", "");
        String gnm = u.optString("userGroupNodeName", "");
        if (!gid.isEmpty() && !gnm.isEmpty()) uuidByGroupName.putIfAbsent(gnm, gid);
      }
    }

    // Cards: fetch ONLY for managed users (scoped by employeeNo). Unmanaged
    // entries (SKM* …) are never synced, so their cards aren't part of the
    // diff. Use the literal controller employeeNo so leading-zero forms match.
    List<String> managedEmps = new ArrayList<>();
    for (Map.Entry<String, JSONObject> e : actualByEmp.entrySet())
      if (Identity.isManaged(e.getKey())) managedEmps.add(e.getValue().optString("employeeNo"));
    pl.log("Hikvision CardInfo abrufen (nur " + managedEmps.size() + " verwaltete Benutzer)…");
    JSONArray cards = client.listCards(managedEmps, pl);
    // cardTotal must reflect the controller's TRUE total (cheap O(1) probe), not
    // the scoped count — the incremental refresh's drift check compares it
    // against client.getTotalCards() and would otherwise always escalate.
    try { plan.cardTotal = client.getTotalCards(); }
    catch (Exception ex) { plan.cardTotal = cards.length(); }

    Map<String, List<String>> cardsByEmp = new HashMap<>();
    for (int i = 0; i < cards.length(); i++)
    {
      JSONObject c = cards.getJSONObject(i);
      cardsByEmp.computeIfAbsent(Identity.canonical(c.optString("employeeNo")), k -> new ArrayList<>())
                .add(c.optString("cardNo"));
    }

    // Walk every Hikvision entry first (so the table shows everything)
    for (Map.Entry<String, JSONObject> e : actualByEmp.entrySet())
    {
      String empCanonical = e.getKey();
      JSONObject u = e.getValue();
      String empLiteral = u.optString("employeeNo");   // literal form for writes
      List<String> cur = cardsByEmp.getOrDefault(empCanonical, new ArrayList<>());

      if (!Identity.isManaged(empCanonical))
      {
        PlanRow row = rowFromActual(empLiteral, u, cur);
        row.status = Status.HIK_ONLY;
        row.detail = "unverwalteter employeeNo (z.B. SKM*) — wird bei Sync nie angefasst";
        plan.rows.add(row); plan.hikOnly++;
        continue;
      }

      PlanRow d = desired.remove(empCanonical);
      if (d == null)
      {
        // Hikvision-managed entry with no assignment in our store
        PlanRow row = rowFromActual(empLiteral, u, cur);
        Mitglied m = lookupByEmp(empCanonical, jvByExterne, jvById);
        if (m == null)
        {
          row.status = Status.DELETE;
          row.detail = "kein zugehöriges jverein-Mitglied — verwaister Eintrag";
          plan.rows.add(row); plan.delete++;
        }
        else
        {
          row.status = Status.INCOMPLETE;
          row.jvereinName = (safe(m.getVorname()) + " " + safe(m.getName())).trim();
          row.detail = "jverein-Mitglied vorhanden, aber keine MitgliedAssignments-Zuweisung — "
              + "bitte Migration ausführen oder im UI zuweisen";
          plan.rows.add(row); plan.incomplete++;
        }
        continue;
      }

      // Overlay actual Hikvision state on the desired row. employeeNo
      // gets the literal Hikvision value (e.g. "0497") so subsequent
      // write calls hit the existing record instead of trying to write
      // to the canonical form ("497") which doesn't exist on the
      // controller.
      d.employeeNo = empLiteral;
      d.currentCards = cur;
      d.groupId = u.optString("userGroupNodeID", "");
      d.groupName = u.optString("userGroupNodeName", "");
      d.currentRegionIds = regionIdsOf(u);
      d.desiredGroupName = autoGroupName(d.groupManaged, d.storedGroupName, d.isSponsor, d.groupName);
      d.desiredGroupId = uuidByGroupName.get(d.desiredGroupName);
      d.userType = u.optString("userType", d.userType);  // preserve actual (blackList etc.)
      JSONObject vl = u.optJSONObject("Valid");
      if (vl != null)
      {
        d.currentEnabled = vl.optBoolean("enable", true);
        d.currentValidEnd = parseValidEnd(vl.optString("endTime", ""));
      }

      assignStatusAndDetail(d, chips, regionNameById);
      plan.rows.add(d);
      switch (d.status)
      {
        case OK:         plan.ok++;         break;
        case UPDATE:     plan.update++;     break;
        case DISABLE:    plan.disable++;    break;
        case REACTIVATE: plan.reactivate++; break;
        default: break;
      }
    }

    // Anything left in desired → CREATE (or skip if no transponder)
    for (PlanRow row : desired.values())
    {
      if (row.desiredCards.isEmpty())
      { plan.membersSkipped++; continue; }    // no transponder → no Hikvision record
      row.desiredGroupName = autoGroupName(row.groupManaged, row.storedGroupName, row.isSponsor, "");
      row.desiredGroupId = uuidByGroupName.get(row.desiredGroupName);
      row.status = Status.CREATE;
      row.detail = "neu auf Hikvision anlegen — Gruppe " + row.desiredGroupName
          + ", " + row.desiredCards.size() + " Karte(n)"
          + (row.desiredRegionNames.isEmpty() ? "" : ", Berechtigung: " + String.join(",", row.desiredRegionNames))
          + (row.desiredValidEnd != null ? ", endTime=" + ISO_D.format(row.desiredValidEnd) : "");
      plan.rows.add(row); plan.create++;
    }

    pl.log("Plan: ok=" + plan.ok + " neu=" + plan.create + " geändert=" + plan.update
        + " deaktivieren=" + plan.disable + " reaktivieren=" + plan.reactivate
        + " löschen=" + plan.delete + " unvollständig=" + plan.incomplete
        + " hik-only=" + plan.hikOnly
        + " | übersprungen=" + plan.membersSkipped + " unbekannte Transponder=" + plan.unknownCards);

    for (PlanRow r : plan.rows) r.accessEnded = computeAccessEnded(r);
    PlanCache.save(plan);
    // The group catalog is owned by the Organisationsgruppen / Berechtigungs-
    // gruppen views now — a plain refresh doesn't overwrite it (the on-the-fly
    // org-group fallback above already saved any freshly-fetched groups).
    return plan;
  }

  // ============================================================ computePlanFor

  /**
   * Scoped variant of {@link #computePlan}: re-classifies only the given
   * canonical employeeNos by hitting the Hikvision controller with
   * {@code EmployeeNoList}-filtered Search calls (typically ~200ms total
   * vs ~80s for a full refresh on ~560 users). Rows for employeeNos
   * outside {@code scope} are carried over from {@code cachedBase} unchanged.
   *
   * <p>What the caller is expected to seed into {@code scope}:
   * <ul>
   *   <li>employeeNos of non-OK rows in the cached plan (still actionable)</li>
   *   <li>employeeNos derived from recently-edited MitgliedAssignments
   *       (Identity.of(m).employeeNo, including new-CREATE candidates)</li>
   *   <li>any specific employeeNos the user asked to refresh
   *       (e.g. "Sichtbare aktualisieren")</li>
   * </ul>
   *
   * <p>Counter semantics: {@code ok/create/update/...} are recomputed from
   * the merged row set at the end. {@code unknownCards} reflects only
   * what was seen in the scoped pass (chips in scope-Assignments with no
   * ChipStore mapping); it's not carried over from {@code cachedBase}.
   *
   * <p>This does NOT touch the {@link MitgliedAssignments#getLastFullRefresh}
   * marker — only a true full refresh does.
   */
  public static Plan computePlanFor(java.util.Set<String> scope, Plan cachedBase,
      ChipStore chips, HikvisionClient client,
      de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    if (cachedBase == null) throw new IllegalArgumentException("cachedBase required for scoped refresh");
    if (scope == null || scope.isEmpty())
    {
      pl.log("Scoped refresh: leerer Scope — nichts zu tun.");
      return cachedBase;
    }
    pl.log("Scoped refresh: " + scope.size() + " employeeNo(s) werden aktualisiert");

    MitgliedAssignments asn = MitgliedAssignments.load();
    // Scoped refresh is the fast path — read groups from the cached catalog
    // (last full refresh / Settings "laden") rather than hitting the
    // controller. A new region group only needs a full refresh to appear.
    HikvisionGroupCatalog cat = HikvisionGroupCatalog.fromCache();
    Map<String, String>  uuidByGroupName = uuidByGroupName(cat);
    Map<String, Integer> regionIdByName  = regionIdByName(cat);
    Map<Integer, String> regionNameById  = regionNameById(cat);

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

    int scopedUnknownCards = 0;
    Map<String, PlanRow> desired = new TreeMap<>();
    for (MitgliedAssignments.Assignment a : asn.all())
    {
      Mitglied m = jvById.get(a.jvId);
      if (m == null) continue;
      Identity id = Identity.of(m);
      if (!scope.contains(id.employeeNo)) continue;     // out of scope
      PlanRow row = new PlanRow();
      row.employeeNo = id.employeeNo;
      row.name = (safe(m.getVorname()) + " " + safe(m.getName())).trim();
      row.userType = id.isSponsor ? "visitor" : "normal";
      row.isSponsor = id.isSponsor;
      row.groupManaged = a.groupManaged;
      row.storedGroupName = a.hikvisionGroup == null ? "" : a.hikvisionGroup;
      row.desiredRegionNames = new ArrayList<>(a.regionPermissionGroups);
      row.desiredRegionIds = resolveRegionIds(row.desiredRegionNames, regionIdByName,
          row.name + " (jv_id=" + m.getID() + ")", pl);
      for (String chip : a.transponder)
      {
        String cardNo = chips.cardForChip(chip);
        if (cardNo == null)
        { scopedUnknownCards++;
          pl.log("WARN: chip '" + chip + "' (jv_id=" + m.getID() + " " + row.name + ") nicht im ChipStore");
          continue; }
        row.desiredCards.add(cardNo);
      }
      Date austritt = m.getAustritt();
      row.desiredValidEnd = austritt;
      row.desiredEnabled = (austritt != null);
      row.jvereinName = row.name;
      desired.put(id.employeeNo, row);
    }

    pl.log("Hikvision UserInfo (scoped) abrufen…");
    JSONArray users = client.listUsers(scope, pl);
    pl.log("Hikvision CardInfo (scoped) abrufen…");
    JSONArray cards = client.listCards(scope, pl);

    Map<String, List<String>> cardsByEmp = new HashMap<>();
    for (int i = 0; i < cards.length(); i++)
    {
      JSONObject c = cards.getJSONObject(i);
      cardsByEmp.computeIfAbsent(Identity.canonical(c.optString("employeeNo")), k -> new ArrayList<>())
                .add(c.optString("cardNo"));
    }
    Map<String, JSONObject> actualByEmp = new TreeMap<>();
    for (int i = 0; i < users.length(); i++)
    {
      JSONObject u = users.getJSONObject(i);
      actualByEmp.put(Identity.canonical(u.optString("employeeNo")), u);
    }

    // Build new rows for every employeeNo in scope (some will end up with
    // no row at all — see "no row to emit" branch below).
    Map<String, PlanRow> newRowsByEmp = new java.util.HashMap<>();
    for (String emp : scope)
    {
      JSONObject u = actualByEmp.get(emp);
      List<String> cur = cardsByEmp.getOrDefault(emp, new ArrayList<>());

      if (u != null)
      {
        String empLiteral = u.optString("employeeNo");
        if (!Identity.isManaged(emp))
        {
          PlanRow row = rowFromActual(empLiteral, u, cur);
          row.status = Status.HIK_ONLY;
          row.detail = "unverwalteter employeeNo (z.B. SKM*) — wird bei Sync nie angefasst";
          newRowsByEmp.put(emp, row);
          continue;
        }
        PlanRow d = desired.remove(emp);
        if (d == null)
        {
          PlanRow row = rowFromActual(empLiteral, u, cur);
          Mitglied m = lookupByEmp(emp, jvByExterne, jvById);
          if (m == null)
          { row.status = Status.DELETE;
            row.detail = "kein zugehöriges jverein-Mitglied — verwaister Eintrag"; }
          else
          { row.status = Status.INCOMPLETE;
            row.jvereinName = (safe(m.getVorname()) + " " + safe(m.getName())).trim();
            row.detail = "jverein-Mitglied vorhanden, aber keine MitgliedAssignments-Zuweisung — "
                + "bitte Migration ausführen oder im UI zuweisen"; }
          newRowsByEmp.put(emp, row);
          continue;
        }
        d.employeeNo = empLiteral;
        d.currentCards = cur;
        d.groupId = u.optString("userGroupNodeID", "");
        d.groupName = u.optString("userGroupNodeName", "");
        d.currentRegionIds = regionIdsOf(u);
        d.desiredGroupName = autoGroupName(d.groupManaged, d.storedGroupName, d.isSponsor, d.groupName);
        d.desiredGroupId = uuidByGroupName.get(d.desiredGroupName);
        d.userType = u.optString("userType", d.userType);
        JSONObject vl = u.optJSONObject("Valid");
        if (vl != null)
        { d.currentEnabled = vl.optBoolean("enable", true);
          d.currentValidEnd = parseValidEnd(vl.optString("endTime", "")); }
        assignStatusAndDetail(d, chips, regionNameById);
        newRowsByEmp.put(emp, d);
      }
      else
      {
        // No Hikvision record. Either CREATE candidate or no row at all.
        PlanRow d = desired.remove(emp);
        if (d == null) continue;                  // emp dropped from both sides — no row
        if (d.desiredCards.isEmpty()) continue;   // assignment exists but no cards — skip
        d.desiredGroupName = autoGroupName(d.groupManaged, d.storedGroupName, d.isSponsor, "");
        d.desiredGroupId = uuidByGroupName.get(d.desiredGroupName);
        d.status = Status.CREATE;
        d.detail = "neu auf Hikvision anlegen — Gruppe " + d.desiredGroupName
            + ", " + d.desiredCards.size() + " Karte(n)"
            + (d.desiredRegionNames.isEmpty() ? "" : ", Berechtigung: " + String.join(",", d.desiredRegionNames))
            + (d.desiredValidEnd != null ? ", endTime=" + ISO_D.format(d.desiredValidEnd) : "");
        newRowsByEmp.put(emp, d);
      }
    }

    // Merge: take cachedBase rows, replace any whose employeeNo is in
    // scope with the new row (or drop if scope yielded no row). Add any
    // scope rows that weren't previously in cachedBase.
    Plan merged = new Plan();
    java.util.Set<String> placed = new java.util.HashSet<>();
    for (PlanRow r : cachedBase.rows)
    {
      String canon = Identity.canonical(r.employeeNo);
      if (scope.contains(canon))
      {
        PlanRow nr = newRowsByEmp.get(canon);
        if (nr != null) { merged.rows.add(nr); placed.add(canon); }
        // else: scope refreshed and yielded no row — row is dropped
      }
      else
      {
        merged.rows.add(r);
      }
    }
    for (Map.Entry<String, PlanRow> e : newRowsByEmp.entrySet())
      if (!placed.contains(e.getKey())) merged.rows.add(e.getValue());

    // Recompute summary counters
    for (PlanRow r : merged.rows) tally(merged, r.status);
    merged.unknownCards = scopedUnknownCards;
    merged.membersSkipped = cachedBase.membersSkipped;     // not re-derivable from scoped pass
    merged.userTotal = cachedBase.userTotal;               // last full-refresh totals carry forward
    merged.cardTotal = cachedBase.cardTotal;

    pl.log("Plan (scoped merge): ok=" + merged.ok + " neu=" + merged.create
        + " geändert=" + merged.update + " deaktivieren=" + merged.disable
        + " reaktivieren=" + merged.reactivate + " löschen=" + merged.delete
        + " unvollständig=" + merged.incomplete + " hik-only=" + merged.hikOnly
        + " | unbekannte Transponder (Scope)=" + merged.unknownCards);

    for (PlanRow r : merged.rows) r.accessEnded = computeAccessEnded(r);
    PlanCache.save(merged);
    return merged;
  }

  private static void tally(Plan p, Status s)
  {
    if (s == null) return;
    switch (s)
    {
      case OK:         p.ok++;         break;
      case CREATE:     p.create++;     break;
      case UPDATE:     p.update++;     break;
      case DISABLE:    p.disable++;    break;
      case REACTIVATE: p.reactivate++; break;
      case DELETE:     p.delete++;     break;
      case INCOMPLETE: p.incomplete++; break;
      case HIK_ONLY:   p.hikOnly++;    break;
    }
  }

  /** Recompute the summary counters from the current row statuses. */
  public static void recount(Plan p)
  {
    p.ok = p.create = p.update = p.disable = p.reactivate = p.delete = p.incomplete = p.hikOnly = 0;
    for (PlanRow r : p.rows) tally(p, r.status);
  }

  /**
   * Offline update of one Mitglied's cached plan row after an in-UI
   * assignment edit — recomputes the desired side (Org-Gruppe,
   * Berechtigungsgruppen, transponder, validity) and re-diffs it against the
   * row's already-known controller state, WITHOUT contacting the controller.
   * Lets the Benutzer view reflect an edit immediately (and keeps the cache
   * alive) instead of invalidating it and depending on a working refresh.
   *
   * <p>Returns true if a matching row was updated. Rows that aren't
   * jverein-desired-driven (HIK_ONLY/DELETE) keep their status; CREATE rows
   * stay CREATE with a refreshed detail; everything else is re-diffed.
   */
  public static boolean recomputeRowOffline(Plan plan, Mitglied m, ChipStore chips) throws Exception
  {
    if (plan == null || m == null) return false;
    Identity id = Identity.of(m);
    String canon = Identity.canonical(id.employeeNo);
    PlanRow d = null;
    for (PlanRow r : plan.rows)
      if (r.employeeNo != null && Identity.canonical(r.employeeNo).equals(canon)) { d = r; break; }
    if (d == null) return false;   // not in the cached plan → will appear on next refresh

    MitgliedAssignments.Assignment a;
    try { a = MitgliedAssignments.load().get(m.getID()); }
    catch (Exception e) { Logger.warn("recomputeRowOffline: assignment load failed: " + e.getMessage()); return false; }

    HikvisionGroupCatalog cat = HikvisionGroupCatalog.fromCache();
    Map<String, String>  uuidByGroupName = uuidByGroupName(cat);
    Map<String, Integer> regionIdByName  = regionIdByName(cat);
    Map<Integer, String> regionNameById  = regionNameById(cat);

    d.isSponsor = id.isSponsor;
    d.groupManaged = a != null && a.groupManaged;
    d.storedGroupName = (a == null || a.hikvisionGroup == null) ? "" : a.hikvisionGroup;
    d.desiredGroupName = autoGroupName(d.groupManaged, d.storedGroupName, d.isSponsor, d.groupName);
    d.desiredGroupId = uuidByGroupName.get(d.desiredGroupName);

    d.desiredRegionNames = (a == null) ? new ArrayList<>() : new ArrayList<>(a.regionPermissionGroups);
    d.desiredRegionIds = resolveRegionIds(d.desiredRegionNames, regionIdByName, d.name, null);

    List<String> desiredCards = new ArrayList<>();
    if (a != null) for (String chip : a.transponder)
    { String c = chips.cardForChip(chip); if (c != null) desiredCards.add(c); }
    d.desiredCards = desiredCards;

    Date austritt = m.getAustritt();
    d.desiredValidEnd = austritt;
    d.desiredEnabled = (austritt != null);

    if (d.status == Status.HIK_ONLY || d.status == Status.DELETE) return true;   // not desired-driven
    if (d.status == Status.CREATE)
    {
      d.detail = "neu auf Hikvision anlegen — Gruppe " + d.desiredGroupName
          + (d.desiredRegionNames.isEmpty() ? "" : ", Berechtigung: " + String.join(",", d.desiredRegionNames))
          + ", " + d.desiredCards.size() + " Karte(n)";
      return true;
    }
    assignStatusAndDetail(d, chips, regionNameById);   // OK / UPDATE / DISABLE / REACTIVATE / (ex-)INCOMPLETE
    return true;
  }

  /** Decide DISABLE / REACTIVATE / UPDATE / OK for a row where both sides exist.
   *
   *  Rules (Hikvision Valid semantics):
   *  - austritt set → want enable=true + endTime=austritt enforced. If
   *    already in place → OK (modulo cards/Berechtigung); else DISABLE.
   *  - no austritt → want NO active block. If Hikvision currently blocks
   *    via enable=true + past endTime → REACTIVATE; otherwise leave
   *    enable/endTime alone and treat as OK (modulo cards/Berechtigung).
   *
   *  Both the org userGroup ({@code userGroupNodeID}) and the door
   *  Berechtigungsgruppen ({@code regionPermissionGroupIDList}) are diffed
   *  here from the per-member assignment and propagated on UPDATE.
   */
  private static void assignStatusAndDetail(PlanRow d, ChipStore chips, Map<Integer, String> regionNameById)
  {
    Set<String> add = new HashSet<>(d.desiredCards); add.removeAll(d.currentCards);
    Set<String> rem = new HashSet<>(d.currentCards); rem.removeAll(d.desiredCards);
    boolean cardsDiffer = !add.isEmpty() || !rem.isEmpty();

    // Empty desired Berechtigungsgruppen = "not managed here": leave whatever
    // the controller has alone (don't flag, don't sync). A member only
    // becomes region-managed once at least one group is assigned in the UI.
    boolean regionsManaged = !d.desiredRegionIds.isEmpty();
    Set<Integer> addR = new HashSet<>(d.desiredRegionIds); addR.removeAll(d.currentRegionIds);
    Set<Integer> remR = new HashSet<>(d.currentRegionIds); remR.removeAll(d.desiredRegionIds);
    boolean regionsDiffer = regionsManaged && (!addR.isEmpty() || !remR.isEmpty());
    if (!regionsManaged) { addR.clear(); remR.clear(); }   // don't render region detail when unmanaged

    boolean groupDiffers = d.desiredGroupId != null && !d.desiredGroupId.isEmpty()
        && !d.desiredGroupId.equals(d.groupId);

    StringBuilder det = new StringBuilder();

    if (d.desiredValidEnd != null)
    {
      // austritt is set — want time restriction enforcing on that day
      boolean restrictionAlreadyCorrect = d.currentEnabled && sameDay(d.currentValidEnd, d.desiredValidEnd);
      if (!restrictionAlreadyCorrect)
      {
        d.status = Status.DISABLE;
        det.append("Zugang sperren (endTime → ").append(ISO_D.format(d.desiredValidEnd)).append(")");
        appendCardDetail(det, add, rem, chips);
        appendRegionDetail(det, addR, remR, regionNameById);
        if (groupDiffers) { sep(det); det.append("Gruppe → ").append(d.desiredGroupName); }
        d.detail = det.toString();
        return;
      }
      // austritt enforcement already in place → fall through to OK/UPDATE
    }
    else
    {
      // no austritt — check whether Hikvision is blocking via a past endTime
      java.util.Date today = new java.util.Date();
      boolean blockedByPastDate = d.currentEnabled && d.currentValidEnd != null
                                  && d.currentValidEnd.before(today);
      if (blockedByPastDate)
      {
        d.status = Status.REACTIVATE;
        det.append("Sperre entfernen (endTime ").append(ISO_D.format(d.currentValidEnd)).append(" → unbeschränkt)");
        appendCardDetail(det, add, rem, chips);
        appendRegionDetail(det, addR, remR, regionNameById);
        if (groupDiffers) { sep(det); det.append("Gruppe → ").append(d.desiredGroupName); }
        d.detail = det.toString();
        return;
      }
    }

    // Neither status-flip case applies → OK or UPDATE based on cards/Berechtigung/Gruppe
    if (cardsDiffer || regionsDiffer || groupDiffers)
    {
      d.status = Status.UPDATE;
      if (!add.isEmpty()) det.append("hinzu: ").append(renderCardsForDetail(add, chips));
      if (!rem.isEmpty()) { sep(det); det.append("entfernen: ").append(renderCardsForDetail(rem, chips)); }
      appendRegionDetail(det, addR, remR, regionNameById);
      if (groupDiffers) { sep(det); det.append("Gruppe → ").append(d.desiredGroupName); }
    }
    else
    {
      d.status = Status.OK;
      det.append("in sync");
    }
    d.detail = det.toString();
  }

  private static void sep(StringBuilder b) { if (b.length() > 0) b.append("  "); }

  private static void appendCardDetail(StringBuilder det, Set<String> add, Set<String> rem, ChipStore chips)
  {
    if (!add.isEmpty()) { sep(det); det.append("hinzu: ").append(renderCardsForDetail(add, chips)); }
    if (!rem.isEmpty()) { sep(det); det.append("entfernen: ").append(renderCardsForDetail(rem, chips)); }
  }

  private static void appendRegionDetail(StringBuilder det, Set<Integer> addR, Set<Integer> remR,
      Map<Integer, String> regionNameById)
  {
    if (!addR.isEmpty()) { sep(det); det.append("Berechtigung +").append(renderRegions(addR, regionNameById)); }
    if (!remR.isEmpty()) { sep(det); det.append("Berechtigung −").append(renderRegions(remR, regionNameById)); }
  }

  private static String renderRegions(java.util.Collection<Integer> ids, Map<Integer, String> regionNameById)
  {
    StringBuilder sb = new StringBuilder();
    for (Integer id : ids)
    {
      if (sb.length() > 0) sb.append(",");
      String nm = regionNameById == null ? null : regionNameById.get(id);
      sb.append(nm != null ? nm : ("#" + id));
    }
    return sb.toString();
  }

  /** Extract a UserInfo's current regionPermissionGroupIDList as ints. */
  private static List<Integer> regionIdsOf(JSONObject u)
  {
    List<Integer> out = new ArrayList<>();
    JSONArray a = u.optJSONArray("regionPermissionGroupIDList");
    if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optInt(i));
    return out;
  }

  /** Render a card-number collection as Transponder ids for human-facing
   *  detail strings. Unmapped cards (no ChipStore entry) render as
   *  "Karte #<cardNo>" so they're still identifiable but flagged as
   *  unknown — same convention as the Benutzer view. */
  private static String renderCardsForDetail(java.util.Collection<String> cards, ChipStore chips)
  {
    if (cards == null || cards.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (String c : cards)
    {
      if (sb.length() > 0) sb.append(",");
      String chip = (chips == null || c == null) ? null : chips.chipForCard(c);
      sb.append(chip != null ? chip : "Karte #" + c);
    }
    return sb.toString();
  }

  private static PlanRow rowFromActual(String emp, JSONObject u, List<String> cur)
  {
    PlanRow row = new PlanRow();
    row.employeeNo = emp;
    row.name = u.optString("name", "");
    row.userType = u.optString("userType", "");
    row.groupName = u.optString("userGroupNodeName", "");
    row.groupId = u.optString("userGroupNodeID", "");
    row.currentRegionIds = regionIdsOf(u);
    row.currentCards = cur;
    JSONObject vl = u.optJSONObject("Valid");
    if (vl != null)
    {
      row.currentEnabled = vl.optBoolean("enable", true);
      row.currentValidEnd = parseValidEnd(vl.optString("endTime", ""));
    }
    return row;
  }

  private static Mitglied lookupByEmp(String emp, Map<String, Mitglied> byExterne, Map<String, Mitglied> byId)
  {
    if (emp.startsWith("G")) return byId.get(emp.substring(1));
    try { return byExterne.get(String.valueOf(Integer.parseInt(emp))); }
    catch (NumberFormatException nfe) { return null; }
  }

  private static String safe(String s) { return s == null ? "" : s; }

  // ============================================================ run

  /** Full sync: re-fetch the entire controller state, recompute the plan, then
   *  apply. Pulls all users + all cards (~80s) — use only when no cached plan
   *  is available. The interactive "Übertragen" button uses {@link #applyCached}
   *  instead so a single pending change doesn't trigger a full fetch. */
  public static Result run(boolean dryRun, de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    pl.log("=== Sync " + (dryRun ? "(DRY-RUN)" : "(APPLY)") + " (vollständig) ===");
    HikvisionClient client = buildClient(pl);
    ChipStore chips = ChipStore.defaultStore();
    Plan plan = computePlan(chips, client, pl);
    return applyPlan(plan, dryRun, client, pl);
  }

  /** Apply an already-computed plan (the cached Benutzer-view diff) WITHOUT any
   *  controller fetch — writes only the out-of-sync rows. This is what
   *  "Übertragen" runs: the displayed plan already says what differs, so syncing
   *  just writes those rows instead of pulling all users + all cards first.
   *  Refresh the view (Aktualisieren) when you need the diff re-checked. */
  public static Result applyCached(Plan plan, boolean dryRun,
                                   de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    pl.log("=== Sync " + (dryRun ? "(DRY-RUN)" : "(APPLY)") + " (nur offene Aktionen) ===");
    if (plan == null)
    {
      Result r = new Result(); r.dryRun = dryRun;
      pl.log("Kein Plan im Cache — bitte zuerst 'Aktualisieren' klicken.");
      return r;
    }
    return applyPlan(plan, dryRun, buildClient(pl), pl);
  }

  /** Controller client wired to the task's cancel flag + configured
   *  retry/deadline knobs, so a wedged call can't hang Jameica's task slot. */
  private static HikvisionClient buildClient(de.jost_net.JVerein.hikvision.ProgressListener pl)
  {
    HikvisionClient client = new HikvisionClient(
        HikvisionSettings.getControllerUrl(), HikvisionSettings.getControllerUser(),
        HikvisionSettings.getControllerPassword(), HikvisionSettings.getInterCallPauseMs(),
        HikvisionSettings.getVerifySsl());
    client.setCancelCheck(pl::isCancelled);
    client.setResilience(HikvisionSettings.getMaxAttempts(), HikvisionSettings.getCallDeadlineMs());
    return client;
  }

  /** Walk the plan and write only the actionable rows (OK / HIK_ONLY /
   *  INCOMPLETE are no-ops). On full success folds the applied changes into the
   *  cache (no re-fetch); on partial failure invalidates it. */
  private static Result applyPlan(Plan plan, boolean dryRun, HikvisionClient client,
                                  de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    Result r = new Result();
    r.dryRun = dryRun;
    int total = plan.rows.size();

    // A cardNo can belong to only one user on the controller. When a
    // transponder moves from member A to member B, B's createCard would fail
    // ("card already assigned") unless A's deleteCard ran first. So enforce a
    // strict global order across the whole plan, independent of row order:
    //   PASS 1 — detach every no-longer-desired card (and delete orphans),
    //            freeing those cardNos;
    //   PASS 2 — create users, attach cards, apply group / region / valid.
    // This guarantees a moved card is always free before it's re-attached.
    int steps = total * 2;
    int done = 0;
    pl.progress(done, steps, "Karten entfernen");

    for (PlanRow row : plan.rows)
    {
      if (pl.isCancelled()) throw new java.io.InterruptedIOException("Abgebrochen nach " + done + "/" + steps);
      switch (row.status)
      {
        case UPDATE:
        case DISABLE:
        case REACTIVATE:
          applyCardRemovals(client, row, dryRun, r, pl);
          break;
        case DELETE:
          applyDelete(client, row, dryRun, r, pl);   // removes its cards + the user
          break;
        default:
          break;
      }
      pl.progress(++done, steps, "Karten entfernen");
    }

    pl.progress(done, steps, "Karten & Gruppen zuweisen");
    for (PlanRow row : plan.rows)
    {
      if (pl.isCancelled()) throw new java.io.InterruptedIOException("Abgebrochen nach " + done + "/" + steps);
      switch (row.status)
      {
        case CREATE:
          applyCreate(client, plan, row, dryRun, r, pl);
          break;

        case UPDATE:
          applyUpdate(client, plan, row, dryRun, r, pl);   // card adds + group/region (removals done in pass 1)
          break;

        case DISABLE:
          applyDisable(client, row, dryRun, r, pl);
          applyUpdate(client, plan, row, dryRun, r, pl);   // card adds / group changes still propagate
          break;

        case REACTIVATE:
          applyReactivate(client, row, dryRun, r, pl);
          applyUpdate(client, plan, row, dryRun, r, pl);
          break;

        case OK:
        case HIK_ONLY:
        case INCOMPLETE:   // intentionally requires user intervention
        case DELETE:       // fully handled in pass 1
          break;
      }
      pl.progress(++done, steps, "Karten & Gruppen zuweisen");
    }

    pl.log("=== DONE === created=" + r.created + " updated=" + r.updated
        + " disabled=" + r.disabled + " reactivated=" + r.reactivated
        + " deleted=" + r.deleted + " cardsAdded=" + r.cardsAdded
        + " cardsRemoved=" + r.cardsRemoved + " cardsMoved=" + r.cardsMoved
        + " groupsChanged=" + r.groupsChanged + " regionsChanged=" + r.regionsChanged
        + " validChanged=" + r.validChanged + " errors=" + r.errors.size());

    int changes = r.created + r.deleted + r.cardsAdded + r.cardsRemoved + r.disabled
                + r.reactivated + r.groupsChanged + r.regionsChanged + r.validChanged;
    if (!dryRun && changes > 0)
    {
      if (r.errors.isEmpty())
        // Apply succeeded fully — fold the applied changes into the plan so the
        // cached Benutzer view reflects the new controller state immediately and
        // survives a restart, WITHOUT a follow-up full re-fetch.
        foldAppliedIntoCache(plan);
      else
        // Partial failure — real controller state is uncertain, so drop the
        // cache and let the next view-open / refresh re-fetch authoritatively.
        PlanCache.invalidate();
    }
    return r;
  }

  /** After a fully-successful apply, rewrite each actionable row's "current"
   *  side to equal what we just pushed (so it now reads as in-sync), drop
   *  DELETE rows, recompute the access-ended flag + counters, and persist. No
   *  controller round-trip — this is the cheap alternative to invalidating the
   *  cache and forcing a manual Aktualisieren after every sync. */
  private static void foldAppliedIntoCache(Plan plan)
  {
    java.util.Iterator<PlanRow> it = plan.rows.iterator();
    while (it.hasNext())
    {
      PlanRow r = it.next();
      if (r.status == null) continue;
      switch (r.status)
      {
        case DELETE:
          it.remove();                 // user removed from the controller
          break;
        case CREATE:
        case UPDATE:
        case DISABLE:
        case REACTIVATE:
          foldDesiredIntoCurrent(r);   // current := desired (now applied)
          r.status = Status.OK;
          r.detail = "in sync";
          break;
        default:                       // OK / HIK_ONLY / INCOMPLETE — untouched
          break;
      }
      r.accessEnded = computeAccessEnded(r);
    }
    recount(plan);
    PlanCache.save(plan);
  }

  /** Mirror a successfully-applied row's desired state onto its current state.
   *  Mirrors exactly what the apply* methods write: cards, managed region
   *  groups, a changed org group, and the Valid enable/endTime pair. */
  private static void foldDesiredIntoCurrent(PlanRow r)
  {
    r.currentCards = new ArrayList<>(r.desiredCards);
    if (r.desiredRegionIds != null && !r.desiredRegionIds.isEmpty())
      r.currentRegionIds = new ArrayList<>(r.desiredRegionIds);
    if (r.desiredGroupId != null && !r.desiredGroupId.isEmpty())
    { r.groupId = r.desiredGroupId; r.groupName = r.desiredGroupName; }
    r.currentEnabled  = r.desiredEnabled;
    r.currentValidEnd = r.desiredValidEnd;
  }

  /** True when the controller is actively denying this user purely on an
   *  expired validity window: {@code Valid.enable=true} (time restriction ON)
   *  with a real (non-far-future) {@code endTime} now in the past. This is the
   *  state a member lands in once their Austrittsdatum passes — they are kept
   *  on the controller (not deleted) so swipe history stays attached to their
   *  transponder, but their access has ended. enable=false means "no
   *  restriction" (always allow) → never counts as ended. */
  public static boolean computeAccessEnded(PlanRow r)
  {
    return r != null && r.currentEnabled && r.currentValidEnd != null
        && r.currentValidEnd.before(new Date());
  }

  /** Attach {@code cardNo} to {@code employeeNo}, recovering from the
   *  "card already assigned to another user" case — a transponder move whose
   *  donor wasn't in this plan (e.g. an incremental sync where only the new
   *  holder is in scope). The global PASS 1 frees in-plan donors before any
   *  add; this is the fallback for donors PASS 1 never saw:
   *  <ol>
   *    <li>optimistic createCard — succeeds outright when the card is free;</li>
   *    <li>on failure, look up the card's current owner on the controller —
   *        if it is already this user, treat as done;</li>
   *    <li>otherwise detach the card (frees it from the donor) and retry the
   *        create;</li>
   *    <li>drop the card from the donor's row in the cached plan so it isn't
   *        double-listed.</li>
   *  </ol>
   *  In the happy path this is a single call; recovery only adds round-trips
   *  when a card genuinely has to be reclaimed. */
  private static void attachCardMoveAware(HikvisionClient c, Plan plan, String employeeNo, String cardNo,
                                          Result r, de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    // (2) add card — succeeds immediately when the cardNo is free
    if (c.createCard(employeeNo, cardNo)) { r.cardsAdded++; return; }

    // (1) the add failed — find who currently holds the card
    String owner = null;
    try { owner = c.findCardOwner(cardNo); }
    catch (Exception e) { pl.log("WARN: Karteninhaber-Abfrage für " + cardNo + " fehlgeschlagen: " + e.getMessage()); }

    if (owner != null && Identity.canonical(owner).equals(Identity.canonical(employeeNo)))
    { pl.log("Karte " + cardNo + " ist bereits " + employeeNo + " zugeordnet — übersprungen"); return; }

    pl.log("Karte " + cardNo + " bereits vergeben"
        + (owner != null ? " an " + owner : "") + " — Umzug nach " + employeeNo);

    // (3) remove it from the previous user (deleteCard frees by cardNo alone)
    if (!c.deleteCard(cardNo)) { r.errors.add("deleteCard(Umzug) " + cardNo + " fehlgeschlagen"); return; }
    r.cardsRemoved++;

    // (4) reflect the detach in the cached plan
    detachCardFromCache(plan, cardNo, owner);

    // (2, retry) now the card is free → attach to the intended user
    if (c.createCard(employeeNo, cardNo)) { r.cardsAdded++; r.cardsMoved++; }
    else r.errors.add("createCard(Umzug-Retry) " + employeeNo + "/" + cardNo + " fehlgeschlagen");
  }

  /** Drop {@code cardNo} from its previous holder's current cards in the cached
   *  plan after a controller-side move, so the cache no longer double-lists it.
   *  {@code ownerEmp} is the donor employeeNo when the owner lookup found it;
   *  null → scan every row. The donor row keeps its status (the next refresh
   *  reconciles it) — this only keeps the displayed/persisted cache honest. */
  private static void detachCardFromCache(Plan plan, String cardNo, String ownerEmp)
  {
    if (plan == null || cardNo == null) return;
    String canonOwner = ownerEmp == null ? null : Identity.canonical(ownerEmp);
    for (PlanRow row : plan.rows)
    {
      if (row.currentCards == null || row.currentCards.isEmpty()) continue;
      if (canonOwner != null && !canonOwner.equals(Identity.canonical(row.employeeNo))) continue;
      if (row.currentCards.remove(cardNo))
      {
        row.accessEnded = computeAccessEnded(row);
        if (canonOwner != null) break;   // found the specific donor
      }
    }
  }

  private static void applyCreate(HikvisionClient c, Plan plan, PlanRow row, boolean dry, Result r,
                                  de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    // Org userGroup is automatic: sponsors → BSV, members → Mitglieder. Keep
    // gid/gnm a consistent pair — never the old "fallback UUID + desired name"
    // mix that silently dumped new users into the wrong group.
    boolean sponsor = "visitor".equals(row.userType);
    String gid = row.desiredGroupId;
    String gnm = row.desiredGroupName;
    if (gid == null || gid.isEmpty())
    {
      gid = sponsor ? HikvisionSettings.getSponsorGroupId()   : HikvisionSettings.getMemberGroupId();
      gnm = sponsor ? HikvisionSettings.getSponsorGroupName() : HikvisionSettings.getMemberGroupName();
    }
    if (gid == null || gid.isEmpty())
    {
      // No usable userGroup UUID at all — refuse rather than create broken.
      String msg = "CREATE " + row.employeeNo + " " + row.name
          + " abgebrochen: keine gültige Organisationsgruppe (UUID fehlt — bitte in den Einstellungen setzen)";
      pl.log("FEHLER: " + msg); r.errors.add(msg); return;
    }
    pl.log("CREATE " + row.employeeNo + " " + row.name + " group=" + gnm
        + (row.desiredRegionNames.isEmpty() ? "" : " Berechtigung=" + row.desiredRegionNames)
        + " cards=" + row.desiredCards
        + " endTime=" + (row.desiredValidEnd == null ? "(none)" : ISO_D.format(row.desiredValidEnd)));
    if (dry) return;
    boolean ok = c.createUser(row.employeeNo, row.name, row.userType, gid, gnm,
        row.desiredEnabled, null, toHikvisionEndTime(row.desiredValidEnd), "", row.desiredRegionIds);
    if (!ok) { r.errors.add("createUser " + row.employeeNo + " failed"); return; }
    r.created++;
    if (!row.desiredRegionIds.isEmpty()) r.regionsChanged++;
    for (String cn : row.desiredCards)
      attachCardMoveAware(c, plan, row.employeeNo, cn, r, pl);
  }

  /** PASS 1 — detach every card the controller still has but the member no
   *  longer wants, freeing the cardNo so {@link #applyUpdate} / {@link
   *  #applyCreate} can re-attach it to whoever now holds it. Runs for
   *  UPDATE / DISABLE / REACTIVATE rows before any card is added anywhere. */
  private static void applyCardRemovals(HikvisionClient c, PlanRow row, boolean dry, Result r,
                                        de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    Set<String> rem = new HashSet<>(row.currentCards); rem.removeAll(row.desiredCards);
    if (rem.isEmpty()) return;
    pl.log("UPDATE-cards " + row.employeeNo + " entfernen -" + rem);
    if (dry) return;
    for (String cn : rem)
    { if (c.deleteCard(cn)) r.cardsRemoved++; else r.errors.add("deleteCard " + cn + " failed"); }
  }

  /** PASS 2 — attach newly-desired cards and apply group / region changes.
   *  Card removals already ran in {@link #applyCardRemovals} (pass 1), so a
   *  card moved away from another member is already free by the time we
   *  createCard it here. */
  private static void applyUpdate(HikvisionClient c, Plan plan, PlanRow row, boolean dry, Result r,
                                  de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    Set<String> add = new HashSet<>(row.desiredCards); add.removeAll(row.currentCards);
    Set<Integer> addR = new HashSet<>(row.desiredRegionIds); addR.removeAll(row.currentRegionIds);
    Set<Integer> remR = new HashSet<>(row.currentRegionIds); remR.removeAll(row.desiredRegionIds);
    boolean regionsDiffer = !addR.isEmpty() || !remR.isEmpty();
    boolean groupDiffers = row.desiredGroupId != null && !row.desiredGroupId.isEmpty()
        && !row.desiredGroupId.equals(row.groupId);

    if (!add.isEmpty())
      pl.log("UPDATE-cards " + row.employeeNo + " +" + add);
    if (groupDiffers)
      pl.log("UPDATE-Gruppe " + row.employeeNo + " " + row.groupName + " → " + row.desiredGroupName);
    if (regionsDiffer)
      pl.log("UPDATE-Berechtigung " + row.employeeNo + " soll=" + row.desiredRegionIds
          + " ist=" + row.currentRegionIds);

    if (dry) return;

    for (String cn : add)
      attachCardMoveAware(c, plan, row.employeeNo, cn, r, pl);

    if (groupDiffers)
    {
      if (c.setUserGroup(row.employeeNo, row.desiredGroupId, row.desiredGroupName)) r.groupsChanged++;
      else r.errors.add("setUserGroup " + row.employeeNo + " failed");
    }

    if (regionsDiffer)
    {
      // Replace the full desired set (adds + removes in one call).
      if (c.setUserRegionPermissionGroups(row.employeeNo, row.desiredRegionIds)) r.regionsChanged++;
      else r.errors.add("setUserRegionPermissionGroups " + row.employeeNo + " failed");
    }

    if (row.status == Status.UPDATE) r.updated++;
  }

  /** Apply DISABLE = enforce a time restriction ending on austritt date.
   *  Sets enable=true (turn ON the time check) + endTime=austritt. Cards
   *  stay attached so swipe history is preserved. */
  private static void applyDisable(HikvisionClient c, PlanRow row, boolean dry, Result r,
                                   de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    pl.log("DISABLE " + row.employeeNo + " " + row.name
        + " — enforce endTime=" + (row.desiredValidEnd == null ? "?" : ISO_D.format(row.desiredValidEnd)));
    if (dry) return;
    if (c.setUserValid(row.employeeNo, true, toHikvisionEndTime(row.desiredValidEnd)))
    { r.disabled++; r.validChanged++; }
    else r.errors.add("setUserValid(disable) " + row.employeeNo + " failed");
  }

  /** Apply REACTIVATE = remove a stale time restriction left over from a
   *  previous austritt. Sets enable=false (no time check). */
  private static void applyReactivate(HikvisionClient c, PlanRow row, boolean dry, Result r,
                                      de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    pl.log("REACTIVATE " + row.employeeNo + " " + row.name + " — remove time restriction");
    if (dry) return;
    if (c.setUserValid(row.employeeNo, false, null))
    { r.reactivated++; r.validChanged++; }
    else r.errors.add("setUserValid(enable=false) " + row.employeeNo + " failed");
  }

  private static void applyDelete(HikvisionClient c, PlanRow row, boolean dry, Result r,
                                  de.jost_net.JVerein.hikvision.ProgressListener pl) throws Exception
  {
    pl.log("DELETE " + row.employeeNo + " " + row.name + " (orphan)");
    if (dry) return;
    for (String cn : row.currentCards)
    { if (c.deleteCard(cn)) r.cardsRemoved++; else r.errors.add("deleteCard " + cn + " failed"); }
    if (c.deleteUser(row.employeeNo)) r.deleted++;
    else r.errors.add("deleteUser " + row.employeeNo + " failed");
  }
}
