package com.gpmanager.ui.bento;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

/**
 * Base for fully painted components: supplies an accessible context (a raw JComponent has
 * none) so every control can carry an accessible name for screen readers and tests.
 */
public abstract class Painted extends JComponent
{
    private final AccessibleRole role;

    protected Painted(AccessibleRole role)
    {
        this.role = role == null ? AccessibleRole.PANEL : role;
        setOpaque(false);
    }

    @Override
    public AccessibleContext getAccessibleContext()
    {
        if (accessibleContext == null)
        {
            accessibleContext = new AccessibleJComponent()
            {
                @Override
                public AccessibleRole getAccessibleRole()
                {
                    return role;
                }
            };
        }
        return accessibleContext;
    }
}
