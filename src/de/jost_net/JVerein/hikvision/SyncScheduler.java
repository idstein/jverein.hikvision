package de.jost_net.JVerein.hikvision;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.willuhn.jameica.messaging.Message;
import de.willuhn.jameica.messaging.MessageConsumer;
import de.willuhn.jameica.messaging.SystemMessage;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;

/**
 * Periodic delta-sync scheduler. Armed at {@code SYSTEM_STARTED} (so jverein's
 * DB is up — {@code Plugin.init()} runs before that), it invokes
 * {@link SyncOrchestrator#tick} every {@code getSyncIntervalMinutes} on a
 * <b>daemon</b> {@link ScheduledExecutorService} using
 * {@code scheduleWithFixedDelay} (delay measured from the END of the previous
 * run, so a slow/wedged tick can never stack catch-up runs). Stops on plugin
 * {@code shutDown()} / {@code SYSTEM_SHUTDOWN}.
 *
 * <p>Wedge-safety: a hung controller call self-bounds via the client's call
 * deadline; {@link SyncOrchestrator#SYNC_IN_PROGRESS} keeps a tick from
 * colliding with a user-triggered task; the tick runs headless (no SWT) so a
 * closed view is irrelevant.
 */
public class SyncScheduler implements MessageConsumer
{
  private ScheduledExecutorService exec;
  private ScheduledFuture<?> future;
  private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
  private boolean armed = false;

  /** Register for SYSTEM_STARTED / SYSTEM_SHUTDOWN; actual arming waits for
   *  SYSTEM_STARTED. Cheap + safe even when the schedule is disabled. */
  public synchronized void start()
  {
    try
    {
      Application.getMessagingFactory().registerMessageConsumer(this);
      Logger.info("Hikvision SyncScheduler registriert (wartet auf SYSTEM_STARTED).");
    }
    catch (Exception e) { Logger.error("Hikvision SyncScheduler: registrieren fehlgeschlagen", e); }
  }

  private synchronized void arm()
  {
    if (armed) return;
    if (!HikvisionSettings.getSyncScheduleEnabled())
    { Logger.info("Hikvision SyncScheduler: per Einstellung deaktiviert — kein Zeitplan."); return; }
    armed = true;
    cancelFlag.set(false);
    ThreadFactory tf = r -> { Thread t = new Thread(r, "hikvision-sync-scheduler"); t.setDaemon(true); return t; };
    exec = Executors.newSingleThreadScheduledExecutor(tf);
    long minutes = HikvisionSettings.getSyncIntervalMinutes();
    long periodSec = minutes * 60L;
    // First run after one full interval: DB is warm and we avoid a boot-storm.
    future = exec.scheduleWithFixedDelay(this::runOnce, periodSec, periodSec, TimeUnit.SECONDS);
    Logger.info("Hikvision SyncScheduler aktiv: alle " + minutes + " min (Voll alle "
        + HikvisionSettings.getForcedFullIntervalMinutes() + " min, autoApply="
        + HikvisionSettings.getAutoApply() + ", autoApplyDeletes=" + HikvisionSettings.getAutoApplyDeletes() + ").");
  }

  private void runOnce()
  {
    // Never let a tick exception kill the schedule (a Timer would die; the
    // executor isolates it, but we still guard + log defensively).
    try { SyncOrchestrator.tick(cancelFlag::get); }
    catch (Throwable t) { Logger.error("Hikvision SyncScheduler: Tick-Ausnahme", t); }
  }

  /** Stop the schedule and abort any in-flight tick within the call deadline.
   *  Idempotent + null-safe (no ordering guarantee vs SYSTEM_SHUTDOWN). */
  public synchronized void stop()
  {
    cancelFlag.set(true);
    try { Application.getMessagingFactory().unRegisterMessageConsumer(this); } catch (Exception ignored) {}
    if (future != null) { future.cancel(false); future = null; }
    if (exec != null)
    {
      exec.shutdownNow();
      try { exec.awaitTermination(5, TimeUnit.SECONDS); }
      catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
      exec = null;
    }
    armed = false;
  }

  // ---- MessageConsumer: arm on SYSTEM_STARTED, stop on SYSTEM_SHUTDOWN ----
  @Override public Class[] getExpectedMessageTypes() { return new Class[] { SystemMessage.class }; }
  @Override public boolean autoRegister() { return false; }
  @Override public void handleMessage(Message message)
  {
    if (!(message instanceof SystemMessage)) return;
    int code = ((SystemMessage) message).getStatusCode();
    if (code == SystemMessage.SYSTEM_STARTED) arm();
    else if (code == SystemMessage.SYSTEM_SHUTDOWN) stop();
  }
}
