package cn.originalrpg.story;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class StoryActorManager {
    private final RpgStoryPlugin plugin;
    private NPC actor;

    StoryActorManager(RpgStoryPlugin plugin) {
        this.plugin = plugin;
    }

    NPC ensureRegistryEntry() {
        NPCRegistry registry = Objects.requireNonNull(CitizensAPI.getNPCRegistry(), "Citizens registry");
        int id = plugin.getConfig().getInt("actor.citizens-id", 9101);
        UUID expectedUuid = UUID.fromString(Objects.requireNonNull(
                plugin.getConfig().getString("actor.citizens-uuid"), "actor.citizens-uuid"));
        NPC existing = registry.getById(id);
        if (existing != null && !expectedUuid.equals(existing.getUniqueId())) {
            throw new IllegalStateException("Citizens NPC id " + id + " is already used by " + existing.getName());
        }
        actor = existing == null
                ? registry.createNPC(EntityType.PLAYER, expectedUuid, id,
                plugin.getConfig().getString("actor.name", "主城导览 · 栖枝"))
                : existing;
        configure(actor);
        registry.saveToStore();
        return actor;
    }

    boolean spawnAtHub() {
        NPC current = actor == null ? ensureRegistryEntry() : actor;
        HubAnchor anchor = HubAnchor.resolve(plugin);
        List<?> offset = plugin.getConfig().getList("actor.offset-from-hub-center", List.of(-10.5, 1.05, 16.5));
        Location target = anchor.offset(offset);
        if (current.isSpawned()) {
            current.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            return true;
        }
        return current.spawn(target);
    }

    String status() {
        NPC current = actor;
        if (current == null) return "uninitialized";
        return "id=%d uuid=%s spawned=%s location=%s".formatted(
                current.getId(), current.getUniqueId(), current.isSpawned(),
                current.isSpawned() ? compact(current.getEntity().getLocation()) : "stored:" + compact(current.getStoredLocation()));
    }

    private void configure(NPC npc) {
        npc.setName(plugin.getConfig().getString("actor.name", "主城导览 · 栖枝"));
        npc.setProtected(true);
        npc.setUseMinecraftAI(false);
        SkinTrait skin = npc.getOrAddTrait(SkinTrait.class);
        skin.setFetchDefaultSkin(false);
        skin.setShouldUpdateSkins(false);
        LookClose look = npc.getOrAddTrait(LookClose.class);
        look.lookClose(true);
        look.setRange(8.0);
        look.setRealisticLooking(true);
        look.setDisableWhileNavigating(true);
        npc.getOrAddTrait(Equipment.class).set(
                Equipment.EquipmentSlot.HAND, new ItemStack(Material.RECOVERY_COMPASS));
    }

    private static String compact(Location location) {
        if (location == null || location.getWorld() == null) return "none";
        return "%s %.1f %.1f %.1f".formatted(
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }
}
