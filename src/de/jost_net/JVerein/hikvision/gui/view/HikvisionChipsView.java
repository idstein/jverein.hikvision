package de.jost_net.JVerein.hikvision.gui.view;

import java.io.File;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import de.jost_net.JVerein.hikvision.ChipStore;
import de.jost_net.JVerein.hikvision.ext.ChipEditDialog;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.jameica.messaging.StatusBarMessage;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;

/**
 * OpenJVerein > Hikvision > Chips
 *
 * Manage chip ↔ Kartennummer mappings persisted by {@link ChipStore}.
 * Local-only UI; never hits Hikvision.
 */
public class HikvisionChipsView extends AbstractView
{
  private Table table;
  private ChipStore store;

  @Override
  public void bind() throws Exception
  {
    GUI.getView().setTitle("Zugangssystem Chips");
    try { store = ChipStore.defaultStore(); }
    catch (Exception e)
    {
      Logger.error("ChipStore konnte nicht geladen werden", e);
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(
          "ChipStore konnte nicht geladen werden: " + e.getMessage(), StatusBarMessage.TYPE_ERROR));
      return;
    }

    Composite c = getParent();
    c.setLayout(new GridLayout(2, false));

    Label info = new Label(c, SWT.WRAP);
    info.setText("Chip ↔ Kartennummer-Zuordnungen werden in Jameica gespeichert "
        + "(cfg/Chips.json). Für Backup oder externe Verarbeitung können sie "
        + "als CSV exportiert / importiert werden.");
    GridData infoGd = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    infoGd.widthHint = 600;
    info.setLayoutData(infoGd);

    table = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
    table.setHeaderVisible(true);
    table.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
    tgd.heightHint = 500; tgd.widthHint = 600;
    table.setLayoutData(tgd);
    TableColumn c1 = new TableColumn(table, SWT.LEFT); c1.setText("Chip"); c1.setWidth(180);
    TableColumn c2 = new TableColumn(table, SWT.LEFT); c2.setText("Kartennummer"); c2.setWidth(240);
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
    List<String[]> rows = store.rows();
    for (String[] row : rows)
    {
      TableItem ti = new TableItem(table, SWT.NONE);
      ti.setText(0, row[0]); ti.setText(1, row[1]);
    }
    TableSorter.reapplyIfSorted(table);
  }

  private void onAdd()
  {
    String[] vals = ChipEditDialog.open(Display.getDefault().getActiveShell(), "Chip hinzufügen", "", "");
    if (vals == null) return;
    try { store.put(vals[0], vals[1]); store.save(); refresh(); }
    catch (Exception e) { err("Hinzufügen fehlgeschlagen", e.getMessage()); }
  }

  private void onEdit()
  {
    int idx = table.getSelectionIndex(); if (idx < 0) return;
    TableItem ti = table.getItem(idx);
    String oldChip = ti.getText(0), oldCard = ti.getText(1);
    String[] vals = ChipEditDialog.open(Display.getDefault().getActiveShell(), "Chip bearbeiten", oldChip, oldCard);
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
    if (!confirm("Löschen", "Chip-Eintrag '" + chip + "' wirklich löschen?")) return;
    try { store.removeByChip(chip); store.save(); refresh(); }
    catch (Exception e) { err("Löschen fehlgeschlagen", e.getMessage()); }
  }

  private void onImport()
  {
    FileDialog fd = new FileDialog(Display.getDefault().getActiveShell(), SWT.OPEN);
    fd.setText("Chip-CSV importieren");
    fd.setFilterExtensions(new String[] { "*.csv", "*.*" });
    String path = fd.open(); if (path == null) return;
    boolean overwrite = confirm("Import-Modus",
        "Bestehende Chips überschreiben?\n\nJa = überschreiben falls Chip existiert\nNein = nur neue hinzufügen");
    try
    {
      int[] r = store.importCsv(new File(path), overwrite); refresh();
      info("Import abgeschlossen", "Hinzugefügt: " + r[0] + "\nAktualisiert: " + r[1] + "\nÜbersprungen: " + r[2]);
    }
    catch (Exception e) { err("Import fehlgeschlagen", e.getMessage()); }
  }

  private void onExport()
  {
    FileDialog fd = new FileDialog(Display.getDefault().getActiveShell(), SWT.SAVE);
    fd.setText("Chip-CSV exportieren"); fd.setFileName("chip_kartennummer.csv");
    fd.setFilterExtensions(new String[] { "*.csv", "*.*" });
    String path = fd.open(); if (path == null) return;
    try { store.exportCsv(new File(path)); info("Export abgeschlossen", store.size() + " Einträge geschrieben"); }
    catch (Exception e) { err("Export fehlgeschlagen", e.getMessage()); }
  }

  private boolean confirm(String t, String m)
  { MessageBox b = new MessageBox(Display.getDefault().getActiveShell(), SWT.ICON_WARNING | SWT.YES | SWT.NO);
    b.setText(t); b.setMessage(m); return b.open() == SWT.YES; }
  private void err(String t, String m)
  { MessageBox b = new MessageBox(Display.getDefault().getActiveShell(), SWT.ICON_ERROR | SWT.OK);
    b.setText(t); b.setMessage(m); b.open(); }
  private void info(String t, String m)
  { MessageBox b = new MessageBox(Display.getDefault().getActiveShell(), SWT.ICON_INFORMATION | SWT.OK);
    b.setText(t); b.setMessage(m); b.open(); }
}
