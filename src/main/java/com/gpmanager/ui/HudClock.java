package com.gpmanager.ui;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.inject.Singleton;

/**
 * Controllable clock for HUD/reward presentation. Production uses wall time;
 * previews and tests pin a fixed epoch so reveal dwell can be asserted.
 */
@Singleton
public final class HudClock
{
    private final AtomicReference<LongSupplier> supplier =
        new AtomicReference<>(System::currentTimeMillis);

    public long now()
    {
        return supplier.get().getAsLong();
    }

    public void useWallClock()
    {
        supplier.set(System::currentTimeMillis);
    }

    public void fixAt(long epochMillis)
    {
        supplier.set(() -> epochMillis);
    }

    public void setSupplier(LongSupplier next)
    {
        supplier.set(next == null ? System::currentTimeMillis : next);
    }
}
