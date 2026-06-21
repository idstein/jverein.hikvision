package de.jost_net.JVerein.hikvision.ext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import de.jost_net.JVerein.Einstellungen;
import de.jost_net.JVerein.hikvision.ChipStore;
import de.jost_net.JVerein.hikvision.HikvisionGroupCatalog;
import de.jost_net.JVerein.hikvision.HikvisionSettings;
import de.jost_net.JVerein.hikvision.MitgliedAssignments;
import de.jost_net.JVerein.rmi.Mitglied;

/**
 * Modal dialog for editing a Mitglied's plugin-owned assignment
 * (transponder list + Hikvision user group).
 *
 * <p>UI: checkbox table listing every chip in the ChipStore. Each row
 * shows the chip id, its Kartennummer, and — if currently held by some
 * other Mitglied — that Mitglied's name. A search field above the table
 * filters rows incrementally (chip id / Kartennummer / current holder
 * name). Checking a chip already held by someone else triggers a
 * confirmation on Save and atomically moves it.
 *
 * <p>Enforces the single-holder-per-transponder invariant: no two
 * Mitglieder can hold the same chip simultaneously.
 */
public final class AssignmentEditDialog
{
  private AssignmentEditDialog() {}

  /** Holder row used purely for table rendering / filtering. */
  private static class Row
  {
    String chip;
    String cardNo;
    String holderJvId;   // null = unassigned
    String holderName;   // display name if held by another (not this) Mitglied
    boolean checked;
    boolean visible;
  }

  public static boolean open(Shell parent, MitgliedAssignments store, ChipStore chipStore,
                             String jvId, String displayName, String employeeNo, String externe)
  {
    Shell sh = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
    sh.setText("Zuweisung bearbeiten");
    sh.setLayout(new GridLayout(2, false));

    Label hdr = new Label(sh, SWT.WRAP);
    hdr.setText(displayName + "  ·  jv_id=" + jvId
        + (employeeNo == null || employeeNo.isEmpty() ? "" : "  ·  employeeNo=" + employeeNo));
    GridData hdrGd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    hdrGd.widthHint = 760;
    hdr.setLayoutData(hdrGd);

    HikvisionGroupCatalog cat = HikvisionGroupCatalog.fromCache();
    MitgliedAssignments.Assignment existing = store.get(jvId);

    // --- org userGroup (single-select). Default by member/sponsor. ---
    final boolean sponsor = employeeNo != null && employeeNo.startsWith("G");
    final String defaultGroup = sponsor
        ? HikvisionSettings.getSponsorGroupName() : HikvisionSettings.getMemberGroupName();
    String[] groupNames = new String[cat.groups.size()];
    for (int i = 0; i < cat.groups.size(); i++) groupNames[i] = cat.groups.get(i).name;
    new Label(sh, SWT.NONE).setText("Organisationsgruppe");
    final Combo groupCombo = new Combo(sh, SWT.READ_ONLY | SWT.DROP_DOWN);
    groupCombo.setItems(groupNames);
    groupCombo.setLayoutData(gdFill());
    String preGroup = (existing != null && existing.hikvisionGroup != null && !existing.hikvisionGroup.isEmpty())
        ? existing.hikvisionGroup : defaultGroup;
    int gidx = java.util.Arrays.asList(groupNames).indexOf(preGroup);
    if (gidx >= 0) groupCombo.select(gidx);
    else if (groupNames.length > 0) groupCombo.select(0);
    final String initialGroup = groupCombo.getText().trim();   // to detect an explicit change on save

    // --- Berechtigungsgruppen (Türzugang) multi-select ---
    java.util.Set<String> currentRegions = existing == null
        ? new HashSet<>() : new HashSet<>(existing.regionPermissionGroups);
    new Label(sh, SWT.NONE).setText("Berechtigungsgruppen");
    final Table regionTable = new Table(sh, SWT.BORDER | SWT.CHECK | SWT.V_SCROLL);
    GridData rgd = new GridData(SWT.FILL, SWT.FILL, true, false);
    rgd.heightHint = 96; rgd.widthHint = 720;
    regionTable.setLayoutData(rgd);
    if (cat.regions.isEmpty())
    {
      TableItem ti = new TableItem(regionTable, SWT.NONE);
      ti.setText("(keine — bitte erst in den Einstellungen 'Aus Hikvision laden')");
    }
    for (HikvisionGroupCatalog.RegionPermissionGroup rp : cat.regions)
    {
      TableItem ti = new TableItem(regionTable, SWT.NONE);
      String doors = rp.doors.isEmpty() ? "" : "  (" + String.join(", ", rp.doors) + ")";
      ti.setText(rp.displayName() + doors);
      ti.setData(rp);
      if (currentRegions.contains(rp.name)) ti.setChecked(true);
    }

    // --- search filter ---
    new Label(sh, SWT.NONE).setText("Suche");
    Text searchField = new Text(sh, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
    searchField.setMessage("Chip-ID, Kartennummer oder aktueller Besitzer …");
    searchField.setLayoutData(gdFill());

    // --- build row model from ChipStore + assignments ---
    Set<String> currentChipsThis = existing == null ? new HashSet<>() : new HashSet<>(existing.transponder);
    Map<String, List<String>> chipToJv = store.chipToJvIds();
    Set<String> otherHolders = new HashSet<>();
    for (Map.Entry<String, List<String>> e : chipToJv.entrySet())
      for (String j : e.getValue()) if (!j.equals(jvId)) otherHolders.add(j);
    Map<String, String> nameByJvId;
    try { nameByJvId = lookupNames(otherHolders); }
    catch (Exception ex) { nameByJvId = Collections.emptyMap(); }

    List<Row> rows = new ArrayList<>();
    for (String[] cs : chipStore.rows())
    {
      Row r = new Row();
      r.chip = cs[0]; r.cardNo = cs[1];
      List<String> holders = chipToJv.getOrDefault(r.chip, Collections.emptyList());
      for (String j : holders) if (!j.equals(jvId)) { r.holderJvId = j; r.holderName = nameByJvId.get(j); break; }
      r.checked = currentChipsThis.contains(r.chip);
      r.visible = true;
      rows.add(r);
    }
    // Sort: checked first, then unassigned, then held-by-others; alpha within
    rows.sort((a, b) -> {
      int ac = (a.checked ? 0 : (a.holderJvId == null ? 1 : 2));
      int bc = (b.checked ? 0 : (b.holderJvId == null ? 1 : 2));
      if (ac != bc) return ac - bc;
      return naturalCompare(a.chip, b.chip);
    });

    // --- table ---
    Table table = new Table(sh, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setHeaderVisible(true); table.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    tgd.heightHint = 380; tgd.widthHint = 720;
    table.setLayoutData(tgd);
    TableColumn c1 = new TableColumn(table, SWT.LEFT); c1.setText("Transponder");          c1.setWidth(120);
    TableColumn c2 = new TableColumn(table, SWT.LEFT); c2.setText("Kartennummer");         c2.setWidth(180);
    TableColumn c3 = new TableColumn(table, SWT.LEFT); c3.setText("Aktueller Besitzer");   c3.setWidth(280);

    Runnable refreshTable = () -> {
      table.removeAll();
      for (Row r : rows)
      {
        if (!r.visible) continue;
        TableItem ti = new TableItem(table, SWT.NONE);
        ti.setText(0, r.chip);
        ti.setText(1, r.cardNo);
        if (r.holderJvId != null)
          ti.setText(2, (r.holderName == null ? "(unbekannt)" : r.holderName)
              + " — wird umgezogen wenn ausgewählt");
        else ti.setText(2, "");
        ti.setChecked(r.checked);
        ti.setData(r);
      }
    };
    refreshTable.run();

    // Sync row.checked when user toggles via the table — required because
    // applyEdit reads from the row list (table state isn't authoritative
    // after a filter clears the table).
    table.addListener(SWT.Selection, ev -> {
      if (ev.detail != SWT.CHECK) return;
      TableItem it = (TableItem) ev.item;
      Row r = (Row) it.getData();
      if (r != null) r.checked = it.getChecked();
    });

    // --- search filter wiring ---
    searchField.addModifyListener(e -> {
      String q = searchField.getText().trim().toLowerCase();
      for (Row r : rows)
      {
        if (q.isEmpty()) { r.visible = true; continue; }
        String haystack = (r.chip + " " + r.cardNo + " " + (r.holderName == null ? "" : r.holderName)).toLowerCase();
        r.visible = haystack.contains(q);
      }
      refreshTable.run();
    });

    // --- status / hint line ---
    Label hint = new Label(sh, SWT.WRAP);
    hint.setText("Hinweis: Bereits zugewiesene Transponder zeigen den aktuellen Besitzer in Spalte 3. "
        + "Beim Speichern wird für jeden Konflikt eine Bestätigung abgefragt; pro Transponder darf nur eine Person Besitzer sein.");
    GridData hintGd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    hintGd.widthHint = 720;
    hint.setLayoutData(hintGd);

    // --- buttons ---
    Composite btnRow = new Composite(sh, SWT.NONE);
    btnRow.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false, 2, 1));
    btnRow.setLayout(new GridLayout(2, true));
    Button okBtn = new Button(btnRow, SWT.PUSH);
    okBtn.setText("Speichern");
    okBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Button cancelBtn = new Button(btnRow, SWT.PUSH);
    cancelBtn.setText("Abbrechen");
    cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    final boolean[] saved = { false };
    okBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e)
      {
        try
        {
          List<String> selected = new ArrayList<>();
          Map<String, String> conflicts = new LinkedHashMap<>();
          for (Row r : rows)
          {
            if (!r.checked) continue;
            selected.add(r.chip);
            if (r.holderJvId != null) conflicts.put(r.chip, r.holderJvId);
          }

          List<String> selectedRegions = new ArrayList<>();
          for (TableItem rti : regionTable.getItems())
          {
            if (!rti.getChecked()) continue;
            Object data = rti.getData();
            if (data instanceof HikvisionGroupCatalog.RegionPermissionGroup)
            {
              String rn = ((HikvisionGroupCatalog.RegionPermissionGroup) data).name;
              if (rn != null && !rn.isEmpty() && !selectedRegions.contains(rn)) selectedRegions.add(rn);
            }
          }

          if (!conflicts.isEmpty())
          {
            StringBuilder m = new StringBuilder("Folgende Transponder sind bereits anderen Mitgliedern zugewiesen:\n\n");
            for (Map.Entry<String, String> ce : conflicts.entrySet())
            {
              MitgliedAssignments.Assignment from = store.get(ce.getValue());
              String otherName = "(unbekannt)";
              try { Map<String, String> nm = lookupNames(Collections.singleton(ce.getValue()));
                String n = nm.get(ce.getValue()); if (n != null && !n.isEmpty()) otherName = n; }
              catch (Exception ignore) {}
              m.append("  ").append(ce.getKey()).append(" → ").append(otherName)
               .append("  (jv_id=").append(ce.getValue()).append(")\n");
            }
            m.append("\nAuf ").append(displayName).append(" umziehen?\n")
             .append("(Werden bei den vorherigen Besitzern entfernt — pro Transponder darf es nur einen Besitzer geben.)");
            MessageBox box = new MessageBox(sh, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
            box.setText("Transponder umziehen?"); box.setMessage(m.toString());
            if (box.open() != SWT.YES) return;
          }

          // Apply
          for (Map.Entry<String, String> ce : conflicts.entrySet())
          {
            MitgliedAssignments.Assignment from = store.get(ce.getValue());
            if (from != null) { from.transponder.remove(ce.getKey()); store.touch(from.jvId); }
          }
          MitgliedAssignments.Assignment my = store.get(jvId);
          if (my == null) { my = new MitgliedAssignments.Assignment(jvId); store.put(my); }
          my.employeeNo = employeeNo == null ? "" : employeeNo;
          my.externe = externe == null ? "" : externe;
          my.transponder.clear();
          my.transponder.addAll(selected);
          String chosenGroup = groupCombo.getText().trim();
          if (chosenGroup.isEmpty()) chosenGroup = defaultGroup;
          my.hikvisionGroup = chosenGroup;
          // A Berechtigungsgruppe equal to the Org-Gruppe is redundant — the
          // Org-Gruppe (userGroup) already grants that group's door access.
          // Drop it so we never push a pointless regionPermissionGroupIDList.
          final String orgGroup = chosenGroup;
          selectedRegions.removeIf(rn -> rn.equalsIgnoreCase(orgGroup));
          my.regionPermissionGroups.clear();
          my.regionPermissionGroups.addAll(selectedRegions);
          // Only mark as explicitly managed when the user actually changed the
          // group — otherwise leave it to the automatic rule (so e.g. opening a
          // member who is wrongly in BSV and saving doesn't "bless" BSV).
          if (!chosenGroup.equals(initialGroup)) my.groupManaged = true;
          store.touch(my.jvId);
          store.save();

          // Mirror the affected Mitglieder's transponder CSV back into the
          // jverein Zusatzfeld so jverein's Mitglied detail view stays in
          // sync. Includes any prior holders we just took chips from.
          try
          {
            store.writeAssignmentToZusatzfeld(jvId);
            for (String otherJv : conflicts.values())
              store.writeAssignmentToZusatzfeld(otherJv);
          }
          catch (Exception ex)
          { msg(sh, SWT.ICON_WARNING, "Rückschreiben in jverein-Zusatzfeld fehlgeschlagen",
              "Zuweisung wurde in MitgliedAssignments gespeichert, aber Rückschreiben in jverein lieferte: "
              + ex.getClass().getSimpleName() + ": " + ex.getMessage()); }

          saved[0] = true; sh.close();
        }
        catch (Exception ex)
        { msg(sh, SWT.ICON_ERROR, "Fehler", ex.getClass().getSimpleName() + ": " + ex.getMessage()); }
      }
    });
    cancelBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { sh.close(); }
    });

    sh.pack(); sh.open();
    Display d = sh.getDisplay();
    while (!sh.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
    return saved[0];
  }

  /** Lookup display names for the given jverein IDs. */
  private static Map<String, String> lookupNames(java.util.Collection<String> jvIds) throws Exception
  {
    Map<String, String> out = new HashMap<>();
    if (jvIds.isEmpty()) return out;
    Set<String> need = new HashSet<>(jvIds);
    de.willuhn.datasource.rmi.DBIterator<Mitglied> it = Einstellungen.getDBService().createList(Mitglied.class);
    while (it.hasNext())
    {
      Mitglied m = (Mitglied) it.next();
      if (!need.contains(m.getID())) continue;
      String vn = m.getVorname() == null ? "" : m.getVorname().trim();
      String nn = m.getName() == null ? "" : m.getName().trim();
      out.put(m.getID(), (vn + " " + nn).trim());
    }
    return out;
  }

  /** "1" < "2" < "10" rather than "1" < "10" < "2". Falls back to lexicographic. */
  private static int naturalCompare(String a, String b)
  {
    try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
    catch (NumberFormatException nfe) { return a.compareToIgnoreCase(b); }
  }

  private static GridData gdFill()
  {
    GridData g = new GridData(SWT.FILL, SWT.CENTER, true, false);
    g.widthHint = 720;
    return g;
  }

  private static void msg(Shell parent, int style, String title, String text)
  {
    MessageBox b = new MessageBox(parent, style | SWT.OK);
    b.setText(title); b.setMessage(text); b.open();
  }
}
