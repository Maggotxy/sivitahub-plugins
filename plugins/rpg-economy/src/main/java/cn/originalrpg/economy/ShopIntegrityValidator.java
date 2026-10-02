package cn.originalrpg.economy;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/** Startup fail-closed checks for every system-controlled Gold/item edge. */
final class ShopIntegrityValidator {
    private static final Set<String> ALLOWED_MARKERS = Set.of("adaptive", "boss", "reward");

    private ShopIntegrityValidator() {}

    static Report validate(JavaPlugin economyPlugin) {
        Set<Material> systemIssued = new LinkedHashSet<>();
        org.bukkit.plugin.Plugin commerce = economyPlugin.getServer().getPluginManager().getPlugin("RpgCommerce");
        if (commerce instanceof JavaPlugin commercePlugin) {
            collectMaterials(commercePlugin.getConfig(), "supply-shop.products", systemIssued);
            collectMaterials(commercePlugin.getConfig(), "products", systemIssued);
            collectMaterials(commercePlugin.getConfig(), "rewards", systemIssued);
        }
        collectMaterials(economyPlugin.getConfig(), "earned-shop", systemIssued);
        collectMaterials(economyPlugin.getConfig(), "black-market.offers", systemIssued);

        Map<Material, Set<Material>> recipeEdges = recipeEdges(economyPlugin.getServer().recipeIterator());
        return validate(economyPlugin.getConfig(), systemIssued, recipeEdges, Material::getMaxStackSize);
    }

    static Report validate(
            FileConfiguration economy,
            Set<Material> systemIssuedMaterials,
            Map<Material, Set<Material>> recipeEdges
    ) {
        // Paper's registry-backed Material#getMaxStackSize is unavailable in a
        // plain unit-test JVM. Runtime validation uses the overload above and
        // therefore always applies the server's exact component value.
        return validate(economy, systemIssuedMaterials, recipeEdges, ignored -> 64);
    }

    private static Report validate(
            FileConfiguration economy,
            Set<Material> systemIssuedMaterials,
            Map<Material, Set<Material>> recipeEdges,
            ToIntFunction<Material> maximumStackSize
    ) {
        Set<String> disabledCommissions = new LinkedHashSet<>();
        Set<String> disabledOffers = new LinkedHashSet<>();
        List<String> findings = new ArrayList<>();
        Set<Material> systemIssuedClosure = reachable(systemIssuedMaterials, recipeEdges);

        int defaultEvidence = economy.getInt("commissions-policy.default-evidence-cost", 0);
        int totalDaily = economy.getInt("commissions-policy.total-daily-limit", 0);
        int defaultOfferDaily = economy.getInt("commissions-policy.default-offer-daily-limit", 0);
        int minimumPlayMinutes = economy.getInt("commissions-policy.minimum-play-minutes", 0);
        BigDecimal maxGoldPerEvidence = decimal(economy,
                "commissions-policy.maximum-gold-per-evidence", BigDecimal.ZERO);
        long maxMeritPerEvidence = economy.getLong(
                "commissions-policy.maximum-merit-per-evidence", 0L);
        ConfigurationSection commissions = economy.getConfigurationSection("commissions");
        Set<Material> purchasedMaterials = new HashSet<>();
        if (commissions != null) {
            for (String id : commissions.getKeys(false)) {
                String path = "commissions." + id;
                Material material = Material.matchMaterial(economy.getString(path + ".material", ""));
                int amount = economy.getInt(path + ".amount", 0);
                int evidence = economy.getInt(path + ".evidence-cost", defaultEvidence);
                int daily = economy.getInt(path + ".daily-limit", defaultOfferDaily);
                BigDecimal gold = decimal(economy, path + ".gold", BigDecimal.valueOf(-1L));
                long merit = economy.getLong(path + ".merit", 0L);
                boolean invalid = material == null || isAir(material)
                        || amount <= 0 || material != null && amount > maximumStackSize.applyAsInt(material)
                        || gold.signum() <= 0
                        || evidence <= 0 || daily <= 0 || totalDaily <= 0 || minimumPlayMinutes < 15
                        || maxGoldPerEvidence.signum() <= 0 || maxMeritPerEvidence <= 0L || merit < 0L
                        || gold.compareTo(maxGoldPerEvidence.multiply(BigDecimal.valueOf(evidence))) > 0
                        || BigDecimal.valueOf(merit).compareTo(BigDecimal.valueOf(maxMeritPerEvidence)
                        .multiply(BigDecimal.valueOf(evidence))) > 0;
                if (material != null && !purchasedMaterials.add(material)) {
                    invalid = true;
                    findings.add("commission " + id + " duplicates a system buy material: " + material);
                }
                if (material != null && systemIssuedClosure.contains(material)) {
                    invalid = true;
                    findings.add("commission " + id
                            + " is reachable from a system-issued item transformation: " + material);
                }
                if (invalid) {
                    disabledCommissions.add(id);
                    findings.add("commission " + id + " disabled: missing active-evidence/limit/loss guard");
                }
            }
        }

        ConfigurationSection offers = economy.getConfigurationSection("black-market.offers");
        if (offers != null) {
            for (String id : offers.getKeys(false)) {
                String path = "black-market.offers." + id;
                String marker = economy.getString(path + ".marker", "none").toLowerCase(Locale.ROOT);
                Material material = Material.matchMaterial(economy.getString(path + ".material", ""));
                int amount = economy.getInt(path + ".amount", 0);
                String markerValue = economy.getString(path + ".marker-value", "").strip();
                BigDecimal base = decimal(economy, path + ".base-price", BigDecimal.ZERO);
                BigDecimal minimum = decimal(economy, path + ".sell-multiplier-minimum", BigDecimal.ZERO);
                BigDecimal maximum = decimal(economy, path + ".sell-multiplier-maximum", BigDecimal.ZERO);
                boolean invalid = material == null || isAir(material)
                        || amount <= 0 || material != null && amount > maximumStackSize.applyAsInt(material)
                        || !ALLOWED_MARKERS.contains(marker) || markerValue.isBlank() || markerValue.length() > 64
                        || base.signum() <= 0 || minimum.signum() <= 0 || maximum.compareTo(minimum) < 0
                        || economy.getInt(path + ".purchase-limit", 0) <= 0
                        || economy.getInt(path + ".buyback-limit", 0) <= 0;
                if (invalid) {
                    disabledOffers.add(id);
                    findings.add("black-market offer " + id + " disabled: invalid marker, price, or quota");
                }
            }
        }
        return new Report(Set.copyOf(disabledCommissions), Set.copyOf(disabledOffers), List.copyOf(findings),
                Set.copyOf(systemIssuedMaterials), Set.copyOf(systemIssuedClosure));
    }

    private static void collectMaterials(
            FileConfiguration config, String path, Set<Material> destination
    ) {
        ConfigurationSection products = config.getConfigurationSection(path);
        if (products == null) return;
        for (String id : products.getKeys(false)) {
            Material material = Material.matchMaterial(products.getString(id + ".material", ""));
            if (material != null && !isAir(material)) destination.add(material);
        }
    }

    static boolean matchesMerchantProvenance(String periodKey, String offerId, String actual) {
        return periodKey != null && offerId != null && actual != null
                && actual.equals(periodKey + ":" + offerId);
    }

    static Set<Material> reachable(Set<Material> starts, Map<Material, Set<Material>> edges) {
        Set<Material> reached = new LinkedHashSet<>(starts);
        ArrayDeque<Material> queue = new ArrayDeque<>(starts);
        while (!queue.isEmpty()) {
            Material source = queue.removeFirst();
            for (Material destination : edges.getOrDefault(source, Set.of())) {
                if (reached.add(destination)) queue.addLast(destination);
            }
        }
        return Set.copyOf(reached);
    }

    private static Map<Material, Set<Material>> recipeEdges(Iterator<Recipe> recipes) {
        Map<Material, Set<Material>> edges = new HashMap<>();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            Material result = recipe.getResult().getType();
            if (isAir(result)) continue;
            Collection<RecipeChoice> choices;
            if (recipe instanceof ShapedRecipe shaped) {
                choices = shaped.getChoiceMap().values();
            } else if (recipe instanceof ShapelessRecipe shapeless) {
                choices = shapeless.getChoiceList();
            } else if (recipe instanceof CookingRecipe<?> cooking) {
                choices = List.of(cooking.getInputChoice());
            } else if (recipe instanceof StonecuttingRecipe stonecutting) {
                choices = List.of(stonecutting.getInputChoice());
            } else {
                continue;
            }
            for (RecipeChoice choice : choices) {
                if (choice == null) continue;
                for (Material ingredient : choiceMaterials(choice)) {
                    edges.computeIfAbsent(ingredient, ignored -> new LinkedHashSet<>()).add(result);
                }
            }
        }
        return edges;
    }

    private static Set<Material> choiceMaterials(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.MaterialChoice materials) return Set.copyOf(materials.getChoices());
        if (choice instanceof RecipeChoice.ExactChoice exact) {
            Set<Material> result = new LinkedHashSet<>();
            for (ItemStack item : exact.getChoices()) result.add(item.getType());
            return result;
        }
        return Set.of();
    }

    private static BigDecimal decimal(FileConfiguration config, String path, BigDecimal fallback) {
        try {
            return new BigDecimal(config.getString(path, fallback.toPlainString()));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    record Report(
            Set<String> disabledCommissions,
            Set<String> disabledMerchantOffers,
            List<String> findings,
            Set<Material> systemIssuedMaterials,
            Set<Material> systemIssuedClosure
    ) {
        boolean commissionEnabled(String id) {
            return !disabledCommissions.contains(id);
        }

        boolean merchantOfferEnabled(String id) {
            return !disabledMerchantOffers.contains(id);
        }
    }
}
