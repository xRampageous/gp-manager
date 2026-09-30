package com.gpmanager;

import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ReleaseContractTest
{
    /**
     * The release uses save schema 108. Unsupported schemas are not imported.
     */
    @Test
    public void compactDataSchemaIsPinned()
    {
        assertEquals(108, SavedState.CURRENT_SCHEMA_VERSION);
    }

    @Test
    public void pluginIconResourceExists() throws IOException
    {
        try (InputStream in = ReleaseContractTest.class.getResourceAsStream("/profit_manager_icon.png"))
        {
            assertNotNull("plugin loads /profit_manager_icon.png via getResourceAsStream", in);
        }
    }
}
