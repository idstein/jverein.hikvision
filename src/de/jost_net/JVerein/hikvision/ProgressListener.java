package de.jost_net.JVerein.hikvision;

/**
 * Cross-package progress sink. Promoted to a top-level type so
 * {@link HikvisionClient} (low-level paging) can call back to UI code
 * (the ProgressBar widgets) without depending on {@link SyncEngine}.
 *
 * The {@code phase} overload lets the controller paging code report
 * "Benutzer abrufen 120/560" without callers having to wrap every
 * inner-loop event themselves.
 */
public interface ProgressListener
{
  void log(String msg);
  void progress(int done, int total);

  default void progress(int done, int total, String phase)
  {
    progress(done, total);
  }
}
