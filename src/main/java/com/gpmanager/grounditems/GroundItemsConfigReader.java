package com.gpmanager;
import static com.gpmanager.Ak.msg;
import lombok.RequiredArgsConstructor;
import javax.inject.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.grounditems.*;
import org.slf4j.*;
/**
* Reads only the active Ground Items list preferences through supported RuneLite APIs.
* Never reflects into private Ground Items fields or package-private matchers.
*/
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GroundItemsConfigReader {
static final Logger log = LoggerFactory.getLogger(GroundItemsConfigReader.class);
final ConfigManager configManager;
final PluginManager pluginManager;
Bc read() {
 boolean /* enabled */a = wz();
 GroundItemsConfig /* config */b;
 try {
  b = configManager.getConfig(GroundItemsConfig.class);
 } catch (RuntimeException /* ex */c) {
  log.debug(msg("bd"), c);
  return Bc.disabled();
 }
 if (b == null) return Bc.disabled();
 var /* read */d = new Bc(a, b.getHighlightItems(), b.getHiddenItems(),
 b.showHighlightedOnly());
 d.insane = b.insaneValuePrice();
 return d;
}

boolean wz() {
 if (pluginManager == null) return false;
 for (Plugin /* plugin */a : pluginManager.getPlugins()) {
  if (a instanceof GroundItemsPlugin) return pluginManager.isPluginEnabled(a);
 }
 return false;
}
}
