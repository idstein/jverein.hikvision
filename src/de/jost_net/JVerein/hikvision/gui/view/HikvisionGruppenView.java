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
 * OpenJVerein > Hikvision > Organisationsgruppen
 *
 * Read-only view of the current Hikvision user-groups (Mitglieder /
 * Vorstand / Robby Bubble / BSV …) derived from the cached plan.
 * No Hikvision call here — click Aktualisieren on the Benutzer view to
 * refresh the underlying data.
 */
public class HikvisionGruppenView extends AbstractView
{
  @Override
  public void bind() throws Exception
  {
    GUI.getView().setTitle("Zugangssystem Organisationsgruppen");
    Composite c = getParent();
    c.setLayout(new GridLayout(1, false));

    Label info = new Label(c, SWT.WRAP);
    info.setText("Zugangssystem-Benutzergruppen mit Anzahl Mitglieder (aus dem letzten Cache-Stand). "
        + "Über die Benutzer-Sicht 'Aktualisieren' klicken um die Daten neu vom Controller zu laden.");
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

    TableColumn c1 = new TableColumn(table, SWT.LEFT); c1.setText("Gruppe");        c1.setWidth(220);
    TableColumn c2 = new TableColumn(table, SWT.RIGHT); c2.setText("Benutzer");      c2.setWidth(90);
    TableColumn c3 = new TableColumn(table, SWT.LEFT); c3.setText("UUID");           c3.setWidth(380);
    TableSorter.install(table);

    for (HikvisionGroupCatalog.Group g : cat.groups)
    {
      TableItem ti = new TableItem(table, SWT.NONE);
      ti.setText(0, g.name == null ? "" : g.name);
      ti.setText(1, String.valueOf(g.memberCount));
      ti.setText(2, g.uuid == null ? "" : g.uuid);
    }
  }

  private static String formatStamp(long ts)
  {
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    return sdf.format(new java.util.Date(ts));
  }
}
