package cn.originalrpg.sulfur;

import cn.originalrpg.api.model.ForgeImprint;
import cn.originalrpg.api.service.ForgeImprintService;
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
import org.bukkit.plugin.Plugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

final class SignedImprintService implements ForgeImprintService {
    private static final String HMAC = "HmacSHA256";
    private static final Set<String> ALLOWED_BIASES = Set.of(
            "strength", "defense", "agility", "intelligence", "stamina", "critical", "healing", "skill-haste");

    private final SecretKeySpec secret;
    private final MessageService messages;
    private final NamespacedKey blank;
    private final NamespacedKey marker;
    private final NamespacedKey imprintId;
    private final NamespacedKey owner;
    private final NamespacedKey bias;
    private final NamespacedKey source;
    private final NamespacedKey strength;
    private final NamespacedKey signature;

    SignedImprintService(Plugin plugin, MessageService messages, byte[] signingKey) {
        if (signingKey.length < 32) throw new IllegalArgumentException("Imprint signing key must contain at least 32 bytes");
        this.secret = new SecretKeySpec(signingKey.clone(), HMAC);
        this.messages = messages;
        blank = new NamespacedKey(plugin, "blank_imprint");
        marker = new NamespacedKey(plugin, "imprint");
        imprintId = new NamespacedKey(plugin, "imprint_id");
        owner = new NamespacedKey(plugin, "owner");
        bias = new NamespacedKey(plugin, "bias");
        source = new NamespacedKey(plugin, "source");
        strength = new NamespacedKey(plugin, "strength");
        signature = new NamespacedKey(plugin, "signature");
    }

    ItemStack createBlank(Player audience) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(blank, PersistentDataType.BYTE, (byte) 1);
        meta.displayName(Component.text(messages.text(audience, "sulfur.blank-name"), NamedTextColor.YELLOW));
        meta.lore(messages.lines(audience, "sulfur.blank-lore").stream()
                .map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    ItemStack createRecipeResult() {
        return createBlank(null);
    }

    boolean isBlank(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && item.getType() == Material.PAPER
                && meta.getPersistentDataContainer().getOrDefault(blank, PersistentDataType.BYTE, (byte) 0) == (byte) 1;
    }

    ItemStack createImprint(Player ownerPlayer, String statBias, String sourceMaterial, int resonanceStrength) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        UUID id = UUID.randomUUID();
        data.set(marker, PersistentDataType.BYTE, (byte) 1);
        data.set(imprintId, PersistentDataType.STRING, id.toString());
        data.set(owner, PersistentDataType.STRING, ownerPlayer.getUniqueId().toString());
        data.set(bias, PersistentDataType.STRING, statBias);
        data.set(source, PersistentDataType.STRING, sourceMaterial.toLowerCase(Locale.ROOT));
        data.set(strength, PersistentDataType.INTEGER, Math.clamp(resonanceStrength, 1, 3));
        data.set(signature, PersistentDataType.STRING, Base64.getEncoder().encodeToString(digest(canonical(data))));
        meta.displayName(Component.text(messages.text(ownerPlayer, "sulfur.imprint-name"), NamedTextColor.GOLD));
        meta.setItemModel(new NamespacedKey("originalrpg", "sulfur_resonance_imprint"));
        double chance = ResonanceRules.biasedChance(resonanceStrength) * 100.0;
        meta.lore(List.of(
                Component.text(messages.text(ownerPlayer, "sulfur.imprint-bias", Map.of(
                        "stat", messages.text(ownerPlayer, "item-stats." + statBias))), NamedTextColor.AQUA),
                Component.text(messages.text(ownerPlayer, "sulfur.imprint-weight", Map.of(
                        "chance", String.format(Locale.ROOT, "%.1f", chance))), NamedTextColor.GREEN),
                Component.text(messages.text(ownerPlayer, "sulfur.imprint-source", Map.of(
                        "source", sourceMaterial.toLowerCase(Locale.ROOT))), NamedTextColor.GRAY),
                Component.text(messages.text(ownerPlayer, "sulfur.imprint-bound"), NamedTextColor.YELLOW)
        ));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public boolean isImprint(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .getOrDefault(marker, PersistentDataType.BYTE, (byte) 0) == (byte) 1;
    }

    @Override
    public Optional<ForgeImprint> inspect(ItemStack item, UUID expectedOwner) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null || item.getType() != Material.AMETHYST_SHARD) return Optional.empty();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (data.getOrDefault(marker, PersistentDataType.BYTE, (byte) 0) != (byte) 1) return Optional.empty();
        String stored = data.get(signature, PersistentDataType.STRING);
        try {
            UUID id = UUID.fromString(data.get(imprintId, PersistentDataType.STRING));
            UUID ownerId = UUID.fromString(data.get(owner, PersistentDataType.STRING));
            String statBias = data.get(bias, PersistentDataType.STRING);
            String sourceMaterial = data.get(source, PersistentDataType.STRING);
            int resonanceStrength = data.getOrDefault(strength, PersistentDataType.INTEGER, 0);
            if (!ownerId.equals(expectedOwner) || statBias == null || !ALLOWED_BIASES.contains(statBias) || sourceMaterial == null
                    || resonanceStrength < 1 || resonanceStrength > 3 || stored == null) return Optional.empty();
            byte[] actual = Base64.getDecoder().decode(stored);
            if (!MessageDigest.isEqual(actual, digest(canonical(data)))) return Optional.empty();
            return Optional.of(new ForgeImprint(id, ownerId, statBias, sourceMaterial, resonanceStrength));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
    }

    private String canonical(PersistentDataContainer data) {
        return String.join("\n",
                "schema=1",
                "imprint_id=" + data.getOrDefault(imprintId, PersistentDataType.STRING, ""),
                "owner=" + data.getOrDefault(owner, PersistentDataType.STRING, ""),
                "bias=" + data.getOrDefault(bias, PersistentDataType.STRING, ""),
                "source=" + data.getOrDefault(source, PersistentDataType.STRING, ""),
                "strength=" + data.getOrDefault(strength, PersistentDataType.INTEGER, 0));
    }

    private byte[] digest(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(secret);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }
}
