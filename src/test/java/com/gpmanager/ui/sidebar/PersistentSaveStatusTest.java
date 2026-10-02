package com.gpmanager;

import java.util.Collections;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Owner 2026-10-01 (F06): a save failure stays visible until the backend actually recovers. */
public class PersistentSaveStatusTest
{
    @Test
    public void aStickyFailureIgnoresTheTimerAndTextClicks() throws Exception
    {
        Fixture fixture = new Fixture();
        onEdt(() ->
        {
            fixture.shell.notifySticky("Data not saved", "disk full", "Retry", () -> { });
            assertTrue(fixture.shell.notice.isVisible());
            assertFalse("a failure has no auto-dismiss timer", fixture.shell.noticeTimer.isRunning());
            fixture.shell.noticeClicked();
            assertTrue("a text click does not dismiss a failure", fixture.shell.notice.isVisible());
            fixture.shell.tell("Saved as My Grind", "future defaults only", false);
            assertTrue("routine news never replaces a failure",
                ShellProbe.noticeText(fixture.shell).startsWith("Data not saved"));
            fixture.shell.clearSticky();
            assertFalse("recovery takes the failure down", fixture.shell.notice.isVisible());
            fixture.shell.tell("Saved as My Grind", "future defaults only", false);
            assertTrue("routine news flows again",
                ShellProbe.noticeText(fixture.shell).startsWith("Saved as My Grind"));
            return null;
        });
    }

    @Test
    public void retryRunsAndAPersistingFailureComesBack() throws Exception
    {
        Fixture fixture = new Fixture();
        boolean[] retried = {false};
        onEdt(() ->
        {
            fixture.shell.notifySticky("Data not saved", "disk full", "Retry", () -> retried[0] = true);
            ((JButton) fixture.shell.noticeAction.getComponent(0)).doClick();
            assertTrue("the retry action runs", retried[0]);
            assertFalse("the attempt hides the failure", fixture.shell.notice.isVisible());
            fixture.shell.notifySticky("Data not saved", "disk full", "Retry", () -> { });
            assertTrue("still failing means still visible", fixture.shell.notice.isVisible());
            assertFalse("and still no timer", fixture.shell.noticeTimer.isRunning());
            return null;
        });
    }

    @Test
    public void theSidebarFollowsTheBackendStatusUntilRecovery() throws Exception
    {
        FakePersist persist = new FakePersist();
        Dp panel = onEdt(() -> new Dp(engine(), new GpManagerConfig() {}, null, null, persist));
        Shell shell = panel.shell();
        onEdt(() ->
        {
            persist.status = new Ci(Ci.State.FAILED, "disk full", false, "");
            panel.ajf();
            assertTrue(shell.notice.isVisible());
            assertTrue(ShellProbe.noticeText(shell).startsWith("Data not saved"));
            panel.ajf();
            assertTrue("a repeated failing status stays up", shell.notice.isVisible());

            persist.status = new Ci(Ci.State.OK, "", false, "backup-2026");
            panel.ajf();
            assertTrue("recovery replaces it with the recovery note",
                ShellProbe.noticeText(shell).startsWith("Recovered from backup"));
            assertTrue("routine recovery has a timer", shell.noticeTimer.isRunning());

            persist.status = new Ci(Ci.State.FAILED, "disk full", false, "");
            panel.ajf();
            assertTrue(shell.notice.isVisible());
            panel.ot();
            assertFalse("a profile switch drops the old profile's failure", shell.notice.isVisible());
            return null;
        });
    }

    private static Am engine()
    {
        return new Am(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
    }

    private static final class Fixture
    {
        final Dp panel;
        final Shell shell;

        Fixture() throws Exception
        {
            panel = onEdt(() -> new Dp(engine(), new GpManagerConfig() {}, null));
            shell = panel.shell();
        }
    }

    /** Returns the status the test chooses; the real writer is never involved. */
    private static final class FakePersist extends Ei
    {
        Ci status = Ci.abd();

        FakePersist()
        {
            super(null, null, null, null, null);
        }

        @Override
        Ci ux()
        {
            return status;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                result[0] = callable.call();
            }
            catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
        });
        return (T) result[0];
    }
}
