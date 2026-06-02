package de.jost_net.JVerein.hikvision.ext;

import de.willuhn.jameica.system.BackgroundTask;

/**
 * Tiny base for all Hikvision plugin background work. Implements the
 * interrupt-flag plumbing so subclasses only need to override
 * {@link BackgroundTask#run}. Submit via
 * {@code Application.getController().start(...)} — Jameica's status bar
 * then shows progress, a cancel button, and the status text.
 *
 * Subclasses should call {@link #isInterrupted()} between expensive
 * steps where reasonable so the cancel button has an effect — though
 * the controller paging in {@link de.jost_net.JVerein.hikvision.HikvisionClient}
 * isn't currently interrupt-aware, so cancellation only takes effect at
 * the natural break between batches at the moment.
 */
public abstract class HikvisionBackgroundTask implements BackgroundTask
{
  private volatile boolean interrupted = false;

  @Override public final void interrupt()         { this.interrupted = true; }
  @Override public final boolean isInterrupted()  { return this.interrupted; }
}
