package de.jost_net.JVerein.hikvision.gui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import de.jost_net.JVerein.hikvision.HikvisionGroupCatalog;
import de.jost_net.JVerein.hikvision.HikvisionSettings;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.GUI;

/**
 * OpenJVerein &gt; Zugangssystem &gt; Türrechte
 *
 * Lists the region-permission-group IDs in use on the controller with
 * their member counts. Hikvision DS-K firmware doesn't expose Permission
 * Group display names via ISAPI — they only live in the controller's
 * web UI — so the Name column is editable here: double-click a cell,
 * type, Enter / focus out to save. Names persist in HikvisionSettings
 * and propagate to the Settings dropdown.
 */
public class HikvisionRechteView extends AbstractView
{
  private Table table;

  @Override
  public void bind() throws Exception
  {
    GUI.getView().setTitle("Zugangssystem Türrechte");
    Composite c = getParent();
    c.setLayout(new GridLayout(1, false));

    Label info = new Label(c, SWT.WRAP);
    info.setText("Region-Permission-Gruppen (Türrechte) in Verwendung. Hikvision DS-K stellt "
        + "die im Web-UI vergebenen Namen nicht via ISAPI bereit — bitte einmalig pflegen: "
        + "Doppelklick in die Spalte 'Name', Text eingeben, Enter / Klick ausserhalb zum Speichern. "
        + "Die Namen erscheinen anschließend auch in der Region-Permission-Dropdown der Einstellungen.");
    GridData igd = new GridData(SWT.FILL, SWT.CENTER, true, false);
    igd.widthHint = 820;
    info.setLayoutData(igd);

    HikvisionGroupCatalog cat = HikvisionGroupCatalog.fromCache();
    Label ts = new Label(c, SWT.NONE);
    ts.setText(cat.timestamp == 0 ? "(kein Cache — bitte zuerst Benutzer aktualisieren)"
        : "Stand: " + formatStamp(cat.timestamp));
    ts.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    table = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setHeaderVisible(true); table.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true);
    tgd.heightHint = 360; tgd.widthHint = 760;
    table.setLayoutData(tgd);

    TableColumn c1 = new TableColumn(table, SWT.RIGHT); c1.setText("Region-ID");   c1.setWidth(90);
    TableColumn c2 = new TableColumn(table, SWT.LEFT);  c2.setText("Name");         c2.setWidth(260);
    TableColumn c3 = new TableColumn(table, SWT.RIGHT); c3.setText("Benutzer");     c3.setWidth(100);
    TableSorter.install(table);

    for (HikvisionGroupCatalog.RegionPermissionGroup g : cat.regions)
    {
      TableItem ti = new TableItem(table, SWT.NONE);
      ti.setText(0, String.valueOf(g.id));
      ti.setText(1, (g.name == null || g.name.isEmpty()) ? "(unbenannt)" : g.name);
      ti.setText(2, String.valueOf(g.memberCount));
    }

    installInlineNameEditor(table);
  }

  /**
   * Wires up double-click → inline Text editor on column 1 (Name). The
   * Text widget overlays the cell via a {@link TableEditor}. Enter or
   * focus-loss commits the edit; the typed value is stored as the
   * region's name in HikvisionSettings keyed by the row's Region-ID
   * (column 0), and the cell shown in the table updates immediately.
   */
  private void installInlineNameEditor(Table tbl)
  {
    final TableEditor editor = new TableEditor(tbl);
    editor.horizontalAlignment = SWT.LEFT;
    editor.grabHorizontal = true;
    editor.minimumWidth = 50;
    final int NAME_COL = 1;

    tbl.addMouseListener(new MouseAdapter() {
      @Override public void mouseDoubleClick(MouseEvent e)
      {
        TableItem item = tbl.getItem(new org.eclipse.swt.graphics.Point(e.x, e.y));
        if (item == null) return;
        // Only allow editing the Name column
        Rectangle rect = item.getBounds(NAME_COL);
        if (!rect.contains(e.x, e.y)) return;

        Text text = new Text(tbl, SWT.NONE);
        String current = item.getText(NAME_COL);
        text.setText("(unbenannt)".equals(current) ? "" : current);
        text.selectAll();
        text.setFocus();

        Runnable commit = () -> {
          if (text.isDisposed()) return;
          String newName = text.getText().trim();
          int regionId;
          try { regionId = Integer.parseInt(item.getText(0)); }
          catch (NumberFormatException nfe) { text.dispose(); return; }
          HikvisionSettings.setRegionName(regionId, newName);
          item.setText(NAME_COL, newName.isEmpty() ? "(unbenannt)" : newName);
          text.dispose();
        };
        text.addListener(SWT.Traverse, ev -> {
          if (ev.detail == SWT.TRAVERSE_RETURN) { commit.run(); ev.doit = false; }
          else if (ev.detail == SWT.TRAVERSE_ESCAPE) { text.dispose(); ev.doit = false; }
        });
        text.addFocusListener(new FocusAdapter() {
          @Override public void focusLost(FocusEvent fe) { commit.run(); }
        });
        editor.setEditor(text, item, NAME_COL);
      }
    });
  }

  private static String formatStamp(long ts)
  {
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    return sdf.format(new java.util.Date(ts));
  }
}
