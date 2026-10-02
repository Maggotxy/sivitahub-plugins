package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.model.EquipmentTraitCatalog;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class LegacyBlueprintService {
    private static final String HMAC = "HmacSHA256";
    private static final java.util.Set<String> EQUIPMENT_TYPES = java.util.Set.of("WEAPON", "ARMOR");

    private final SecretKeySpec signingKey;
    private final java.util.List<SecretKeySpec> verificationKeys;
    private final NamespacedKey marker = key("rpgitems:legacy_blueprint");
    private final NamespacedKey blueprintId = key("rpgitems:blueprint_id");
    private final NamespacedKey owner = key("rpgitems:material_owner");
    private final NamespacedKey sourceItem = key("rpgitems:blueprint_source_item");
    private final NamespacedKey trait = key("rpgitems:blueprint_trait");
    private final NamespacedKey equipmentType = key("rpgitems:blueprint_equipment_type");
    private final NamespacedKey archetype = key("rpgitems:blueprint_archetype");
    private final NamespacedKey signature = key("rpgitems:blueprint_signature");

    public LegacyBlueprintService(byte[] signingKey) {
        this(signingKey, java.util.List.of());
    }

    /** G-003 轮换支持：校验时接受当前密钥与被替换下来的旧密钥（新→旧）。 */
    public LegacyBlueprintService(byte[] signingKey, java.util.List<byte[]> retiredSigningKeys) {
        if (signingKey == null || signingKey.length < 32) {
            throw new IllegalArgumentException("Blueprint signing key must contain at least 32 bytes");
        }
        this.signingKey = new SecretKeySpec(signingKey.clone(), HMAC);
        java.util.ArrayList<SecretKeySpec> accepted = new java.util.ArrayList<>(1 + retiredSigningKeys.size());
        accepted.add(this.signingKey);
        for (byte[] retired : retiredSigningKeys) {
            if (retired == null || retired.length < 32) {
                throw new IllegalArgumentException("Blueprint retired signing key must contain at least 32 bytes");
            }
            accepted.add(new SecretKeySpec(retired.clone(), HMAC));
        }
        verificationKeys = java.util.List.copyOf(accepted);
    }

    ItemStack create(Player player, EquipmentSnapshot source, MessageService messages, UUID blueprintIdentity) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(marker, PersistentDataType.BYTE, (byte) 1);
        data.set(blueprintId, PersistentDataType.STRING, blueprintIdentity.toString());
        data.set(owner, PersistentDataType.STRING, player.getUniqueId().toString());
        data.set(sourceItem, PersistentDataType.STRING, source.itemId().toString());
        data.set(trait, PersistentDataType.STRING, source.rareTrait().orElseThrow());
        data.set(equipmentType, PersistentDataType.STRING, source.equipmentType());
        data.set(archetype, PersistentDataType.STRING, source.archetype());
        data.set(signature, PersistentDataType.STRING, Base64.getEncoder().encodeToString(digest(canonical(data))));
        meta.displayName(Component.text(messages.text(player, "items.legacy.blueprint-name"), NamedTextColor.LIGHT_PURPLE));
        meta.setItemModel(new NamespacedKey("originalrpg", "legacy_blueprint"));
        meta.lore(List.of(
                Component.text(messages.text(player, "items.legacy.blueprint-trait", java.util.Map.of(
                        "trait", messages.text(player, "item-traits." + source.rareTrait().orElseThrow()))), NamedTextColor.LIGHT_PURPLE),
                Component.text(messages.text(player, "items.legacy.blueprint-type", java.util.Map.of(
                        "type", messages.text(player, "items.legacy.types." + source.equipmentType().toLowerCase(Locale.ROOT)))), NamedTextColor.AQUA),
                Component.text(messages.text(player, "items.legacy.blueprint-rule"), NamedTextColor.GRAY),
                Component.text(messages.text(player, "items.legacy.blueprint-bound"), NamedTextColor.YELLOW)
        ));
        item.setItemMeta(meta);
        return item;
    }

    boolean isBlueprint(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .getOrDefault(marker, PersistentDataType.BYTE, (byte) 0) == (byte) 1;
    }

    Optional<LegacyBlueprint> inspect(ItemStack item, UUID expectedOwner) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null || item.getType() != Material.PAPER) return Optional.empty();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (data.getOrDefault(marker, PersistentDataType.BYTE, (byte) 0) != (byte) 1) return Optional.empty();
        String stored = data.get(signature, PersistentDataType.STRING);
        try {
            UUID id = UUID.fromString(data.get(blueprintId, PersistentDataType.STRING));
            UUID ownerId = UUID.fromString(data.get(owner, PersistentDataType.STRING));
            UUID sourceId = UUID.fromString(data.get(sourceItem, PersistentDataType.STRING));
            String rareTrait = data.get(trait, PersistentDataType.STRING);
            String type = data.get(equipmentType, PersistentDataType.STRING);
            String direction = data.get(archetype, PersistentDataType.STRING);
            if (!ownerId.equals(expectedOwner) || !EquipmentTraitCatalog.implemented().contains(rareTrait)
                    || !EQUIPMENT_TYPES.contains(type) || direction == null || direction.isBlank() || stored == null) {
                return Optional.empty();
            }
            byte[] actual = Base64.getDecoder().decode(stored);
            if (!matchesAnyKey(canonical(data), actual)) return Optional.empty();
            return Optional.of(new LegacyBlueprint(id, ownerId, sourceId, rareTrait, type, direction));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
    }

    private String canonical(PersistentDataContainer data) {
        return String.join("\n",
                "schema=1",
                "blueprint_id=" + data.getOrDefault(blueprintId, PersistentDataType.STRING, ""),
                "owner=" + data.getOrDefault(owner, PersistentDataType.STRING, ""),
                "source_item=" + data.getOrDefault(sourceItem, PersistentDataType.STRING, ""),
                "trait=" + data.getOrDefault(trait, PersistentDataType.STRING, ""),
                "equipment_type=" + data.getOrDefault(equipmentType, PersistentDataType.STRING, ""),
                "archetype=" + data.getOrDefault(archetype, PersistentDataType.STRING, ""));
    }

    /** 新签名：只用当前密钥。 */
    private byte[] digest(String value) {
        return digest(value, signingKey);
    }

    /** 校验：轮换集合里任意一把密钥匹配即通过。 */
    private boolean matchesAnyKey(String value, byte[] expected) {
        for (SecretKeySpec candidate : verificationKeys) {
            if (MessageDigest.isEqual(digest(value, candidate), expected)) return true;
        }
        return false;
    }

    private static byte[] digest(String value, SecretKeySpec secret) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(secret);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    private static NamespacedKey key(String value) {
        return java.util.Objects.requireNonNull(NamespacedKey.fromString(value), value);
    }
}
