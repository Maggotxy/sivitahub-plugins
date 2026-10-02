package cn.originalrpg.items.security;

import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.model.EquipmentTraitCatalog;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.rules.EquipmentBaseRules;
import cn.originalrpg.api.model.EquipmentOperationMarker;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class EquipmentSecurity implements EquipmentService {
    private static final String HMAC = "HmacSHA256";
    private static final Set<String> RARITIES = Set.of("COMMON", "RARE", "EPIC", "LEGENDARY");
    private static final Set<String> DUNGEON_CORE_SOURCES = Set.of(
            "ancient-ruins", "ancient-ruins-hidden",
            "sulfur-depths", "sulfur-depths-hidden",
            "storm-vault", "storm-vault-hidden");

    private final SecretKeySpec key;
    private final NamespacedKey itemId = key("rpgitems:item_id");
    private final NamespacedKey crafter = key("rpgitems:crafter");
    private final NamespacedKey owner = key("rpgitems:owner");
    private final NamespacedKey tradeUnlocked = key("rpgitems:trade_unlocked");
    private final NamespacedKey forgeImprintId = key("rpgitems:forge_imprint_id");
    private final NamespacedKey forgeBias = key("rpgitems:forge_bias");
    private final NamespacedKey forgeSource = key("rpgitems:forge_source");
    private final NamespacedKey forgeStrength = key("rpgitems:forge_strength");
    private final NamespacedKey dungeonCoreSource = key("rpgitems:dungeon_core_source");
    private final NamespacedKey rarity = key("rpgitems:rarity");
    private final NamespacedKey totalSlots = key("rpgitems:total_slots");
    private final NamespacedKey rareTrait = key("rpgitems:rare_trait");
    private final NamespacedKey equipmentType = key("rpgitems:equipment_type");
    private final NamespacedKey weaponArchetype = key("rpgitems:weapon_archetype");
    private final NamespacedKey revealedSlots = key("rpgitems:revealed_slots");
    private final NamespacedKey itemExperience = key("rpgitems:item_xp");
    private final NamespacedKey refineCount = key("rpgenhancement:refine_count");
    private final NamespacedKey limitLevel = key("rpgenhancement:limit_level");
    private final NamespacedKey[] refinedSlots = refinedSlotKeys();
    private final NamespacedKey schemaVersion = key("rpgitems:schema_version");
    private final NamespacedKey signature = key("rpgitems:signature");
    private final Map<String, Optional<EquipmentSnapshot>> inspectionCache = Collections.synchronizedMap(
            new LinkedHashMap<>(512, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Optional<EquipmentSnapshot>> eldest) {
                    return size() > 4096;
                }
            });

    public EquipmentSecurity(byte[] secret) {
        if (secret.length < 32) {
            throw new IllegalArgumentException("Equipment signing secret must contain at least 32 bytes");
        }
        key = new SecretKeySpec(secret.clone(), HMAC);
    }

    @Override
    public boolean isCustomEquipment(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(itemId, PersistentDataType.STRING);
    }

    @Override
    public boolean isAuthentic(ItemStack item) {
        return !EquipmentOperationMarker.isMarked(item) && inspect(item).isPresent();
    }

    /**
     * Repairs and marks authentic custom equipment as permanent. Vanilla item
     * damage is deliberately outside the signed payload, so this migration
     * does not alter the equipment identity or progression.
     */
    public boolean preserveDurability(ItemStack item) {
        if (!isAuthentic(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        meta.setUnbreakable(true);
        if (meta instanceof Damageable damageable) damageable.setDamage(0);
        item.setItemMeta(meta);
        return true;
    }

    @Override
    public boolean isBound(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(owner, PersistentDataType.STRING);
    }

    @Override
    public boolean isTradeUnlocked(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && isAuthentic(item) && meta.getPersistentDataContainer()
                .getOrDefault(tradeUnlocked, PersistentDataType.BYTE, (byte) 0) == (byte) 1;
    }

    @Override
    public Optional<UUID> itemId(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return Optional.empty();
        String value = meta.getPersistentDataContainer().get(itemId, PersistentDataType.STRING);
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<EquipmentSnapshot> inspect(ItemStack item) {
        if (EquipmentOperationMarker.isMarked(item)) return Optional.empty();
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return Optional.empty();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String stored = data.get(signature, PersistentDataType.STRING);
        if (stored == null || stored.length() != 44 || !validIdentity(data)) return Optional.empty();
        try {
            int schema = data.getOrDefault(schemaVersion, PersistentDataType.INTEGER, 0);
            String canonical = switch (schema) {
                case 1 -> canonicalV1(data);
                case 2 -> canonicalV2(item, data);
                case 3 -> canonicalV3(item, data);
                case 4 -> canonicalV4(item, data);
                default -> canonicalV5(item, data);
            };
            String cacheKey = stored + '\u0000' + canonical;
            Optional<EquipmentSnapshot> cached = inspectionCache.get(cacheKey);
            if (cached != null) return cached;
            byte[] actual = Base64.getDecoder().decode(stored);
            if (!MessageDigest.isEqual(digest(canonical), actual)) {
                inspectionCache.put(cacheKey, Optional.empty());
                return Optional.empty();
            }
            UUID id = UUID.fromString(data.get(itemId, PersistentDataType.STRING));
            UUID creator = UUID.fromString(data.get(crafter, PersistentDataType.STRING));
            String ownerValue = data.get(owner, PersistentDataType.STRING);
            int revealed = Math.clamp(data.getOrDefault(revealedSlots, PersistentDataType.INTEGER, 0), 0,
                    data.getOrDefault(totalSlots, PersistentDataType.INTEGER, 0));
            Map<String, Integer> stats = new HashMap<>();
            for (int index = 0; index < revealed; index++) {
                String encoded = data.get(refinedSlots[index], PersistentDataType.STRING);
                if (encoded == null) continue;
                int separator = encoded.lastIndexOf(':');
                if (separator <= 0 || separator == encoded.length() - 1) continue;
                try {
                    stats.merge(encoded.substring(0, separator), Integer.parseInt(encoded.substring(separator + 1)), Integer::sum);
                } catch (NumberFormatException ignored) {
                    // Invalid optional stat data is ignored; the signature still prevents silent replacement.
                }
            }
            String trait = data.get(rareTrait, PersistentDataType.STRING);
            String itemRarity = data.getOrDefault(rarity, PersistentDataType.STRING, "COMMON");
            String itemEquipmentType = data.getOrDefault(
                    equipmentType, PersistentDataType.STRING, inferEquipmentType(item));
            int basePower = EquipmentBaseRules.power(item.getType().name(), itemRarity, itemEquipmentType);
            int baseGuard = EquipmentBaseRules.guard(item.getType().name(), itemRarity, itemEquipmentType);
            if (basePower > 0) stats.put("base-power", basePower);
            if (baseGuard > 0) stats.put("base-guard", baseGuard);
            Optional<EquipmentSnapshot> inspected = Optional.of(new EquipmentSnapshot(
                    id,
                    creator,
                    ownerValue == null ? Optional.empty() : Optional.of(UUID.fromString(ownerValue)),
                    itemRarity,
                    itemEquipmentType,
                    data.getOrDefault(weaponArchetype, PersistentDataType.STRING, "UNIVERSAL"),
                    data.getOrDefault(totalSlots, PersistentDataType.INTEGER, 0),
                    revealed,
                    Math.max(0, data.getOrDefault(itemExperience, PersistentDataType.INTEGER, 0)),
                    Math.max(0, data.getOrDefault(limitLevel, PersistentDataType.INTEGER, 0)),
                    trait == null || trait.isBlank() ? Optional.empty() : Optional.of(trait),
                    Map.copyOf(stats)
            ));
            inspectionCache.put(cacheKey, inspected);
            return inspected;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void sign(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("Equipment has no item meta");
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (!validIdentity(data)) throw new IllegalArgumentException("Equipment identity is incomplete");
        int schema = data.getOrDefault(schemaVersion, PersistentDataType.INTEGER, 0);
        String canonical = switch (schema) {
            case 1 -> canonicalV1(data);
            case 2 -> canonicalV2(item, data);
            case 3 -> canonicalV3(item, data);
            case 4 -> canonicalV4(item, data);
            default -> canonicalV5(item, data);
        };
        data.set(signature, PersistentDataType.STRING, Base64.getEncoder().encodeToString(digest(canonical)));
        item.setItemMeta(meta);
    }

    @Override
    public boolean unlockForTrade(ItemStack item, UUID expectedOwner) {
        Optional<EquipmentSnapshot> inspected = inspect(item);
        if (inspected.isEmpty() || inspected.get().boundOwner().isEmpty()
                || !inspected.get().boundOwner().orElseThrow().equals(expectedOwner)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.remove(owner);
        data.set(tradeUnlocked, PersistentDataType.BYTE, (byte) 1);
        int currentSchema = data.getOrDefault(schemaVersion, PersistentDataType.INTEGER, 3);
        data.set(schemaVersion, PersistentDataType.INTEGER, Math.clamp(currentSchema, 3, 5));
        item.setItemMeta(meta);
        sign(item);
        return true;
    }

    public NamespacedKey schemaVersionKey() {
        return schemaVersion;
    }

    private boolean validIdentity(PersistentDataContainer data) {
        String id = data.get(itemId, PersistentDataType.STRING);
        String creator = data.get(crafter, PersistentDataType.STRING);
        String boundOwner = data.get(owner, PersistentDataType.STRING);
        String itemRarity = data.get(rarity, PersistentDataType.STRING);
        Integer slots = data.get(totalSlots, PersistentDataType.INTEGER);
        Integer schema = data.get(schemaVersion, PersistentDataType.INTEGER);
        String trait = data.get(rareTrait, PersistentDataType.STRING);
        try {
            UUID.fromString(id == null ? "" : id);
            UUID.fromString(creator == null ? "" : creator);
            if (boundOwner != null) UUID.fromString(boundOwner);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (schema == null || schema < 1 || schema > 5 || slots == null || slots < 0 || slots > 8
                || itemRarity == null || !RARITIES.contains(itemRarity)) return false;
        if (trait != null && !trait.isBlank() && !EquipmentTraitCatalog.implemented().contains(trait)) return false;
        if (schema == 4) {
            String imprint = data.get(forgeImprintId, PersistentDataType.STRING);
            String bias = data.get(forgeBias, PersistentDataType.STRING);
            String source = data.get(forgeSource, PersistentDataType.STRING);
            int strength = data.getOrDefault(forgeStrength, PersistentDataType.INTEGER, 0);
            boolean allAbsent = imprint == null && bias == null && source == null && strength == 0;
            boolean allPresent = imprint != null && bias != null && source != null && strength >= 1 && strength <= 3;
            if (!allAbsent && !allPresent) return false;
            if (allPresent) {
                try {
                    UUID.fromString(imprint);
                } catch (IllegalArgumentException exception) {
                    return false;
                }
            }
        }
        if (schema == 5) {
            String coreSource = data.get(dungeonCoreSource, PersistentDataType.STRING);
            if (coreSource != null && !DUNGEON_CORE_SOURCES.contains(coreSource)) return false;
            String imprint = data.get(forgeImprintId, PersistentDataType.STRING);
            String bias = data.get(forgeBias, PersistentDataType.STRING);
            String source = data.get(forgeSource, PersistentDataType.STRING);
            int strength = data.getOrDefault(forgeStrength, PersistentDataType.INTEGER, 0);
            boolean allAbsent = imprint == null && bias == null && source == null && strength == 0;
            boolean allPresent = imprint != null && bias != null && source != null && strength >= 1 && strength <= 3;
            if (!allAbsent && !allPresent) return false;
            if (allPresent) {
                try {
                    UUID.fromString(imprint);
                } catch (IllegalArgumentException exception) {
                    return false;
                }
            }
        }
        return true;
    }

    private String canonicalV1(PersistentDataContainer data) {
        return String.join("\n",
                "schema=" + data.getOrDefault(schemaVersion, PersistentDataType.INTEGER, 0),
                "item_id=" + data.getOrDefault(itemId, PersistentDataType.STRING, ""),
                "crafter=" + data.getOrDefault(crafter, PersistentDataType.STRING, ""),
                "owner=" + data.getOrDefault(owner, PersistentDataType.STRING, ""),
                "rarity=" + data.getOrDefault(rarity, PersistentDataType.STRING, ""),
                "total_slots=" + data.getOrDefault(totalSlots, PersistentDataType.INTEGER, -1),
                "rare_trait=" + data.getOrDefault(rareTrait, PersistentDataType.STRING, "")
        );
    }

    private String canonicalV2(ItemStack item, PersistentDataContainer data) {
        StringBuilder value = new StringBuilder(canonicalV1(data))
                .append("\nmaterial=").append(item.getType().getKey())
                .append("\nequipment_type=").append(data.getOrDefault(equipmentType, PersistentDataType.STRING, ""))
                .append("\nweapon_archetype=").append(data.getOrDefault(weaponArchetype, PersistentDataType.STRING, ""))
                .append("\nrevealed_slots=").append(data.getOrDefault(revealedSlots, PersistentDataType.INTEGER, 0))
                .append("\nitem_xp=").append(data.getOrDefault(itemExperience, PersistentDataType.INTEGER, 0))
                .append("\nrefine_count=").append(data.getOrDefault(refineCount, PersistentDataType.INTEGER, 0))
                .append("\nlimit_level=").append(data.getOrDefault(limitLevel, PersistentDataType.INTEGER, 0));
        for (int index = 0; index < refinedSlots.length; index++) {
            value.append("\nslot_").append(index).append('=')
                    .append(data.getOrDefault(refinedSlots[index], PersistentDataType.STRING, ""));
        }
        return value.toString();
    }

    private String canonicalV3(ItemStack item, PersistentDataContainer data) {
        return canonicalV2(item, data) + "\ntrade_unlocked="
                + data.getOrDefault(tradeUnlocked, PersistentDataType.BYTE, (byte) 0);
    }

    private String canonicalV4(ItemStack item, PersistentDataContainer data) {
        return canonicalV3(item, data)
                + "\nforge_imprint_id=" + data.getOrDefault(forgeImprintId, PersistentDataType.STRING, "")
                + "\nforge_bias=" + data.getOrDefault(forgeBias, PersistentDataType.STRING, "")
                + "\nforge_source=" + data.getOrDefault(forgeSource, PersistentDataType.STRING, "")
                + "\nforge_strength=" + data.getOrDefault(forgeStrength, PersistentDataType.INTEGER, 0);
    }

    private String canonicalV5(ItemStack item, PersistentDataContainer data) {
        return canonicalV4(item, data)
                + "\ndungeon_core_source="
                + data.getOrDefault(dungeonCoreSource, PersistentDataType.STRING, "");
    }

    private byte[] digest(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    private static NamespacedKey key(String value) {
        return java.util.Objects.requireNonNull(NamespacedKey.fromString(value), value);
    }

    private static NamespacedKey[] refinedSlotKeys() {
        NamespacedKey[] keys = new NamespacedKey[8];
        for (int index = 0; index < keys.length; index++) keys[index] = key("rpgenhancement:slot_" + index);
        return keys;
    }

    private static String inferEquipmentType(ItemStack item) {
        return item.getType().name().endsWith("_HELMET") || item.getType().name().endsWith("_CHESTPLATE")
                || item.getType().name().endsWith("_LEGGINGS") || item.getType().name().endsWith("_BOOTS")
                || item.getType() == org.bukkit.Material.SHIELD ? "ARMOR" : "WEAPON";
    }
}
