package de.jost_net.JVerein.hikvision.gui.action;

import de.jost_net.JVerein.hikvision.gui.view.HikvisionChipsView;
import de.willuhn.jameica.gui.Action;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.util.ApplicationException;

public class HikvisionChipsAction implements Action
{
  @Override
  public void handleAction(Object context) throws ApplicationException
  {
    GUI.startView(HikvisionChipsView.class, context);
  }
}
