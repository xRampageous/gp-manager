package com.gpmanager;

import java.nio.file.Path;
import net.runelite.client.util.Filepath;

public final class FilepathTestSupport
{
    private FilepathTestSupport()
    {
    }

    public static Filepath root(Path path)
    {
        return Filepath.Unchecked.getRooted(path);
    }

    public static Path path(Filepath filepath)
    {
        return Filepath.Unchecked.getPath(filepath);
    }
}
