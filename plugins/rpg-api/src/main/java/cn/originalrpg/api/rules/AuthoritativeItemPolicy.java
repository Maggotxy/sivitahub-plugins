package cn.originalrpg.api.rules;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Set;

/**
 * Boundary between server-owned item state and metadata-blind vanilla
 * processing. Current item-producing modules and the shared
 * {@code originalrpg} namespace are listed explicitly so new markers inside a
 * module are protected without per-recipe blacklists, while unrelated plugins
 * whose names happen to start with "rpg" are not captured.
 */
public final class AuthoritativeItemPolicy {
    private static final Set<String> AUTHORITATIVE_NAMESPACES = Set.of(
            "rpgapi",
            "rpgadmin",
            "rpgainpc",
            "rpgclasses",
            "rpgcombat",
            "rpgcommerce",
            "rpgcommunity",
            "rpgcore",
            "rpgcosmetics",
            "rpgdungeons",
            "rpgeconomy",
            "rpgenhancement",
            "rpgessentials",
            "rpgguide",
            "rpghub",
            "rpgintegrations",
            "rpgitems",
            "rpgmarket",
            "rpgmobs",
            "rpgperformance",
            "rpgpets",
            "rpgskills",
            "rpgstory",
            "rpgsulfur",
            "rpgworld",
            "originalrpg"
    );
    private static final NamespacedKey EQUIPMENT_SIGNATURE = Objects.requireNonNull(
            NamespacedKey.fromString("rpgitems:signature"));

    private AuthoritativeItemPolicy() {
    }

    public enum Operation {
        CRAFTING,
        AUTOMATIC_CRAFTING,
        COOKING,
        STONECUTTING,
        GRINDSTONE,
        ANVIL,
        SMITHING
    }

    /**
     * Evidence is deliberately produced by the listener through the signed
     * EquipmentService. Marker presence alone can never make a forged or
     * malformed item eligible for a vanilla transformation.
     */
    public record TransformEvidence(
            boolean hasAuthoritativeInput,
            boolean primaryAuthenticEquipment,
            boolean hasAuthoritativeSecondaryInput,
            boolean resultAuthenticEquipment,
            boolean sameEquipmentIdentity,
            boolean persistentDataPreserved
    ) {
    }

    public static boolean isAuthoritativeNamespace(String namespace) {
        if (namespace == null) return false;
        String normalized = namespace.toLowerCase(java.util.Locale.ROOT);
        return AUTHORITATIVE_NAMESPACES.contains(normalized);
    }

    public static boolean isAuthoritativeKey(NamespacedKey key) {
        return key != null && isAuthoritativeNamespace(key.getNamespace());
    }

    public static boolean isAuthoritative(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().getKeys().stream()
                .anyMatch(AuthoritativeItemPolicy::isAuthoritativeKey);
    }

    /**
     * Only a signed equipment rename/enchantment (anvil) or material upgrade
     * (smithing) may cross the boundary. Crafting, cooking, stonecutting and
     * grinding are metadata-insensitive conversion sinks and always fail
     * closed when any authoritative input is present.
     */
    public static boolean allows(Operation operation, TransformEvidence evidence) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(evidence, "evidence");
        if (!evidence.hasAuthoritativeInput()) return true;
        if (operation != Operation.ANVIL && operation != Operation.SMITHING) return false;
        return evidence.primaryAuthenticEquipment()
                && !evidence.hasAuthoritativeSecondaryInput()
                && evidence.resultAuthenticEquipment()
                && evidence.sameEquipmentIdentity()
                && evidence.persistentDataPreserved();
    }

    /**
     * Compare cloned metadata after removing the equipment signature. Smithing
     * legitimately re-signs the same identity because the vanilla material is
     * part of the signature payload; every other PDC byte must remain exactly
     * unchanged. Returning false on serialization failure is intentional.
     */
    public static boolean persistentDataPreserved(ItemStack input, ItemStack result) {
        ItemMeta before = input == null ? null : input.getItemMeta();
        ItemMeta after = result == null ? null : result.getItemMeta();
        if (before == null || after == null) return false;
        try {
            byte[] beforeBytes = normalizedPersistentData(before);
            byte[] afterBytes = normalizedPersistentData(after);
            return MessageDigest.isEqual(beforeBytes, afterBytes);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static byte[] normalizedPersistentData(ItemMeta source) throws IOException {
        ItemMeta copy = source.clone();
        copy.getPersistentDataContainer().remove(EQUIPMENT_SIGNATURE);
        return copy.getPersistentDataContainer().serializeToBytes();
    }
}
