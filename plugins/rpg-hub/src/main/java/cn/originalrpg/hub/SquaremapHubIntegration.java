package cn.originalrpg.hub;

import org.bukkit.plugin.Plugin;
import xyz.jpenilla.squaremap.api.BukkitAdapter;
import xyz.jpenilla.squaremap.api.Key;
import xyz.jpenilla.squaremap.api.MapWorld;
import xyz.jpenilla.squaremap.api.Point;
import xyz.jpenilla.squaremap.api.SimpleLayerProvider;
import xyz.jpenilla.squaremap.api.Squaremap;
import xyz.jpenilla.squaremap.api.SquaremapProvider;
import xyz.jpenilla.squaremap.api.marker.Marker;
import xyz.jpenilla.squaremap.api.marker.MarkerOptions;

import java.awt.Color;
import java.util.Optional;

final class SquaremapHubIntegration {
    private static final Key LAYER_KEY = Key.of("sivita_hub_landmarks");

    private SquaremapHubIntegration() {
    }

    static Runnable register(Plugin plugin, HubSite site) {
        try {
            Squaremap squaremap = SquaremapProvider.get();
            Optional<MapWorld> optional = squaremap.getWorldIfEnabled(BukkitAdapter.worldIdentifier(site.world()));
            if (optional.isEmpty()) return () -> { };
            MapWorld world = optional.get();
            if (world.layerRegistry().hasEntry(LAYER_KEY)) world.layerRegistry().unregister(LAYER_KEY);
            SimpleLayerProvider layer = SimpleLayerProvider.builder("希维塔大陆主城")
                    .showControls(true).defaultHidden(false).layerPriority(20).zIndex(40).build();
            Marker boundary = Marker.circle(Point.of(site.centerX(), site.centerZ()), site.protectionRadius())
                    .markerOptions(MarkerOptions.builder()
                            .stroke(true).strokeColor(new Color(128, 169, 73)).strokeWeight(3).strokeOpacity(0.9)
                            .fill(true).fillColor(new Color(64, 92, 49)).fillOpacity(0.16)
                            .hoverTooltip("希维塔大陆主城 · 安全区")
                            .clickTooltip("<b>希维塔大陆主城</b><br>NPC、锻造、市场、副本与拓荒入口"));
            layer.addMarker(Key.of("hub_boundary"), boundary);
            for (HubRole role : HubRole.values()) {
                Color color = new Color(role.color().getRed(), role.color().getGreen(), role.color().getBlue());
                String name = plugin.getConfig().getString("npcs." + role.id() + ".name", role.id());
                String subtitle = plugin.getConfig().getString("npcs." + role.id() + ".subtitle", "");
                Marker marker = Marker.circle(Point.of(site.centerX() + role.offsetX(), site.centerZ() + role.offsetZ()), 2.6)
                        .markerOptions(MarkerOptions.builder()
                                .stroke(true).strokeColor(color).strokeWeight(2).strokeOpacity(0.95)
                                .fill(true).fillColor(color).fillOpacity(0.5)
                                .hoverTooltip(name)
                                .clickTooltip("<b>" + escape(name) + "</b><br>" + escape(subtitle)));
                layer.addMarker(Key.of("npc_" + role.id()), marker);
            }
            world.layerRegistry().register(LAYER_KEY, layer);
            plugin.getLogger().info("squaremap hub layer active with " + (HubRole.values().length + 1) + " public markers.");
            return () -> {
                if (world.layerRegistry().hasEntry(LAYER_KEY)) world.layerRegistry().unregister(LAYER_KEY);
            };
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not register squaremap hub landmarks: " + exception.getMessage());
            return () -> { };
        }
    }

    static boolean worldReady(HubSite site) {
        try {
            return SquaremapProvider.get().getWorldIfEnabled(
                    BukkitAdapter.worldIdentifier(site.world())).isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
