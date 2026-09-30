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
/** Test constructor without PluginManager. */
GroundItemsConfigReader(ConfigManager configManager) {
this(configManager, null);
}
Bc read() {
boolean enabled = wz();
GroundItemsConfig config;
try {
config = configManager.getConfig(GroundItemsConfig.class);
} catch (RuntimeException ex) {
log.debug(msg("bd"), ex);
return Bc.disabled();
}
if (config == null) {
return Bc.disabled();
}
return new Bc(
enabled,
config.getHighlightItems(),
config.getHiddenItems(),
config.showHighlightedOnly());
}
boolean wz() {
if (pluginManager == null) {
return false;
}
for (Plugin plugin : pluginManager.getPlugins()) {
if (plugin instanceof GroundItemsPlugin) {
return pluginManager.isPluginEnabled(plugin);
}
}
return false;
}
}
