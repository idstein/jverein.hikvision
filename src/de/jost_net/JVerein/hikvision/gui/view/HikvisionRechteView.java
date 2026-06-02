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
 * OpenJVerein > Hikvision > Türrechte
 *
 * Read-only view of the regionPermissionGroup ids in use. The DS-K
 * firmware doesn't expose a name-lookup endpoint for these via ISAPI,
 * so only the numeric id + member count is shown here. Names live in
 * the controller's web UI under Permission Settings.
 */
public class HikvisionRechteView extends AbstractView
{
  @Override
  public void bind() throws Exception
  {
    GUI.getView().setTitle("Zugangssystem Türrechte");
    Composite c = getParent();
    c.setLayout(new GridLayout(1, false));

    Label info = new Label(c, SWT.WRAP);
    info.setText("Region-Permission-Gruppen (Türrechte) in Verwendung. Namen werden — sofern "
        + "verfügbar — aus dem UserRightPlanTemplate des Controllers gelesen; Regionen ohne "
        + "Template-Namen werden als 'Region N' angezeigt.");
    GridData igd = new GridData(SWT.FILL, SWT.CENTER, true, false);
    igd.widthHint = 800;
    info.setLayoutData(igd);

    HikvisionGroupCatalog cat = HikvisionGroupCatalog.fromCache();
    Label ts = new Label(c, SWT.NONE);
    ts.setText(cat.timestamp == 0 ? "(kein Cache — bitte zuerst Benutzer aktualisieren)"
        : "Stand: " + formatStamp(cat.timestamp));
    ts.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    Table table = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setHeaderVisible(true); table.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true);
    tgd.heightHint = 360; tgd.widthHint = 760;
    table.setLayoutData(tgd);

    TableColumn c1 = new TableColumn(table, SWT.RIGHT); c1.setText("Region-ID");   c1.setWidth(90);
    TableColumn c2 = new TableColumn(table, SWT.LEFT);  c2.setText("Name");         c2.setWidth(240);
    TableColumn c3 = new TableColumn(table, SWT.RIGHT); c3.setText("Benutzer");     c3.setWidth(100);
    TableSorter.install(table);

    for (HikvisionGroupCatalog.RegionPermissionGroup g : cat.regions)
    {
      TableItem ti = new TableItem(table, SWT.NONE);
      ti.setText(0, String.valueOf(g.id));
      ti.setText(1, g.name == null || g.name.isEmpty() ? "(unbenannt)" : g.name);
      ti.setText(2, String.valueOf(g.memberCount));
    }
  }

  private static String formatStamp(long ts)
  {
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    return sdf.format(new java.util.Date(ts));
  }
}
