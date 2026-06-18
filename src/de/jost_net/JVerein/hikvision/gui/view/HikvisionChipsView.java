package de.jost_net.JVerein.hikvision.gui.view;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import de.jost_net.JVerein.Einstellungen;
import de.jost_net.JVerein.hikvision.ChipStore;
import de.jost_net.JVerein.hikvision.MitgliedAssignments;
import de.jost_net.JVerein.hikvision.PlanCache;
import de.jost_net.JVerein.hikvision.SyncEngine;
import de.jost_net.JVerein.hikvision.ext.ChipEditDialog;
import de.jost_net.JVerein.rmi.Mitglied;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.jameica.messaging.StatusBarMessage;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;

/**
 * OpenJVerein > Zugangssystem > Transponder
 *
 * Manage chip ↔ Kartennummer mappings persisted by {@link ChipStore}.
 * Local-only UI; never hits Hikvision.
 */
public class HikvisionChipsView extends AbstractView
{
  private Table table;
  private ChipStore store;
  private Set<String> chipFilter;   // null = no filter; set = restrict rows to these chip ids
  private Label filterStatus;
  private Button clearFilterBtn;
  private Text searchField;          // live full-text search across all 4 columns

  @Override
  public void bind() throws Exception
  {
    GUI.getView().setTitle("Zugangssystem Transponder");
    try { store = ChipStore.defaultStore(); }
    catch (Exception e)
    {
      Logger.error("ChipStore konnte nicht geladen werden", e);
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(
          "ChipStore konnte nicht geladen werden: " + e.getMessage(), StatusBarMessage.TYPE_ERROR));
      return;
    }

    // Optional chip-id filter context, set by HikvisionBenutzerView's
    // "Transponder anzeigen…" jump. Null when opened from the nav tree.
    Object ctx = getCurrentObject();
    if (ctx instanceof Set)
    {
      Set<?> raw = (Set<?>) ctx;
      Set<String> ids = new HashSet<>();
      for (Object o : raw) if (o != null) ids.add(o.toString());
      if (!ids.isEmpty()) chipFilter = ids;
    }

    Composite c = getParent();
    c.setLayout(new GridLayout(2, false));

    Label info = new Label(c, SWT.WRAP);
    info.setText("Transponder ↔ Kartennummer + aktuelle Zuweisung (jverein-Seite aus "
        + "MitgliedAssignments, Hikvision-Seite aus dem letzten Aktualisierungslauf).");
    GridData infoGd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    infoGd.widthHint = 600;
    info.setLayoutData(infoGd);

    Composite searchRow = new Composite(c, SWT.NONE);
    searchRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    searchRow.setLayout(new GridLayout(2, false));
    new Label(searchRow, SWT.NONE).setText("Suche:");
    searchField = new Text(searchRow, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
    searchField.setMessage("Transponder, Kartennummer oder Name …");
    searchField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    searchField.addModifyListener(e -> refresh());

    Composite filterRow = new Composite(c, SWT.NONE);
    filterRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    filterRow.setLayout(new GridLayout(2, false));
    filterStatus = new Label(filterRow, SWT.NONE);
    filterStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    clearFilterBtn = new Button(filterRow, SWT.PUSH);
    clearFilterBtn.setText("Filter aufheben");
    clearFilterBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { chipFilter = null; updateFilterStatus(); refresh(); }
    });
    updateFilterStatus();

    table = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
    table.setHeaderVisible(true);
    table.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    tgd.heightHint = 500; tgd.widthHint = 900;
    table.setLayoutData(tgd);
    TableColumn c1 = new TableColumn(table, SWT.LEFT); c1.setText("Transponder");       c1.setWidth(120);
    TableColumn c2 = new TableColumn(table, SWT.LEFT); c2.setText("Kartennummer");      c2.setWidth(180);
    TableColumn c3 = new TableColumn(table, SWT.LEFT); c3.setText("JVerein-Mitglied");  c3.setWidth(240);
    TableColumn c4 = new TableColumn(table, SWT.LEFT); c4.setText("Hikvision-Benutzer"); c4.setWidth(260);
    TableSorter.install(table);
    refresh();
    table.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetDefaultSelected(SelectionEvent e) { onEdit(); }
    });

    Composite btnRow = new Composite(c, SWT.NONE);
    btnRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    btnRow.setLayout(new GridLayout(5, false));
    mk(btnRow, "Hinzufügen…",            () -> onAdd());
    mk(btnRow, "Bearbeiten…",            () -> onEdit());
    mk(btnRow, "Löschen",                 () -> onDelete());
    mk(btnRow, "Aus CSV importieren…",   () -> onImport());
    mk(btnRow, "Als CSV exportieren…",   () -> onExport());
  }

  @FunctionalInterface private interface OnClick { void onClick(); }

  private void mk(Composite parent, String label, OnClick action)
  {
    Button b = new Button(parent, SWT.PUSH);
    b.setText(label);
    b.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    b.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { action.onClick(); }
    });
  }

  private void refresh()
  {
    if (table == null || table.isDisposed()) return;
    table.removeAll();

    Map<String, String> jvByChip  = buildJvereinNameByChip();
    Map<String, String> hikByCard = buildHikvisionNameByCardNo();
    String q = (searchField == null || searchField.isDisposed()) ? "" : searchField.getText().trim().toLowerCase();

    List<String[]> rows = store.rows();
    for (String[] row : rows)
    {
      String chip = row[0], cardNo = row[1];
      if (chipFilter != null && !chipFilter.contains(chip)) continue;
      String jv = jvByChip.get(chip);
      String hk = hikByCard.get(cardNo);
      if (!q.isEmpty())
      {
        String haystack = (chip + " " + cardNo + " " + (jv == null ? "" : jv) + " " + (hk == null ? "" : hk)).toLowerCase();
        if (!haystack.contains(q)) continue;
      }
      TableItem ti = new TableItem(table, SWT.NONE);
      ti.setText(0, chip);
      ti.setText(1, cardNo);
      ti.setText(2, jv == null ? "" : jv);
      ti.setText(3, hk == null ? "" : hk);
    }
    TableSorter.reapplyIfSorted(table);
  }

  private void updateFilterStatus()
  {
    if (filterStatus == null || filterStatus.isDisposed()) return;
    if (chipFilter == null || chipFilter.isEmpty())
    { filterStatus.setText(""); clearFilterBtn.setEnabled(false); }
    else
    { filterStatus.setText("Gefiltert auf " + chipFilter.size() + " Transponder: "
        + String.join(", ", chipFilter));
      clearFilterBtn.setEnabled(true); }
  }

  /** chip → jverein Mitglied name(s) from MitgliedAssignments store. Empty
   *  map if store is missing (pre-migration). */
  private Map<String, String> buildJvereinNameByChip()
  {
    Map<String, String> out = new HashMap<>();
    try
    {
      MitgliedAssignments asn = MitgliedAssignments.load();
      if (asn.size() == 0) return out;
      Map<String, List<String>> chipToJv = asn.chipToJvIds();
      if (chipToJv.isEmpty()) return out;

      Map<String, String> nameByJvId = new HashMap<>();
      DBIterator<Mitglied> it = Einstellungen.getDBService().createList(Mitglied.class);
      while (it.hasNext())
      {
        Mitglied m = (Mitglied) it.next();
        String vn = m.getVorname() == null ? "" : m.getVorname().trim();
        String nn = m.getName() == null ? "" : m.getName().trim();
        nameByJvId.put(m.getID(), (vn + " " + nn).trim());
      }

      for (Map.Entry<String, List<String>> e : chipToJv.entrySet())
      {
        List<String> names = new ArrayList<>();
        for (String jvId : e.getValue())
        {
          String n = nameByJvId.get(jvId);
          names.add(n == null || n.isEmpty() ? ("jv_id=" + jvId) : n);
        }
        out.put(e.getKey(), String.join(", ", names));
      }
    }
    catch (Exception e)
    { Logger.warn("jverein-Namen für Transponder-Spalte nicht aufbaubar: " + e.getMessage()); }
    return out;
  }

  /** Kartennummer → "Name (employeeNo)" from the last Hikvision fetch
   *  (PlanCache). Empty map if PlanCache is missing or empty. */
  private Map<String, String> buildHikvisionNameByCardNo()
  {
    Map<String, String> out = new HashMap<>();
    PlanCache.Cached cached = PlanCache.load();
    if (cached == null || cached.plan == null) return out;
    for (SyncEngine.PlanRow r : cached.plan.rows)
    {
      if (r.currentCards == null || r.currentCards.isEmpty()) continue;
      String label = (r.name == null ? "" : r.name).trim();
      if (r.employeeNo != null && !r.employeeNo.isEmpty())
        label = (label.isEmpty() ? "" : label + " ") + "(" + r.employeeNo + ")";
      for (String card : r.currentCards)
        if (card != null && !card.isEmpty()) out.put(card, label);
    }
    return out;
  }


  private void onAdd()
  {
    String[] vals = ChipEditDialog.open(GUI.getShell(), "Transponder hinzufügen", "", "");
    if (vals == null) return;
    try { store.put(vals[0], vals[1]); store.save(); refresh(); }
    catch (Exception e) { err("Hinzufügen fehlgeschlagen", e.getMessage()); }
  }

  private void onEdit()
  {
    int idx = table.getSelectionIndex(); if (idx < 0) return;
    TableItem ti = table.getItem(idx);
    String oldChip = ti.getText(0), oldCard = ti.getText(1);
    String[] vals = ChipEditDialog.open(GUI.getShell(), "Transponder bearbeiten", oldChip, oldCard);
    if (vals == null) return;
    try
    {
      if (!vals[0].equals(oldChip)) store.removeByChip(oldChip);
      store.put(vals[0], vals[1]); store.save(); refresh();
    }
    catch (Exception e) { err("Bearbeiten fehlgeschlagen", e.getMessage()); }
  }

  private void onDelete()
  {
    int idx = table.getSelectionIndex(); if (idx < 0) return;
    String chip = table.getItem(idx).getText(0);
    if (!confirm("Löschen", "Transponder-Eintrag '" + chip + "' wirklich löschen?")) return;
    try { store.removeByChip(chip); store.save(); refresh(); }
    catch (Exception e) { err("Löschen fehlgeschlagen", e.getMessage()); }
  }

  private void onImport()
  {
    FileDialog fd = new FileDialog(GUI.getShell(), SWT.OPEN);
    fd.setText("Transponder-CSV importieren");
    fd.setFilterExtensions(new String[] { "*.csv", "*.*" });
    String path = fd.open(); if (path == null) return;
    boolean overwrite = confirm("Import-Modus",
        "Bestehende Transponder überschreiben?\n\nJa = überschreiben falls Transponder existiert\nNein = nur neue hinzufügen");
    try
    {
      int[] r = store.importCsv(new File(path), overwrite); refresh();
      info("Import abgeschlossen", "Hinzugefügt: " + r[0] + "\nAktualisiert: " + r[1] + "\nÜbersprungen: " + r[2]);
    }
    catch (Exception e) { err("Import fehlgeschlagen", e.getMessage()); }
  }

  private void onExport()
  {
    FileDialog fd = new FileDialog(GUI.getShell(), SWT.SAVE);
    fd.setText("Transponder-CSV exportieren"); fd.setFileName("chip_kartennummer.csv");
    fd.setFilterExtensions(new String[] { "*.csv", "*.*" });
    String path = fd.open(); if (path == null) return;
    try { store.exportCsv(new File(path)); info("Export abgeschlossen", store.size() + " Einträge geschrieben"); }
    catch (Exception e) { err("Export fehlgeschlagen", e.getMessage()); }
  }

  private boolean confirm(String t, String m)
  { MessageBox b = new MessageBox(GUI.getShell(), SWT.ICON_WARNING | SWT.YES | SWT.NO);
    b.setText(t); b.setMessage(m); return b.open() == SWT.YES; }
  private void err(String t, String m)
  { MessageBox b = new MessageBox(GUI.getShell(), SWT.ICON_ERROR | SWT.OK);
    b.setText(t); b.setMessage(m); b.open(); }
  private void info(String t, String m)
  { MessageBox b = new MessageBox(GUI.getShell(), SWT.ICON_INFORMATION | SWT.OK);
    b.setText(t); b.setMessage(m); b.open(); }
}
