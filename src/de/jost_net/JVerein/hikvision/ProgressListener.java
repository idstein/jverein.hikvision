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

  /**
   * Returns {@code true} if the surrounding background task has been
   * cancelled. Long-running loops (HikvisionClient pagination,
   * SyncEngine.run apply phase) should check this between iterations
   * and throw {@link java.io.InterruptedIOException} when set, so the
   * cancel button in Jameica's status bar takes effect promptly.
   * Default is {@code false} so non-cancellable callers don't need to
   * implement it.
   */
  default boolean isCancelled() { return false; }
}
