package com.gpmanager;

import static com.gpmanager.GameData.msg;

import net.runelite.client.ui.*;

import org.slf4j.*;

import java.awt.image.BufferedImage;
import java.io.*;

import javax.imageio.ImageIO;

/**
 * Owns the one GP Manager NavigationButton; install is idempotent, so disable/enable cannot
 * duplicate it, and uninstall always removes exactly what install added.
 *
 * <p>Sidebar navigation and overlay registration have separate lifecycles. The icon loader never
 * returns null, so a missing image cannot silently remove the navigation button.
 */
class NavButton {
    static final Logger log = LoggerFactory.getLogger(NavButton.class);

    interface NavigationHost {
        void add(NavigationButton button);

        void remove(NavigationButton button);
    }

    NavigationButton button;

    /** The runtime sidebar icon; never null so the required button cannot vanish silently. */
    static BufferedImage loadIcon() {
        try (InputStream stream = NavButton.class.getResourceAsStream(msg("profit-manager"))) {
            BufferedImage image = stream == null ? null : ImageIO.read(stream);
            if (image != null) return image;
            log.warn(msg("gp-manager-icon"));
        } catch (IOException ex) {
            log.warn(msg("gp-manager-icon-resource"), ex);
        }
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    static NavigationHost toolbarHost(ClientToolbar toolbar) {
        if (toolbar == null) return null;
        return new NavigationHost() {
            public void add(NavigationButton navigationButton) {
                toolbar.addNavigation(navigationButton);
            }

            public void remove(NavigationButton navigationButton) {
                toolbar.removeNavigation(navigationButton);
            }
        };
    }

    /**
     * @return true when this call installed the button (host, panel and icon present).
     */
    synchronized boolean install(NavigationHost navigation, PluginPanel panel, BufferedImage icon) {
        if (button != null || navigation == null || panel == null || icon == null) return false;
        NavigationButton next =
                NavigationButton.builder()
                        .tooltip("GP Manager")
                        .icon(icon)
                        .priority(5)
                        .panel(panel)
                        .build();
        navigation.add(next);
        button = next;
        return true;
    }

    synchronized void uninstall(NavigationHost navigation) {
        if (button != null && navigation != null) navigation.remove(button);
        button = null;
    }

    synchronized NavigationButton button() {
        return button;
    }
}
