package de.jost_net.JVerein.hikvision.gui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import de.jost_net.JVerein.hikvision.HikvisionGroupCatalog;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.logging.Logger;

/**
 * OpenJVerein &gt; Zugangssystem &gt; Berechtigungsgruppen
 *
 * View of the controller's region-permission groups
 * ({@code regionPermissionGroupIDList} / DoorRegionMgr) — the groups that
 * actually grant door access. A member is granted access by assigning one or
 * more of these in the Benutzer view's "Zuweisung bearbeiten" dialog.
 *
 * Renders from the local catalog cache; "Aktualisieren" reloads ONLY the
 * Berechtigungsgruppen from the controller (the Organisationsgruppen are left
 * untouched — those have their own view). The Benutzer "Aktualisieren" no
 * longer touches this list.
 */
public class HikvisionRechteView extends AbstractView
{
  private Table table;
  private Label tsLabel;
  private Button refreshBtn;

  @Override
  public void bind() throws Exception
  {
    GUI.getView().setTitle("Zugangssystem Berechtigungsgruppen");
    Composite c = getParent();
    c.setLayout(new GridLayout(1, false));

    Label info = new Label(c, SWT.WRAP);
    info.setText("Berechtigungsgruppen (Türzugang) des Controllers mit ihren Türen und der Anzahl "
        + "direkt zugewiesener Benutzer. Diese Gruppen werden einzelnen Mitgliedern über "
        + "'Benutzer → Zuweisung bearbeiten' zugewiesen. 'Aktualisieren' lädt nur die "
        + "Berechtigungsgruppen neu vom Controller.");
    GridData igd = new GridData(SWT.FILL, SWT.CENTER, true, false);
    igd.widthHint = 820;
    info.setLayoutData(igd);

    Composite toolbar = new Composite(c, SWT.NONE);
    toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    toolbar.setLayout(new GridLayout(2, false));
    refreshBtn = new Button(toolbar, SWT.PUSH);
    refreshBtn.setText("Aktualisieren");
    refreshBtn.setToolTipText("Lädt die Berechtigungsgruppen neu vom Controller (SearchRegionPermissionGroup).");
    refreshBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onRefresh(); }
    });
    tsLabel = new Label(toolbar, SWT.NONE);
    tsLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    table = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setHeaderVisible(true); table.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true);
    tgd.heightHint = 360; tgd.widthHint = 820;
    table.setLayoutData(tgd);

    TableColumn c1 = new TableColumn(table, SWT.RIGHT); c1.setText("ID");           c1.setWidth(60);
    TableColumn c2 = new TableColumn(table, SWT.LEFT);  c2.setText("Name");         c2.setWidth(200);
    TableColumn c3 = new TableColumn(table, SWT.LEFT);  c3.setText("Türen");        c3.setWidth(280);
    TableColumn c4 = new TableColumn(table, SWT.RIGHT); c4.setText("Benutzer");     c4.setWidth(90);
    TableColumn c5 = new TableColumn(table, SWT.RIGHT); c5.setText("Org.-Gruppen"); c5.setWidth(100);
    TableSorter.install(table);

    populate(HikvisionGroupCatalog.fromCache());
  }

  private void populate(HikvisionGroupCatalog cat)
  {
    if (table == null || table.isDisposed()) return;
    table.removeAll();
    for (HikvisionGroupCatalog.RegionPermissionGroup g : cat.regions)
    {
      TableItem ti = new TableItem(table, SWT.NONE);
      ti.setText(0, String.valueOf(g.id));
      ti.setText(1, g.displayName());
      ti.setText(2, String.join(", ", g.doors));
      ti.setText(3, String.valueOf(g.memberCount));
      ti.setText(4, String.valueOf(g.userGroupCount));
    }
    if (tsLabel != null && !tsLabel.isDisposed())
      tsLabel.setText(cat.timestamp == 0 ? "(kein Cache — bitte 'Aktualisieren' klicken)"
          : "Stand: " + formatStamp(cat.timestamp));
    TableSorter.reapplyIfSorted(table);
  }

  private void onRefresh()
  {
    if (refreshBtn != null && !refreshBtn.isDisposed()) refreshBtn.setEnabled(false);
    if (tsLabel != null && !tsLabel.isDisposed()) tsLabel.setText("lädt Berechtigungsgruppen …");
    Thread t = new Thread(() -> {
      try
      {
        HikvisionGroupCatalog cat = HikvisionGroupCatalog.refreshFromHikvision(
            HikvisionGruppenView.newClient(), HikvisionGruppenView.logOnly(), false, true);
        Display.getDefault().asyncExec(() -> {
          populate(cat);
          if (refreshBtn != null && !refreshBtn.isDisposed()) refreshBtn.setEnabled(true);
        });
      }
      catch (Exception ex)
      {
        Logger.error("Berechtigungsgruppen-Refresh fehlgeschlagen", ex);
        Display.getDefault().asyncExec(() -> {
          if (tsLabel != null && !tsLabel.isDisposed()) tsLabel.setText("Fehler beim Laden");
          if (refreshBtn != null && !refreshBtn.isDisposed()) refreshBtn.setEnabled(true);
          MessageBox b = new MessageBox(GUI.getShell(), SWT.ICON_ERROR | SWT.OK);
          b.setText("Laden fehlgeschlagen");
          b.setMessage(ex.getClass().getSimpleName() + ": " + ex.getMessage());
          b.open();
        });
      }
    }, "jverein.hikvision-rechte-refresh");
    t.setDaemon(true); t.start();
  }

  private static String formatStamp(long ts)
  {
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    return sdf.format(new java.util.Date(ts));
  }
}
