package com.gpmanager;

import com.google.gson.Gson;
import java.util.Locale;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SaveMessagesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void realConflictAndNonAdvancingSaveErrorsUsePlayerLanguage() throws Exception {
        var gson = new Gson();
        JsonCodec.bind(gson);
        var repository = new SessionRepository(gson, FilepathTestSupport.root(temporary.newFolder().toPath()), false);
        var initial = new SavedState();
        initial.setRevision(1);
        assertTrue(repository.save(new WriteIntent(null, repository.generation, 0, initial, null)));
        var stale = new SavedState();
        stale.setRevision(1);
        var nonAdvancing = repository.commit(new WriteIntent(null, repository.generation, 1, stale, null), false);
        assertFalse(nonAdvancing.isCommitted());
        plain(nonAdvancing.message);
        var conflict = repository.commit(new WriteIntent(null, repository.generation, 0, stale, null), false);
        assertEquals(SessionRepository.ReplaceOutcome.Kind.CONFLICT, conflict.kind);
        plain(conflict.message);
        assertEquals(1, repository.load().revision);
    }

    @Test public void stickyResourcesArePlain() {
        for (String key : new String[] {"tracking-paused-back", "disk-revision", "advance-disk",
                "another-client-holds", "save-failed", "pending-save", "persistence-executor",
                "disk-state", "reset-did", "save-did", "primary-reset", "primary-already"}) plain(GameData.msg(key));
    }

    private static void plain(String message) {
        assertFalse(message, message.isEmpty());
        String lower = message.toLowerCase(Locale.ROOT);
        for (String internal : new String[] {"revision", "base", "write lock", "scope"})
            assertFalse(message, lower.contains(internal));
    }
}
