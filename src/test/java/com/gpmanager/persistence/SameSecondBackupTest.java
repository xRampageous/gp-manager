package com.gpmanager;

import java.nio.file.Files;
import net.runelite.client.util.Filepath;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 1.3.1: two backups in one second list the second one as the newer, so "keep newest" keeps it. */
public class SameSecondBackupTest
{
    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void aSameSecondCopyIsTheNewestKept() throws Exception
    {
        SessionRepository repository = new SessionRepository(JsonCodec.gson(),
            FilepathTestSupport.root(Files.createTempDirectory("gp-same-second")));
        Ei persistence = new Ei(null, null, repository, new OrderedPersistenceWriter(repository, null),
            PresentationLifecycleTest.engine());
        long now = 1_791_500_000_000L;
        Filepath first = persistence.akt(now);
        Filepath second = persistence.akt(now);
        assertTrue(String.valueOf(second.getFileName()).endsWith("_2.json"));
        assertEquals("the later copy lists first", second.getFileName(), persistence.backups().get(0).getFileName());
        persistence.adz(1);
        assertEquals(1, persistence.backups().size());
        assertEquals("keeping one keeps the newer", second.getFileName(), persistence.backups().get(0).getFileName());
        assertTrue(!first.exists());
    }
}
