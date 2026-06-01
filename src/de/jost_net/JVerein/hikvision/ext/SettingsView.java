package de.jost_net.JVerein.hikvision.ext;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;

import de.jost_net.JVerein.hikvision.HikvisionSettings;
import de.jost_net.JVerein.hikvision.SyncEngine;
import de.willuhn.jameica.gui.extension.Extendable;
import de.willuhn.jameica.gui.extension.Extension;
import de.willuhn.jameica.gui.input.CheckboxInput;
import de.willuhn.jameica.gui.input.IntegerInput;
import de.willuhn.jameica.gui.input.PasswordInput;
import de.willuhn.jameica.gui.input.TextInput;
import de.willuhn.jameica.gui.internal.views.Settings;
import de.willuhn.jameica.gui.util.TabGroup;
import de.willuhn.jameica.messaging.Message;
import de.willuhn.jameica.messaging.MessageConsumer;
import de.willuhn.jameica.messaging.SettingsChangedMessage;
import de.willuhn.jameica.messaging.StatusBarMessage;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;

/**
 * Hikvision-Reiter in Datei → Einstellungen. Zeigt die Konfiguration,
 * speichert beim "Speichern"-Button und bietet einen "Synchronisieren"-
 * Button für den manuellen Sync.
 */
public class SettingsView implements Extension
{
  private TextInput url;
  private TextInput user;
  private PasswordInput password;
  private TextInput csvPath;
  private TextInput memberGroupId;
  private TextInput memberGroupName;
  private TextInput sponsorGroupId;
  private TextInput sponsorGroupName;
  private IntegerInput regionPermissionGroup;
  private TextInput zusatzfeldName;
  private IntegerInput interCallPauseMs;
  private CheckboxInput dryRun;

  private Text logArea;
  private Button syncButton;

  private MessageConsumer consumer;

  @Override
  public void extend(Extendable extendable)
  {
    if (!(extendable instanceof Settings)) return;
    Settings settings = (Settings) extendable;

    consumer = new MessageConsumer()
    {
      @Override public void handleMessage(Message m) throws Exception { store(); }
      @Override public Class<?>[] getExpectedMessageTypes()
      { return new Class[] { SettingsChangedMessage.class }; }
      @Override public boolean autoRegister() { return false; }
    };
    Application.getMessagingFactory().registerMessageConsumer(consumer);

    try
    {
      TabGroup tab = new TabGroup(settings.getTabFolder(), "Hikvision");
      tab.getComposite().addDisposeListener(e -> {
        Application.getMessagingFactory().unRegisterMessageConsumer(consumer);
      });

      url = new TextInput(HikvisionSettings.getControllerUrl(), 200);
      user = new TextInput(HikvisionSettings.getControllerUser(), 64);
      password = new PasswordInput(HikvisionSettings.getControllerPassword());
      csvPath = new TextInput(HikvisionSettings.getCsvPath(), 250);
      memberGroupId = new TextInput(HikvisionSettings.getMemberGroupId(), 64);
      memberGroupName = new TextInput(HikvisionSettings.getMemberGroupName(), 64);
      sponsorGroupId = new TextInput(HikvisionSettings.getSponsorGroupId(), 64);
      sponsorGroupName = new TextInput(HikvisionSettings.getSponsorGroupName(), 64);
      regionPermissionGroup = new IntegerInput(HikvisionSettings.getRegionPermissionGroup());
      zusatzfeldName = new TextInput(HikvisionSettings.getZusatzfeldName(), 64);
      interCallPauseMs = new IntegerInput(HikvisionSettings.getInterCallPauseMs());
      dryRun = new CheckboxInput(HikvisionSettings.getDryRun());

      tab.addLabelPair("Controller-URL", url);
      tab.addLabelPair("Benutzer", user);
      tab.addLabelPair("Passwort", password);
      tab.addLabelPair("CSV-Pfad (Chip,Kartennummer)", csvPath);
      tab.addLabelPair("Mitglieder Gruppen-ID (UUID)", memberGroupId);
      tab.addLabelPair("Mitglieder Gruppen-Name", memberGroupName);
      tab.addLabelPair("Sponsor Gruppen-ID (UUID)", sponsorGroupId);
      tab.addLabelPair("Sponsor Gruppen-Name", sponsorGroupName);
      tab.addLabelPair("Region-Permission-Gruppe (Türrechte)", regionPermissionGroup);
      tab.addLabelPair("Zusatzfeld-Name (transponder)", zusatzfeldName);
      tab.addLabelPair("Pause zwischen Calls (ms)", interCallPauseMs);
      tab.addCheckbox(dryRun, "Trockenlauf — nur loggen, keine Schreibvorgänge");

      // sync button + log area
      Composite c = tab.getComposite();
      syncButton = new Button(c, SWT.PUSH);
      syncButton.setText("Jetzt synchronisieren");
      syncButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
      syncButton.addSelectionListener(new SelectionAdapter()
      {
        @Override public void widgetSelected(SelectionEvent e) { onSyncClick(); }
      });

      logArea = new Text(c, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
      GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
      gd.heightHint = 300;
      logArea.setLayoutData(gd);
    }
    catch (Exception e)
    {
      Logger.error("unable to extend settings", e);
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(
          "Fehler beim Anzeigen der Hikvision-Einstellungen", StatusBarMessage.TYPE_ERROR));
    }
  }

  private void store() throws ApplicationException
  {
    try
    {
      HikvisionSettings.setControllerUrl((String) url.getValue());
      HikvisionSettings.setControllerUser((String) user.getValue());
      HikvisionSettings.setControllerPassword((String) password.getValue());
      HikvisionSettings.setCsvPath((String) csvPath.getValue());
      HikvisionSettings.setMemberGroupId((String) memberGroupId.getValue());
      HikvisionSettings.setMemberGroupName((String) memberGroupName.getValue());
      HikvisionSettings.setSponsorGroupId((String) sponsorGroupId.getValue());
      HikvisionSettings.setSponsorGroupName((String) sponsorGroupName.getValue());
      Object rpg = regionPermissionGroup.getValue();
      if (rpg instanceof Integer) HikvisionSettings.setRegionPermissionGroup((Integer) rpg);
      HikvisionSettings.setZusatzfeldName((String) zusatzfeldName.getValue());
      Object pause = interCallPauseMs.getValue();
      if (pause instanceof Integer) HikvisionSettings.setInterCallPauseMs((Integer) pause);
      HikvisionSettings.setDryRun(Boolean.TRUE.equals(dryRun.getValue()));
    }
    catch (Exception e) { throw new ApplicationException(e.getMessage(), e); }
  }

  private void onSyncClick()
  {
    syncButton.setEnabled(false);
    logArea.setText("");
    boolean dry = Boolean.TRUE.equals(dryRun.getValue());
    appendLog("Sync gestartet (" + (dry ? "Trockenlauf" : "APPLY") + ") …\n");

    Thread t = new Thread(() -> {
      try
      {
        // persist current values before sync, in case user edited but didn't click Speichern
        store();
        SyncEngine.Result r = SyncEngine.run(dry, new SyncEngine.ProgressListener()
        {
          @Override public void log(String msg) { appendLog(msg + "\n"); }
          @Override public void progress(int done, int total) { /* could update a progress bar */ }
        });
        appendLog("\nFertig. created=" + r.created + " deleted=" + r.deleted
            + " cardsAdded=" + r.cardsAdded + " cardsRemoved=" + r.cardsRemoved
            + " skipped=" + r.skippedMembers + " unknownCards=" + r.unknownCards
            + " errors=" + r.errors.size() + "\n");
        if (!r.errors.isEmpty())
        {
          appendLog("\nFehler:\n");
          for (String err : r.errors) appendLog("  " + err + "\n");
        }
      }
      catch (Exception e)
      {
        Logger.error("Sync failed", e);
        appendLog("\nFEHLER: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
      }
      finally
      {
        Display.getDefault().asyncExec(() -> syncButton.setEnabled(true));
      }
    }, "jverein.hikvision-sync");
    t.setDaemon(true);
    t.start();
  }

  private void appendLog(String s)
  {
    Display.getDefault().asyncExec(() -> {
      if (logArea != null && !logArea.isDisposed()) logArea.append(s);
    });
  }
}
