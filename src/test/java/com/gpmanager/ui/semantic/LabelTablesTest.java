package com.gpmanager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Every verb, reason and correction/review label, pinned against a golden written from the switch tables. */
public class LabelTablesTest
{
    private static final Path GOLDEN = Paths.get("src/test/resources/com/gpmanager/label-tables.txt");

    static String snapshot()
    {
        StringBuilder out = new StringBuilder();
        for (Au kind : Au.values())
        {
            out.append("action ").append(kind).append(" = ").append(Br.jy(kind)).append('\n');
        }
        out.append("correction null = ").append(Br.qj(null)).append('\n');
        for (Ah correction : Ah.values())
        {
            out.append("correction ").append(correction).append(" = ")
                .append(Br.qj(correction)).append(" | ")
                .append(LedgerPage.qj(correction)).append('\n');
        }
        for (Cl decision : Cl.values())
        {
            out.append("review ").append(decision).append(" = ").append(LedgerPage.ahh(decision)).append('\n');
        }
        for (Ai type : Ai.values())
        {
            for (boolean counted : new boolean[]{true, false})
            {
                for (long value : new long[]{250L, -250L})
                {
                    Ac tx = Tx.of(1_000L, type, Aj.GENERIC, "", counted,
                        Collections.singletonList(new Ab(526, "Bones", value > 0 ? 1 : -1, 250, value)));
                    out.append("type ").append(type).append(' ').append(counted).append(' ').append(value).append(" = ")
                        .append(Br.verbOf(tx)).append(" | ")
                        .append(Br.why(tx, false, false)).append('\n');
                }
            }
            for (Ah correction : Ah.values())
            {
                Ac tx = Tx.of(1_000L, type, Aj.GENERIC, "", true,
                    Collections.singletonList(new Ab(526, "Bones", 1, 250, 250L)));
                tx.correction = correction;
                out.append("why ").append(type).append(' ').append(correction).append(" = ")
                    .append(Br.why(tx, false, false)).append(" | ")
                    .append(Br.why(tx, true, false)).append(" | ")
                    .append(Br.why(tx, false, true)).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    public void labelsMatchTheGolden() throws java.io.IOException
    {
        String now = snapshot();
        if (Boolean.getBoolean("gp.golden.write"))
        {
            Files.createDirectories(GOLDEN.getParent());
            Files.write(GOLDEN, now.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(new String(Files.readAllBytes(GOLDEN), StandardCharsets.UTF_8).replace("\r\n", "\n"), now);
    }
}
