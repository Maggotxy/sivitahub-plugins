package cn.originalrpg.sulfur;

import cn.originalrpg.api.service.ForgeImprintService;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public final class RpgSulfurPlugin extends JavaPlugin {
    private NamespacedKey recipeKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        SignedImprintService imprints = new SignedImprintService(this, messages, signingKey());
        getServer().getServicesManager().register(
                ForgeImprintService.class, imprints, this, ServicePriority.Normal);
        recipeKey = new NamespacedKey(this, "blank_imprint");
        registerRecipe(imprints);
        SulfurResonanceListener listener = new SulfurResonanceListener(this, imprints, messages, recipeKey);
        getServer().getPluginManager().registerEvents(listener, this);
        PluginCommand command = Objects.requireNonNull(getCommand("imprint"), "imprint command");
        command.setExecutor(listener);
        getLogger().info("Minecraft 26.2 sulfur resonance is connected to weighted equipment forging.");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (recipeKey != null) getServer().removeRecipe(recipeKey);
    }

    private void registerRecipe(SignedImprintService imprints) {
        if (!getConfig().getBoolean("recipe.enabled", true)) return;
        getServer().removeRecipe(recipeKey);
        ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, imprints.createRecipeResult());
        recipe.addIngredient(Material.PAPER);
        int sulfur = Math.clamp(getConfig().getInt("recipe.sulfur-cost", 2), 1, 7);
        int cinnabar = Math.clamp(getConfig().getInt("recipe.cinnabar-cost", 1), 1, 8 - sulfur);
        recipe.addIngredient(sulfur, Material.SULFUR);
        recipe.addIngredient(cinnabar, Material.CINNABAR);
        if (!getServer().addRecipe(recipe)) throw new IllegalStateException("Cannot register blank imprint recipe");
    }

    private byte[] signingKey() {
        String encoded = System.getenv("RPG_IMPRINT_SIGNING_KEY");
        if (encoded == null || encoded.isBlank()) encoded = getConfig().getString("security.signing-key", "");
        if (encoded == null || encoded.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            encoded = Base64.getEncoder().encodeToString(generated);
            getConfig().set("security.signing-key", encoded);
            saveConfig();
            getLogger().warning("Generated a local imprint signing key. Back up RpgSulfur/config.yml before migration.");
        }
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("security.signing-key must be Base64", exception);
        }
    }
}
