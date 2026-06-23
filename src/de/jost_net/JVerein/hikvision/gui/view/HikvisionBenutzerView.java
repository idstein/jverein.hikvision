package de.jost_net.JVerein.hikvision.gui.view;

import java.io.InterruptedIOException;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import de.jost_net.JVerein.hikvision.ChipStore;
import de.jost_net.JVerein.hikvision.HikvisionClient;
import de.jost_net.JVerein.hikvision.HikvisionGroupCatalog;
import de.jost_net.JVerein.hikvision.HikvisionSettings;
import de.jost_net.JVerein.hikvision.Identity;
import de.jost_net.JVerein.hikvision.MitgliedAssignments;
import de.jost_net.JVerein.hikvision.PlanCache;
import de.jost_net.JVerein.hikvision.SyncEngine;
import de.jost_net.JVerein.hikvision.ext.AssignmentEditDialog;
import de.jost_net.JVerein.hikvision.ext.HikvisionBackgroundTask;
import de.jost_net.JVerein.hikvision.ext.MitgliedPickerDialog;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.jameica.system.Application;
import de.willuhn.jameica.system.BackgroundTask;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.ProgressMonitor;

/**
 * OpenJVerein > Hikvision > Benutzer
 *
 * Diff preview of the next jverein → Hikvision sync. Loads from the
 * persistent {@link PlanCache} on open so no controller call happens
 * on UI interactions. Filter dropdown narrows by status. Sync and
 * Import buttons live here (the action belongs with the view that
 * shows what it will do).
 */
public class HikvisionBenutzerView extends AbstractView
{
  private Table table;
  private Label countLabel;
  private Combo filterCombo;
  private ProgressBar progress;
  private Text logArea;
  private Button refreshBtn, refreshVisibleBtn, syncBtn;
  private org.eclipse.swt.widgets.Button dryRunCheckbox;
  private Text searchField;          // live full-text search across visible columns
  private java.util.List<SyncEngine.PlanRow> currentRows = java.util.Collections.emptyList();
  private SyncEngine.Plan currentPlan;   // backing plan for currentRows (for offline edits + re-save)
  private ChipStore chipLookup;      // cached for renderRows so search/filter don't hit disk
  private java.util.Map<Integer, String> regionNameById = java.util.Collections.emptyMap();  // Berechtigungsgruppe id → name
  private final java.util.Set<String> unknownCardsLogged = new java.util.HashSet<>();
  private final java.util.Map<String, java.util.List<String>> unknownCardsByEmp = new java.util.HashMap<>();

  @Override
  public void bind() throws Exception
  {
    GUI.getView().setTitle("Zugangssystem Benutzer");
    Composite parent = getParent();
    parent.setLayout(new GridLayout(2, false));
    Composite c = parent;

    Label info = new Label(c, SWT.WRAP);
    info.setText("Diff-Übersicht: was würde 'Übertragen' (jverein → Hikvision) tun? "
        + "Filter unten links wählt die Aktion. Letzter Stand wird aus lokalem Cache geladen. "
        + "Aktualisieren prüft den Abgleich neu; Übertragen schreibt nur die nicht-synchronen Zeilen.");
    GridData infoGd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    infoGd.widthHint = 800;
    info.setLayoutData(infoGd);

    // --- toolbar (refresh modes / filter / count) ---
    Composite toolbar = new Composite(c, SWT.NONE);
    toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    toolbar.setLayout(new GridLayout(5, false));

    refreshBtn = new Button(toolbar, SWT.PUSH);
    refreshBtn.setText("Aktualisieren");
    refreshBtn.setToolTipText("Prüft den Abgleich mit Hikvision neu (inkrementell: nur Nicht-synchrone Zeilen "
        + "und kürzlich geänderte Zuweisungen; holt automatisch alles, wenn Cache fehlt oder die "
        + "Hikvision-Gesamtzahl abweicht).");
    refreshBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onRefreshIncremental(); }
    });

    refreshVisibleBtn = new Button(toolbar, SWT.PUSH);
    refreshVisibleBtn.setText("Schnelles Aktualisieren");
    refreshVisibleBtn.setToolTipText("Schnell: prüft nur die aktuell angezeigten Zeilen neu (Filter+Suche berücksichtigt).");
    refreshVisibleBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onRefreshVisible(); }
    });

    new Label(toolbar, SWT.NONE).setText("Filter:");
    filterCombo = new Combo(toolbar, SWT.READ_ONLY | SWT.DROP_DOWN);
    filterCombo.setItems(new String[] {
        "Alle",
        "Nicht synchron (Aktion nötig)",
        "Nur neu (CREATE)",
        "Nur geändert (UPDATE)",
        "Nur deaktivieren (DISABLE)",
        "Nur reaktivieren (REACTIVATE)",
        "Nur löschen (DELETE)",
        "Nur unvollständig (INCOMPLETE)",
        "Nur unverwaltet (HIK_ONLY)",
        "Nur in sync (OK)",
        "Zugang beendet (Austritt/abgelaufen)",
        "Ohne Transponder" });
    filterCombo.select(1);   // default to "Nicht OK" — that's the actionable view
    filterCombo.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { renderRows(); }
    });

    countLabel = new Label(toolbar, SWT.NONE);
    countLabel.setText("(noch nicht abgerufen)");
    countLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    Composite searchRow = new Composite(c, SWT.NONE);
    searchRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    searchRow.setLayout(new GridLayout(2, false));
    new Label(searchRow, SWT.NONE).setText("Suche:");
    searchField = new Text(searchRow, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
    searchField.setMessage("employeeNo, Name, Gruppe, Transponder, Hinweis …");
    searchField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    searchField.addModifyListener(e -> renderRows());

    progress = new ProgressBar(c, SWT.HORIZONTAL | SWT.SMOOTH);
    progress.setMinimum(0); progress.setMaximum(100);
    progress.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

    // --- table ---
    table = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    table.setHeaderVisible(true);
    table.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    tgd.heightHint = 400; tgd.widthHint = 1000;
    table.setLayoutData(tgd);
    String[][] cols = {
        { "Status", "90" }, { "employeeNo", "100" }, { "Name", "170" },
        { "Typ", "70" }, { "Org.-Gruppe", "130" }, { "Berechtigungsgruppen", "180" },
        { "Transponder", "180" }, { "Hinweis", "240" } };
    for (String[] col : cols)
    {
      TableColumn tc = new TableColumn(table, SWT.LEFT);
      tc.setText(col[0]); tc.setWidth(Integer.parseInt(col[1]));
    }
    TableSorter.install(table);

    // Double-click on a row → edit the assignment for that Mitglied
    table.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetDefaultSelected(SelectionEvent e) { onEditAssignment(); }
    });

    // Right-click context menu: edit assignment (Org-Gruppe + Berechtigungsgruppen),
    // open the jverein Mitglied, or start a new assignment.
    Menu ctx = new Menu(table);
    MenuItem miEdit = new MenuItem(ctx, SWT.PUSH);
    miEdit.setText("Zuweisung bearbeiten…");
    miEdit.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onEditAssignment(); }
    });
    MenuItem miOpen = new MenuItem(ctx, SWT.PUSH);
    miOpen.setText("Mitglied öffnen");
    miOpen.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onOpenMitglied(); }
    });
    new MenuItem(ctx, SWT.SEPARATOR);
    MenuItem miNew = new MenuItem(ctx, SWT.PUSH);
    miNew.setText("Neue Zuweisung…");
    miNew.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onNewAssignment(); }
    });
    table.setMenu(ctx);

    // --- mitglied bearbeiten / zuweisung row ---
    Composite editRow = new Composite(c, SWT.NONE);
    editRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    editRow.setLayout(new GridLayout(3, true));
    Button editBtn = new Button(editRow, SWT.PUSH);
    editBtn.setText("Mitglied bearbeiten");
    editBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    editBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onOpenMitglied(); }
    });
    Button editAsnBtn = new Button(editRow, SWT.PUSH);
    editAsnBtn.setText("Zuweisung bearbeiten");
    editAsnBtn.setToolTipText("Oder Zeile doppelklicken");
    editAsnBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    editAsnBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onEditAssignment(); }
    });
    Button newAsnBtn = new Button(editRow, SWT.PUSH);
    newAsnBtn.setText("Neue Zuweisung…");
    newAsnBtn.setToolTipText("Für ein Mitglied das noch nicht in der Liste steht");
    newAsnBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    newAsnBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onNewAssignment(); }
    });

    // --- action row: dry-run + Sync + Import ---
    Composite actionRow = new Composite(c, SWT.NONE);
    actionRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    actionRow.setLayout(new GridLayout(2, false));

    dryRunCheckbox = new org.eclipse.swt.widgets.Button(actionRow, SWT.CHECK);
    dryRunCheckbox.setText("Trockenlauf");
    dryRunCheckbox.setToolTipText("Wenn aktiv: nur loggen was passieren würde, keine Schreibvorgänge auf Hikvision.");
    dryRunCheckbox.setSelection(HikvisionSettings.getDryRun());
    dryRunCheckbox.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e)
      { HikvisionSettings.setDryRun(dryRunCheckbox.getSelection()); }
    });
    dryRunCheckbox.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

    syncBtn = new Button(actionRow, SWT.PUSH);
    syncBtn.setText("Übertragen");
    syncBtn.setToolTipText("Schreibt nur die nicht-synchronen Zeilen (jverein → Hikvision) — ohne erneuten Abruf. "
        + "Vorher 'Aktualisieren', wenn der Abgleich neu geprüft werden soll.");
    syncBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    syncBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onSync(); }
    });

    // --- log area ---
    logArea = new Text(c, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
    GridData lgd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    lgd.heightHint = 180;
    logArea.setLayoutData(lgd);

    loadCachedPlan();
  }

  // ============================================================ cache + render

  private void loadCachedPlan()
  {
    PlanCache.Cached cached = PlanCache.load();
    if (cached == null || cached.plan == null)
    {
      countLabel.setText("(noch nicht abgerufen — bitte 'Aktualisieren' klicken)");
      return;
    }
    currentRows = cached.plan.rows;
    currentPlan = cached.plan;
    refreshChipLookup();
    countLabel.setText(summaryFor(cached.plan) + "  · letzter Abruf: " + formatAge(cached.timestamp));
    renderRows();
  }

  /** Load ChipStore from disk and emit one log line per unknown
   *  Hikvision-side cardNo (deduped). Called once per plan load — the
   *  cached store is reused by renderRows so search/filter don't touch
   *  the filesystem on every keystroke. */
  private void refreshChipLookup()
  {
    try { chipLookup = ChipStore.defaultStore(); }
    catch (Exception e)
    {
      Logger.error("ChipStore load failed", e);
      chipLookup = null;
      unknownCardsByEmp.clear();
      return;
    }
    // Cache Berechtigungsgruppe id→name so renderRows can show region groups
    // without re-reading the catalog file on every keystroke.
    try
    {
      java.util.Map<Integer, String> m = new java.util.HashMap<>();
      for (HikvisionGroupCatalog.RegionPermissionGroup r : HikvisionGroupCatalog.fromCache().regions)
        m.put(r.id, r.name == null || r.name.isEmpty() ? ("#" + r.id) : r.name);
      regionNameById = m;
    }
    catch (Exception e) { regionNameById = java.util.Collections.emptyMap(); }
    unknownCardsLogged.clear();
    unknownCardsByEmp.clear();
    for (SyncEngine.PlanRow r : currentRows)
    {
      if (r.currentCards == null) continue;
      java.util.List<String> unknowns = null;
      for (String card : r.currentCards)
      {
        if (card == null || card.isEmpty()) continue;
        if (chipLookup.chipForCard(card) != null) continue;
        if (unknowns == null) unknowns = new java.util.ArrayList<>();
        unknowns.add(card);
        if (unknownCardsLogged.add(card))
          log("Unbekannter Transponder: " + card + " (employeeNo=" + r.employeeNo + ")\n");
      }
      if (unknowns != null) unknownCardsByEmp.put(r.employeeNo, unknowns);
    }
  }

  private String summaryFor(SyncEngine.Plan p)
  {
    return p.rows.size() + " Einträge — " + p.create + " neu, " + p.update + " geändert, "
        + p.disable + " deaktivieren, " + p.reactivate + " reaktivieren, "
        + p.delete + " löschen, " + p.incomplete + " unvollständig, "
        + p.hikOnly + " unverwaltet, " + p.ok + " in sync"
        + (p.unknownCards > 0 ? "  (⚠ " + p.unknownCards + " unbekannte Transponder)" : "");
  }

  private static String formatAge(long ts)
  {
    if (ts <= 0) return "?";
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    String stamp = sdf.format(new java.util.Date(ts));
    long mins = (System.currentTimeMillis() - ts) / 60000L;
    if (mins < 1) return stamp + " (gerade eben)";
    if (mins < 60) return stamp + " (vor " + mins + " Min)";
    long hrs = mins / 60;
    if (hrs < 48) return stamp + " (vor " + hrs + " Std)";
    return stamp + " (vor " + (hrs / 24) + " Tagen)";
  }

  private void renderRows()
  {
    if (table == null || table.isDisposed()) return;
    table.removeAll();
    int sel = filterCombo.getSelectionIndex();
    final boolean onlyEnded = sel == 10;          // Zugang beendet (Austritt/abgelaufen)
    final boolean onlyNoTransponder = sel == 11;  // Ohne Transponder
    java.util.Set<SyncEngine.Status> wanted;
    switch (sel)
    {
      case 1: // Nicht OK — anything that needs an action
        wanted = java.util.EnumSet.of(SyncEngine.Status.CREATE, SyncEngine.Status.UPDATE,
            SyncEngine.Status.DISABLE, SyncEngine.Status.REACTIVATE,
            SyncEngine.Status.DELETE, SyncEngine.Status.INCOMPLETE);
        break;
      case 2: wanted = java.util.EnumSet.of(SyncEngine.Status.CREATE);     break;
      case 3: wanted = java.util.EnumSet.of(SyncEngine.Status.UPDATE);     break;
      case 4: wanted = java.util.EnumSet.of(SyncEngine.Status.DISABLE);    break;
      case 5: wanted = java.util.EnumSet.of(SyncEngine.Status.REACTIVATE); break;
      case 6: wanted = java.util.EnumSet.of(SyncEngine.Status.DELETE);     break;
      case 7: wanted = java.util.EnumSet.of(SyncEngine.Status.INCOMPLETE); break;
      case 8: wanted = java.util.EnumSet.of(SyncEngine.Status.HIK_ONLY);   break;
      case 9: wanted = java.util.EnumSet.of(SyncEngine.Status.OK);         break;
      default: wanted = null;   // 0 (Alle), 10, 11 → handled by the predicates below
    }
    String q = (searchField == null || searchField.isDisposed()) ? "" : searchField.getText().trim().toLowerCase();
    for (SyncEngine.PlanRow r : currentRows)
    {
      boolean ended = SyncEngine.computeAccessEnded(r);
      r.accessEnded = ended;   // keep the row in step with "as of now"
      if (wanted != null && !wanted.contains(r.status)) continue;
      if (onlyEnded && !ended) continue;
      if (onlyNoTransponder && !hasNoTransponder(r)) continue;
      String emp = r.employeeNo == null ? "" : r.employeeNo;
      String nm  = r.name == null ? "" : r.name;
      String ty  = r.userType == null ? "" : r.userType;
      // Managed = a jverein-driven row (not HIK-only / orphan / incomplete);
      // only those show an "ist → soll" arrow.
      boolean managed = r.status != null && r.status != SyncEngine.Status.HIK_ONLY
          && r.status != SyncEngine.Status.DELETE && r.status != SyncEngine.Status.INCOMPLETE;
      String gp     = renderGroupInline(r, managed);
      String ber    = renderRegionsInline(r, managed);
      String transp = renderTransponderInline(r, managed);
      String det = composeDetail(r);
      String statusCell = statusCellLabel(r, ended);
      if (!q.isEmpty())
      {
        String haystack = (statusCell + " " + emp + " " + nm + " " + ty + " " + gp + " " + ber + " " + transp + " " + det).toLowerCase();
        if (!haystack.contains(q)) continue;
      }
      TableItem ti = new TableItem(table, SWT.NONE);
      ti.setText(0, statusCell);
      ti.setText(1, emp);
      ti.setText(2, nm);
      ti.setText(3, ty);
      ti.setText(4, gp);
      ti.setText(5, ber);
      ti.setText(6, transp);
      ti.setText(7, det);
    }
    // Preserve the user's column sort across filter / refresh — without this
    // the table header keeps the ↑/↓ indicator but the rows are unsorted.
    TableSorter.reapplyIfSorted(table);
  }

  /** Compose the Hinweis column: the engine's detail plus a flag for any
   *  unknown current cards. Phrasing depends on status — UPDATE/DISABLE/
   *  REACTIVATE actually remove the card, DELETE removes the whole user
   *  (warning is moot), HIK_ONLY/OK/INCOMPLETE don't touch it. */
  private String composeDetail(SyncEngine.PlanRow r)
  {
    String det = r.detail == null ? "" : r.detail;
    if (SyncEngine.computeAccessEnded(r))
    {
      String when = fmtDate(r.currentValidEnd);
      String note = "Zugang beendet" + (when.isEmpty() ? "" : " am " + when)
          + " — Eintrag bleibt für Zutritts-Historie erhalten";
      // In-sync rows carry the bland "in sync" detail — replace it with the
      // more informative ended note; otherwise prepend it.
      det = (det.isEmpty() || det.equals("in sync")) ? note : (note + "  ·  " + det);
    }
    java.util.List<String> unk = unknownCardsByEmp.get(r.employeeNo);
    if (unk == null || unk.isEmpty()) return det;
    if (r.status == SyncEngine.Status.DELETE) return det;   // whole row goes away

    StringBuilder cards = new StringBuilder();
    for (String c : unk) { if (cards.length() > 0) cards.append(", "); cards.append("#").append(c); }

    String warning;
    switch (r.status)
    {
      case UPDATE: case DISABLE: case REACTIVATE:
        warning = "⚠ unbekannte Karte(n) — werden bei Sync entfernt: " + cards;
        break;
      case HIK_ONLY:
        warning = "⚠ unbekannte Karte(n) auf Hikvision (unverwaltet, nicht angetastet): " + cards;
        break;
      default:   // OK, INCOMPLETE
        warning = "⚠ unbekannte Karte(n): " + cards;
    }
    return det.isEmpty() ? warning : (det + "  ·  " + warning);
  }

  /** Display cards as their Transponder ids. Cards without a ChipStore
   *  mapping show as "Karte #<cardNo>" so the user can still see what's on
   *  the Hikvision side, with the raw number flagged as unmapped. */
  /** "ist → soll" when they differ, else just the value. ∅ marks an empty side. */
  private static String arrow(String ist, String soll)
  {
    return (ist.isEmpty() ? "∅" : ist) + " → " + (soll.isEmpty() ? "∅" : soll);
  }

  /** Org-Gruppe column: ist → soll when the desired group differs (managed rows). */
  private String renderGroupInline(SyncEngine.PlanRow r, boolean managed)
  {
    String ist = r.groupName == null ? "" : r.groupName;
    String soll = r.desiredGroupName == null ? "" : r.desiredGroupName;
    if (managed && !soll.isEmpty() && !soll.equals(ist)) return arrow(ist, soll);
    return ist.isEmpty() ? soll : ist;
  }

  /** Berechtigungsgruppen column. Unmanaged (no assigned groups) → just shows
   *  what's currently on the controller; managed → ist → soll when differing. */
  private String renderRegionsInline(SyncEngine.PlanRow r, boolean managed)
  {
    String ist = renderRegionIds(r.currentRegionIds);
    boolean rManaged = managed && r.desiredRegionIds != null && !r.desiredRegionIds.isEmpty();
    if (!rManaged) return ist;
    String soll = (r.desiredRegionNames != null && !r.desiredRegionNames.isEmpty())
        ? String.join(",", r.desiredRegionNames) : renderRegionIds(r.desiredRegionIds);
    boolean differ = !new java.util.HashSet<>(r.desiredRegionIds).equals(new java.util.HashSet<>(r.currentRegionIds));
    return differ ? arrow(ist, soll) : soll;
  }

  /** Transponder column: ist → soll when the card sets differ (managed rows). */
  private String renderTransponderInline(SyncEngine.PlanRow r, boolean managed)
  {
    String ist = renderTransponders(r.currentCards);
    if (!managed) return ist;
    boolean differ = !new java.util.HashSet<>(r.currentCards).equals(new java.util.HashSet<>(r.desiredCards));
    return differ ? arrow(ist, renderTransponders(r.desiredCards)) : ist;
  }

  private String renderRegionIds(java.util.List<Integer> ids)
  {
    if (ids == null || ids.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (Integer id : ids)
    {
      if (sb.length() > 0) sb.append(",");
      String nm = regionNameById.get(id);
      sb.append(nm != null ? nm : ("#" + id));
    }
    return sb.toString();
  }

  private String renderTransponders(java.util.List<String> cards)
  {
    if (cards == null || cards.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (String card : cards)
    {
      if (sb.length() > 0) sb.append(",");
      String chip = (chipLookup == null || card == null) ? null : chipLookup.chipForCard(card);
      sb.append(chip != null ? chip : "Karte #" + card);
    }
    return sb.toString();
  }

  /** Status column text. When the controller is actively blocking a user on an
   *  expired validity window we surface "ZUGANG BEENDET" instead of a bare
   *  "OK" (the sync action genuinely is "nothing to do"); if an action is also
   *  pending we keep the action label and append a marker. */
  private static String statusCellLabel(SyncEngine.PlanRow r, boolean ended)
  {
    if (!ended) return statusLabel(r.status);
    return r.status == SyncEngine.Status.OK ? "ZUGANG BEENDET" : statusLabel(r.status) + " ⏹";
  }

  /** True when neither the controller side nor the desired side holds any
   *  transponder — the user/member currently can't badge in. */
  private static boolean hasNoTransponder(SyncEngine.PlanRow r)
  {
    boolean cur = r.currentCards == null || r.currentCards.isEmpty();
    boolean des = r.desiredCards == null || r.desiredCards.isEmpty();
    return cur && des;
  }

  private static String fmtDate(java.util.Date d)
  {
    return d == null ? "" : new java.text.SimpleDateFormat("yyyy-MM-dd").format(d);
  }

  private static String statusLabel(SyncEngine.Status s)
  {
    if (s == null) return "?";
    switch (s)
    {
      case OK:         return "OK";
      case CREATE:     return "NEU";
      case UPDATE:     return "GEÄNDERT";
      case DISABLE:    return "DEAKTIVIEREN";
      case REACTIVATE: return "REAKTIVIEREN";
      case DELETE:     return "LÖSCHEN";
      case INCOMPLETE: return "UNVOLLSTÄNDIG";
      case HIK_ONLY:   return "HIK-ONLY";
    }
    return s.name();
  }

  // ============================================================ actions

  /** Smart "Aktualisieren": tries incremental, escalates to full on
   *  missing cache, stale lastFullRefresh, or count-probe drift. */
  private void onRefreshIncremental()
  {
    final boolean dry = dryRunCheckbox.getSelection();
    startTask("Aktualisieren (inkrementell)", dry, (task, mon) -> {
      ChipStore chips = ChipStore.defaultStore();
      HikvisionClient client = buildClient(task);

      MitgliedAssignments asn = MitgliedAssignments.load();
      PlanCache.Cached cached = PlanCache.load();

      String escalate = decideEscalation(asn, cached, client);
      if (escalate != null)
      {
        log("Inkrementell nicht möglich (" + escalate + ") — vollständige Aktualisierung läuft …\n");
        runFullRefresh(asn, chips, client, task, mon);
        return;
      }

      java.util.Set<String> scope = buildIncrementalScope(asn, cached.plan);
      log("Inkrementeller Refresh: " + scope.size() + " employeeNo(s) im Scope "
          + "(" + countActionableInCache(cached.plan) + " offene Aktion(en) + Zuweisungen seit letzter voller Aktualisierung)\n");

      SyncEngine.Plan merged = SyncEngine.computePlanFor(scope, cached.plan, chips, client, listener(task, mon));
      currentRows = merged.rows;
      currentPlan = merged;
      Display.getDefault().asyncExec(() -> {
        refreshChipLookup();
        if (countLabel != null && !countLabel.isDisposed())
          countLabel.setText(summaryFor(merged) + "  · inkrementell: gerade eben");
        renderRows();
      });
    });
  }

  /** Refresh only the currently displayed (filter+search) rows. Skips
   *  the controller round-trip entirely if nothing is visible. */
  private void onRefreshVisible()
  {
    final boolean dry = dryRunCheckbox.getSelection();
    java.util.Set<String> visible = currentlyVisibleEmployeeNos();
    if (visible.isEmpty())
    { info("Keine Zeilen sichtbar", "Es gibt nichts zu aktualisieren — Filter/Suche leeren oder 'Aktualisieren' nutzen."); return; }
    startTask("Schnelles Aktualisieren (" + visible.size() + " sichtbar)", dry, (task, mon) -> {
      ChipStore chips = ChipStore.defaultStore();
      HikvisionClient client = buildClient(task);
      PlanCache.Cached cached = PlanCache.load();
      if (cached == null || cached.plan == null)
      { log("Kein Cache vorhanden — bitte zuerst 'Aktualisieren' klicken.\n"); return; }
      SyncEngine.Plan merged = SyncEngine.computePlanFor(visible, cached.plan, chips, client, listener(task, mon));
      currentRows = merged.rows;
      currentPlan = merged;
      Display.getDefault().asyncExec(() -> {
        refreshChipLookup();
        if (countLabel != null && !countLabel.isDisposed())
          countLabel.setText(summaryFor(merged) + "  · sichtbare aktualisiert: gerade eben");
        renderRows();
      });
    });
  }


  /** Build a controller client wired to {@code task}'s cancel flag and the
   *  configured retry/deadline knobs, so every call (escalation count-probe,
   *  full/incremental/visible fetch, sync) aborts promptly on cancel and can
   *  never wedge the single background-task slot. */
  private HikvisionClient buildClient(BackgroundTask task)
  {
    HikvisionClient client = new HikvisionClient(
        HikvisionSettings.getControllerUrl(), HikvisionSettings.getControllerUser(),
        HikvisionSettings.getControllerPassword(), HikvisionSettings.getInterCallPauseMs(),
        HikvisionSettings.getVerifySsl());
    if (task != null) client.setCancelCheck(task::isInterrupted);
    client.setResilience(HikvisionSettings.getMaxAttempts(), HikvisionSettings.getCallDeadlineMs());
    return client;
  }

  /** Common full-refresh body, used both by the explicit button and the
   *  incremental flow's auto-escalation. Persists the user/card totals to
   *  the assignment store so the next incremental can compare. */
  private void runFullRefresh(MitgliedAssignments asn, ChipStore chips, HikvisionClient client,
                              BackgroundTask task, ProgressMonitor mon) throws Exception
  {
    SyncEngine.Plan plan = SyncEngine.computePlan(chips, client, listener(task, mon));
    currentRows = plan.rows;
    currentPlan = plan;
    if (plan.userTotal >= 0 && plan.cardTotal >= 0)
      asn.recordFullRefresh(plan.userTotal, plan.cardTotal);
    Display.getDefault().asyncExec(() -> {
      refreshChipLookup();
      if (countLabel != null && !countLabel.isDisposed())
        countLabel.setText(summaryFor(plan) + "  · vollständig: gerade eben");
      renderRows();
    });
  }

  /** Returns a human-readable reason to escalate, or null if incremental is OK. */
  private String decideEscalation(MitgliedAssignments asn, PlanCache.Cached cached, HikvisionClient client)
      throws java.io.IOException
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

  /** Scope = (cached non-OK/non-HIK_ONLY rows) ∪ (assignments modified
   *  since lastFullRefresh) ∪ (assignments whose employeeNo isn't in the
   *  cached plan yet — could be new CREATE). */
  private java.util.Set<String> buildIncrementalScope(MitgliedAssignments asn, SyncEngine.Plan cached)
  {
    java.util.Set<String> cachedEmp = new java.util.HashSet<>();
    java.util.Set<String> scope = new java.util.HashSet<>();
    for (SyncEngine.PlanRow r : cached.rows)
    {
      String canon = Identity.canonical(r.employeeNo);
      cachedEmp.add(canon);
      if (r.status != null && r.status != SyncEngine.Status.OK
          && r.status != SyncEngine.Status.HIK_ONLY)
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

  private int countActionableInCache(SyncEngine.Plan p)
  {
    int n = 0;
    for (SyncEngine.PlanRow r : p.rows)
      if (r.status != null && r.status != SyncEngine.Status.OK
          && r.status != SyncEngine.Status.HIK_ONLY) n++;
    return n;
  }

  /** Snapshot the employeeNos currently visible in the table (after the
   *  user's filter+search). Reads from the live SWT widgets — must be
   *  called on the UI thread before submitting the bg task. */
  private java.util.Set<String> currentlyVisibleEmployeeNos()
  {
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    if (table == null || table.isDisposed()) return out;
    for (int i = 0; i < table.getItemCount(); i++)
    {
      String emp = table.getItem(i).getText(1);
      if (emp != null && !emp.isEmpty()) out.add(Identity.canonical(emp));
    }
    return out;
  }

  /**
   * Open the selected row's Mitglied in the JVerein detail view so the
   * user can edit the transponder Zusatzfeld (or anything else). Looks
   * the member up by the row's employeeNo:
   *   "G123"  → jverein id 123  (sponsor mapping)
   *   "456"   → jverein externemitgliedsnummer 456  (regular member)
   * Unmanaged employeeNos (e.g. SKM*) have no jverein side and produce
   * an error popup.
   */
  private void onOpenMitglied()
  {
    int idx = table.getSelectionIndex();
    if (idx < 0)
    {
      info("Keine Zeile ausgewählt", "Bitte zuerst einen Eintrag in der Tabelle auswählen.");
      return;
    }
    String emp = table.getItem(idx).getText(1);   // column 1 = employeeNo
    try
    {
      de.jost_net.JVerein.rmi.Mitglied m = lookupMitglied(emp);
      if (m == null)
      {
        info("Kein jverein-Mitglied",
            "Kein zugehöriges jverein-Mitglied für employeeNo " + emp
            + " gefunden. (Unverwaltete Hikvision-Einträge wie SKM* haben "
            + "kein jverein-Pendant.)");
        return;
      }
      new de.jost_net.JVerein.gui.action.MitgliedDetailAction().handleAction(m);
    }
    catch (Exception e)
    {
      Logger.error("open Mitglied failed", e);
      error("Mitglied öffnen fehlgeschlagen",
          e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * Open the assignment editor for the selected row's Mitglied. Looks up
   * the Mitglied by employeeNo (numeric → externe, G… → jvId) and opens
   * {@link AssignmentEditDialog}. On save, the plan cache is invalidated
   * and the Benutzer view re-renders from the updated store.
   */
  private void onEditAssignment()
  {
    int idx = table.getSelectionIndex();
    if (idx < 0)
    { info("Keine Zeile ausgewählt", "Bitte zuerst einen Eintrag in der Tabelle auswählen."); return; }
    String emp = table.getItem(idx).getText(1);
    try
    {
      de.jost_net.JVerein.rmi.Mitglied m = lookupMitglied(emp);
      if (m == null)
      { info("Kein jverein-Mitglied",
          "Kein zugehöriges jverein-Mitglied für employeeNo " + emp
          + " gefunden. (Unverwaltete Hikvision-Einträge wie SKM* haben kein jverein-Pendant.)");
        return; }
      openAssignmentDialogFor(m);
    }
    catch (Exception e)
    {
      Logger.error("onEditAssignment failed", e);
      error("Zuweisung bearbeiten fehlgeschlagen", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** Show the Mitglieder picker, then open the assignment editor for the
   *  chosen Mitglied. Used to assign chips to people who aren't yet on
   *  Hikvision (= not in the current Plan view at all). */
  private void onNewAssignment()
  {
    try
    {
      MitgliedPickerDialog.Picked p = MitgliedPickerDialog.open(GUI.getShell());
      if (p == null) return;
      de.jost_net.JVerein.rmi.Mitglied m =
          (de.jost_net.JVerein.rmi.Mitglied) de.jost_net.JVerein.Einstellungen.getDBService()
              .createObject(de.jost_net.JVerein.rmi.Mitglied.class, p.jvId);
      openAssignmentDialogFor(m);
    }
    catch (Exception e)
    {
      Logger.error("onNewAssignment failed", e);
      error("Neue Zuweisung fehlgeschlagen", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** Shared opener: load store + chip store, open the dialog, on save
   *  recompute the edited member's row offline (no controller call) so the
   *  change shows immediately AND the cache stays intact — never delete the
   *  PlanCache here, otherwise the view goes empty when a refresh isn't
   *  currently possible. */
  private void openAssignmentDialogFor(de.jost_net.JVerein.rmi.Mitglied m) throws Exception
  {
    String vn = m.getVorname() == null ? "" : m.getVorname().trim();
    String nn = m.getName() == null ? "" : m.getName().trim();
    String displayName = (vn + " " + nn).trim();
    String externe = m.getExterneMitgliedsnummer() == null ? "" : m.getExterneMitgliedsnummer().trim();
    String employeeNo = Identity.of(m).employeeNo;

    MitgliedAssignments store = MitgliedAssignments.load();
    ChipStore chips = ChipStore.defaultStore();

    boolean saved = AssignmentEditDialog.open(GUI.getShell(), store, chips,
        m.getID(), displayName, employeeNo, externe);
    if (!saved) return;

    boolean updated = false;
    if (currentPlan != null)
    {
      try { updated = SyncEngine.recomputeRowOffline(currentPlan, m, chips); }
      catch (Exception e) { Logger.error("offline recompute failed", e); }
    }
    if (updated)
    {
      SyncEngine.recount(currentPlan);
      PlanCache.save(currentPlan);
      refreshChipLookup();
      renderRows();
      if (countLabel != null && !countLabel.isDisposed())
        countLabel.setText(summaryFor(currentPlan) + "  · lokal aktualisiert (ohne Controller-Abruf)");
      log("Zuweisung für " + displayName + " gespeichert und Ansicht lokal aktualisiert.\n");
    }
    else
    {
      log("Zuweisung für " + displayName + " gespeichert. Für den vollständigen Abgleich 'Aktualisieren' klicken.\n");
    }
  }

  private de.jost_net.JVerein.rmi.Mitglied lookupMitglied(String employeeNo) throws Exception
  {
    if (employeeNo == null || employeeNo.isEmpty()) return null;
    String e = employeeNo.trim();
    if (e.startsWith("G") && e.length() > 1)
    {
      String rest = e.substring(1);
      try { Integer.parseInt(rest); }   // must be numeric for sponsor scheme
      catch (NumberFormatException nfe) { return null; }
      return (de.jost_net.JVerein.rmi.Mitglied) de.jost_net.JVerein.Einstellungen
          .getDBService().createObject(de.jost_net.JVerein.rmi.Mitglied.class, rest);
    }
    // numeric → externemitgliedsnummer; non-numeric (SKM*) → no jverein match
    String ext;
    try { ext = String.valueOf(Integer.parseInt(e)); }
    catch (NumberFormatException nfe) { return null; }
    de.willuhn.datasource.rmi.DBIterator<de.jost_net.JVerein.rmi.Mitglied> it =
        de.jost_net.JVerein.Einstellungen.getDBService()
            .createList(de.jost_net.JVerein.rmi.Mitglied.class);
    it.addFilter("externemitgliedsnummer = ?", ext);
    if (it.hasNext()) return (de.jost_net.JVerein.rmi.Mitglied) it.next();
    return null;
  }

  private void onSync()
  {
    final boolean dry = dryRunCheckbox.getSelection();   // UI thread — capture before submitting
    final SyncEngine.Plan inMemory = currentPlan;        // reflects offline edits
    startTask("Übertragen", dry, (task, mon) -> {
      // Apply the already-computed diff — NO controller fetch. The in-memory
      // plan reflects the latest offline edits; fall back to the disk cache.
      SyncEngine.Plan plan = inMemory;
      if (plan == null)
      { PlanCache.Cached c = PlanCache.load(); plan = (c == null) ? null : c.plan; }
      if (plan == null)
      { log("\nKein Abgleich vorhanden — bitte zuerst 'Aktualisieren' klicken.\n"); return; }
      SyncEngine.Result r = SyncEngine.applyCached(plan, dry, listener(task, mon));
      log("\nFertig (Übertragen). created=" + r.created + " deleted=" + r.deleted
          + " cardsAdded=" + r.cardsAdded + " cardsRemoved=" + r.cardsRemoved
          + " errors=" + r.errors.size() + "\n");
      // reflect the post-apply state (foldAppliedIntoCache saved it)
      Display.getDefault().asyncExec(this::loadCachedPlan);
    });
  }

  // ============================================================ task plumbing

  @FunctionalInterface
  private interface Body { void run(BackgroundTask task, ProgressMonitor mon) throws Exception; }

  private void startTask(String name, boolean dryRun, Body body)
  {
    setActionsEnabled(false);
    log("");
    log(name + " gestartet (" + (dryRun ? "Trockenlauf" : "APPLY") + ") …\n");
    if (progress != null && !progress.isDisposed()) { progress.setMaximum(100); progress.setSelection(0); }
    Application.getController().start(new HikvisionBackgroundTask() {
      @Override public void run(ProgressMonitor mon) throws ApplicationException
      {
        try
        {
          mon.setStatusText(name + " läuft …");
          HikvisionSettings.SETTINGS.toString();  // ensure plugin Settings class is loaded
          body.run(this, mon);
          mon.setStatus(ProgressMonitor.STATUS_DONE);
          mon.setPercentComplete(100);
        }
        catch (InterruptedIOException ie)
        {
          Logger.info(name + " cancelled by user");
          log("\nABGEBROCHEN: " + ie.getMessage() + "\n");
          mon.log("Abgebrochen: " + ie.getMessage());
          mon.setStatus(ProgressMonitor.STATUS_CANCEL);
        }
        catch (Exception e)
        {
          Logger.error(name + " failed", e);
          log("\nFEHLER: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
          mon.log("FEHLER: " + e.getMessage());
          mon.setStatus(ProgressMonitor.STATUS_ERROR);
          throw new ApplicationException(e.getMessage(), e);
        }
        finally
        {
          Display.getDefault().asyncExec(() -> setActionsEnabled(true));
        }
      }
    });
  }

  private de.jost_net.JVerein.hikvision.ProgressListener listener(BackgroundTask task, ProgressMonitor mon)
  {
    return new de.jost_net.JVerein.hikvision.ProgressListener() {
      @Override public void log(String msg)
      {
        if (mon != null) mon.log(msg);
        HikvisionBenutzerView.this.log(msg + "\n");
      }
      @Override public void progress(int done, int total) { progress(done, total, ""); }
      @Override public void progress(int done, int total, String phase)
      {
        if (mon != null)
        {
          int pct = total > 0 ? (int) (100L * Math.min(done, total) / total) : 0;
          mon.setPercentComplete(pct);
          mon.setStatusText(phase + "  " + done + " / " + total);
        }
        Display.getDefault().asyncExec(() -> {
          if (progress != null && !progress.isDisposed())
          {
            int safe = Math.max(total, 1);
            progress.setMaximum(safe); progress.setSelection(Math.min(done, safe));
          }
          if (countLabel != null && !countLabel.isDisposed())
            countLabel.setText(phase + "  " + done + " / " + total + " …");
        });
      }
      @Override public boolean isCancelled() { return task != null && task.isInterrupted(); }
    };
  }

  private void setActionsEnabled(boolean en)
  {
    if (refreshBtn != null && !refreshBtn.isDisposed()) refreshBtn.setEnabled(en);
    if (refreshVisibleBtn != null && !refreshVisibleBtn.isDisposed()) refreshVisibleBtn.setEnabled(en);
    if (syncBtn != null && !syncBtn.isDisposed()) syncBtn.setEnabled(en);
  }

  private void log(String s)
  {
    Display.getDefault().asyncExec(() -> {
      if (logArea != null && !logArea.isDisposed())
      {
        if (s.isEmpty()) logArea.setText("");
        else logArea.append(s);
      }
    });
  }

  private void error(String title, String msg)
  { MessageBox b = new MessageBox(GUI.getShell(), SWT.ICON_ERROR | SWT.OK);
    b.setText(title); b.setMessage(msg); b.open(); }
  private void info(String title, String msg)
  { MessageBox b = new MessageBox(GUI.getShell(), SWT.ICON_INFORMATION | SWT.OK);
    b.setText(title); b.setMessage(msg); b.open(); }
}
