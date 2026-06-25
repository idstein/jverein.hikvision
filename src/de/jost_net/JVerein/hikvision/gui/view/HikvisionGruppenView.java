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

import de.jost_net.JVerein.hikvision.HikvisionClient;
import de.jost_net.JVerein.hikvision.HikvisionGroupCatalog;
import de.jost_net.JVerein.hikvision.HikvisionSettings;
import de.jost_net.JVerein.hikvision.ProgressListener;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.logging.Logger;

/**
 * OpenJVerein &gt; Zugangssystem &gt; Organisationsgruppen
 *
 * View of the controller's organisational user groups (Mitglieder /
 * Vorstand / Robby Bubble / BSV …), source {@code UserGroupMgr/SearchUserGroup}.
 * Renders from the local catalog cache; the "Aktualisieren" button reloads
 * ONLY the org-group list from the controller (the Berechtigungsgruppen are
 * left untouched — those have their own view). The Benutzer "Aktualisieren"
 * no longer touches this list.
 */
public class HikvisionGruppenView extends AbstractView
{
  private Table table;
  private Label tsLabel;
  private Button refreshBtn;

  @Override
  public void bind() throws Exception
  {
    GUI.getView().setTitle("Zugangssystem Organisationsgruppen");
    Composite c = getParent();
    c.setLayout(new GridLayout(1, false));

    Label info = new Label(c, SWT.WRAP);
    info.setText("Zugangssystem-Benutzergruppen mit Anzahl Mitglieder. "
        + "'Aktualisieren' lädt nur die Organisationsgruppen neu vom Controller.");
    GridData igd = new GridData(SWT.FILL, SWT.CENTER, true, false);
    igd.widthHint = 800;
    info.setLayoutData(igd);

    Composite toolbar = new Composite(c, SWT.NONE);
    toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    toolbar.setLayout(new GridLayout(2, false));
    refreshBtn = new Button(toolbar, SWT.PUSH);
    refreshBtn.setText("Aktualisieren");
    refreshBtn.setToolTipText("Lädt die Organisationsgruppen neu vom Controller (SearchUserGroup).");
    refreshBtn.addSelectionListener(new SelectionAdapter() {
      @Override public void widgetSelected(SelectionEvent e) { onRefresh(); }
    });
    tsLabel = new Label(toolbar, SWT.NONE);
    tsLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    table = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setHeaderVisible(true); table.setLinesVisible(true);
    GridData tgd = new GridData(SWT.FILL, SWT.FILL, true, true);
    tgd.heightHint = 360; tgd.widthHint = 760;
    table.setLayoutData(tgd);

    TableColumn c1 = new TableColumn(table, SWT.LEFT);  c1.setText("Gruppe");   c1.setWidth(220);
    TableColumn c2 = new TableColumn(table, SWT.RIGHT); c2.setText("Benutzer"); c2.setWidth(90);
    TableColumn c3 = new TableColumn(table, SWT.LEFT);  c3.setText("UUID");     c3.setWidth(380);
    TableSorter.install(table);

    populate(HikvisionGroupCatalog.fromCache());
  }

  private void populate(HikvisionGroupCatalog cat)
  {
    if (table == null || table.isDisposed()) return;
    table.removeAll();
    for (HikvisionGroupCatalog.Group g : cat.groups)
    {
      TableItem ti = new TableItem(table, SWT.NONE);
      ti.setText(0, g.name == null ? "" : g.name);
      ti.setText(1, String.valueOf(g.memberCount));
      ti.setText(2, g.uuid == null ? "" : g.uuid);
    }
    if (tsLabel != null && !tsLabel.isDisposed())
      tsLabel.setText(cat.timestamp == 0 ? "(kein Cache — bitte 'Aktualisieren' klicken)"
          : "Stand: " + formatStamp(cat.timestamp));
    TableSorter.reapplyIfSorted(table);
  }

  private void onRefresh()
  {
    if (refreshBtn != null && !refreshBtn.isDisposed()) refreshBtn.setEnabled(false);
    if (tsLabel != null && !tsLabel.isDisposed()) tsLabel.setText("lädt Organisationsgruppen …");
    Thread t = new Thread(() -> {
      try
      {
        HikvisionGroupCatalog cat = HikvisionGroupCatalog.refreshFromHikvision(
            newClient(), logOnly(), true, false);
        Display.getDefault().asyncExec(() -> {
          populate(cat);
          if (refreshBtn != null && !refreshBtn.isDisposed()) refreshBtn.setEnabled(true);
        });
      }
      catch (Exception ex)
      {
        Logger.error("Organisationsgruppen-Refresh fehlgeschlagen", ex);
        Display.getDefault().asyncExec(() -> {
          if (tsLabel != null && !tsLabel.isDisposed()) tsLabel.setText("Fehler beim Laden");
          if (refreshBtn != null && !refreshBtn.isDisposed()) refreshBtn.setEnabled(true);
          MessageBox b = new MessageBox(GUI.getShell(), SWT.ICON_ERROR | SWT.OK);
          b.setText("Laden fehlgeschlagen");
          b.setMessage(ex.getClass().getSimpleName() + ": " + ex.getMessage());
          b.open();
        });
      }
    }, "jverein.hikvision-gruppen-refresh");
    t.setDaemon(true); t.start();
  }

  /** Controller client wired to the configured retry/deadline knobs; the
   *  call deadline bounds a wedged request even without a cancel button. */
  static HikvisionClient newClient()
  {
    HikvisionClient client = new HikvisionClient(
        HikvisionSettings.getControllerUrl(), HikvisionSettings.getControllerUser(),
        HikvisionSettings.getControllerPassword(), HikvisionSettings.getInterCallPauseMs(),
        HikvisionSettings.getVerifySsl());
    client.setResilience(HikvisionSettings.getMaxAttempts(), HikvisionSettings.getCallDeadlineMs());
    return client;
  }

  /** Progress listener that only forwards to the log (no UI progress bar here). */
  static ProgressListener logOnly()
  {
    return new ProgressListener() {
      @Override public void log(String msg) { Logger.info(msg); }
      @Override public void progress(int done, int total) {}
      @Override public void progress(int done, int total, String phase) {}
    };
  }

  private static String formatStamp(long ts)
  {
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    return sdf.format(new java.util.Date(ts));
  }
}
