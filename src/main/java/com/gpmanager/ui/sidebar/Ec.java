package com.gpmanager;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import net.runelite.client.ui.*;
import org.slf4j.*;
import static com.gpmanager.Ak.msg;
/**
* Owns the one GP Manager NavigationButton; install is idempotent, so disable/enable cannot
* duplicate it, and uninstall always removes exactly what install added.
*
* <p>Sidebar navigation and overlay registration have separate lifecycles. The icon loader
* never returns null, so a missing image cannot silently remove the navigation button.</p>
*/
class Ec {
static final Logger log = LoggerFactory.getLogger(Ec.class);
interface NavigationHost {
 void add(NavigationButton /* button */b);
 void remove(NavigationButton /* button */b);
}

NavigationButton button;
/** The runtime sidebar icon; never null so the required button cannot vanish silently. */
static BufferedImage awn() {
 try (InputStream /* stream */a = Ec.class.getResourceAsStream(msg("cs"))) {
  BufferedImage /* image */b = a == null ? null : ImageIO.read(a);
  if (b != null) return b;
  log.warn(msg("jq"));
 } catch (IOException /* ex */c) {
  log.warn(msg("jr"), c);
 }
 return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
}

static NavigationHost akh(ClientToolbar /* toolbar */a) {
 if (a == null) return null;
 return new NavigationHost() {
  public void add(NavigationButton /* navigationButton */b) {
   a.addNavigation(b);
  }
  public void remove(NavigationButton /* navigationButton */b) {
   a.removeNavigation(b);
  }
 };
}

/** @return true when this call installed the button (host, panel and icon present). */
synchronized boolean install(NavigationHost /* navigation */a, PluginPanel /* panel */b, BufferedImage /* icon */c) {
 if (button != null || a == null || b == null || c == null) return false;
 NavigationButton /* next */d = NavigationButton.builder().tooltip("GP Manager").icon(c).priority(5).panel(b).build();
 a.add(d);
 button = d;
 return true;
}

synchronized void uninstall(NavigationHost /* navigation */a) {
 if (button != null && a != null) a.remove(button);
 button = null;
}

synchronized NavigationButton button() {
 return button;
}
}
