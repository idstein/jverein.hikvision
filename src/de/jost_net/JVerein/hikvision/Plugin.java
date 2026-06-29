package de.jost_net.JVerein.hikvision;

import de.willuhn.jameica.plugin.AbstractPlugin;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;

public class Plugin extends AbstractPlugin
{
  // Jameica 2.12 instantiates plugins with the default constructor.

  /** Unattended periodic delta sync. Registered in init(); arms itself at
   *  SYSTEM_STARTED (when jverein's DB is up) only if enabled in settings. */
  private final SyncScheduler scheduler = new SyncScheduler();

  @Override
  public void init() throws ApplicationException
  {
    super.init();
    try { scheduler.start(); }
    catch (Exception e) { Logger.error("Hikvision SyncScheduler konnte nicht gestartet werden", e); }
  }

  @Override
  public void shutDown()
  {
    try { scheduler.stop(); }
    catch (Exception e) { Logger.error("Hikvision SyncScheduler konnte nicht gestoppt werden", e); }
    super.shutDown();
  }
}
