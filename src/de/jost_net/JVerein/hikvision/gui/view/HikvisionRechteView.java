package de.jost_net.JVerein.hikvision.gui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import de.jost_net.JVerein.hikvision.HikvisionGroupCatalog;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.GUI;

/**
 * OpenJVerein &gt; Zugangssystem &gt; Berechtigungsgruppen
 *
 * Read-only view of the controller's region-permission groups
 * ({@code regionPermissionGroupIDList} / DoorRegionMgr) — the groups that
 * actually grant door access. Names and door lists come straight from the
 * controller now (no manual naming needed). A member is granted access by
 * assigning one or more of these in the Benutzer view's "Zuweisung
 * bearbeiten" dialog.
 *
 * No Hikvision call here — click Aktualisieren on the Benutzer view (or
 * "Aus Hikvision laden" in the Einstellungen) to refresh the underlying data.
 */
public class HikvisionRechteView extends AbstractView
{
  @Override
  public void bind() throws Exception
  {
    GUI.getView().setTitle("Zugangssystem Berechtigungsgruppen");
    Composite c = getParent();
    c.setLayout(new GridLayout(1, false));

    Label info = new Label(c, SWT.WRAP);
    info.setText("Berechtigungsgruppen (Türzugang) des Controllers mit ihren Türen und der Anzahl "
        + "direkt zugewiesener Benutzer. Diese Gruppen werden einzelnen Mitgliedern über "
        + "'Benutzer → Zuweisung bearbeiten' zugewiesen. Zum Neuladen in der Benutzer-Sicht "
        + "'Aktualisieren' klicken.");
    GridData igd = new GridData(SWT.FILL, SWT.CENTER, true, false);
    igd.widthHint = 820;
    info.setLayoutData(igd);

    HikvisionGroupCatalog cat = HikvisionGroupCatalog.fromCache();
    Label ts = new Label(c, SWT.NONE);
    ts.setText(cat.timestamp == 0 ? "(kein Cache — bitte zuerst Benutzer aktualisieren)"
        : "Stand: " + formatStamp(cat.timestamp));
    ts.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    Table table = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setHeaderVisible(true); table.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true);
    tgd.heightHint = 360; tgd.widthHint = 820;
    table.setLayoutData(tgd);

    TableColumn c1 = new TableColumn(table, SWT.RIGHT); c1.setText("ID");          c1.setWidth(60);
    TableColumn c2 = new TableColumn(table, SWT.LEFT);  c2.setText("Name");        c2.setWidth(200);
    TableColumn c3 = new TableColumn(table, SWT.LEFT);  c3.setText("Türen");       c3.setWidth(280);
    TableColumn c4 = new TableColumn(table, SWT.RIGHT); c4.setText("Benutzer");    c4.setWidth(90);
    TableColumn c5 = new TableColumn(table, SWT.RIGHT); c5.setText("Org.-Gruppen"); c5.setWidth(100);
    TableSorter.install(table);

    for (HikvisionGroupCatalog.RegionPermissionGroup g : cat.regions)
    {
      TableItem ti = new TableItem(table, SWT.NONE);
      ti.setText(0, String.valueOf(g.id));
      ti.setText(1, g.displayName());
      ti.setText(2, String.join(", ", g.doors));
      ti.setText(3, String.valueOf(g.memberCount));
      ti.setText(4, String.valueOf(g.userGroupCount));
    }
  }

  private static String formatStamp(long ts)
  {
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    return sdf.format(new java.util.Date(ts));
  }
}
