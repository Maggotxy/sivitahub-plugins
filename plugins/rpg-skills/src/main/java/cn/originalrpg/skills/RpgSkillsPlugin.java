package cn.originalrpg.skills;

import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.SkillService;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.ClassBranchService;
import cn.originalrpg.api.service.SkillHudService;
import cn.originalrpg.api.service.SkillDamageService;
import cn.originalrpg.api.model.EquipmentTraitCatalog;
import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.rules.StarterBuildRules;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Objects;

public final class RpgSkillsPlugin extends JavaPlugin {
    private SkillsCommand command;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        validateCatalog();
        validateHiddenRouteReachability();
        ProfileService profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        SkillService skillService = Objects.requireNonNull(
                getServer().getServicesManager().load(SkillService.class), "SkillService");
        MessageService messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        EquipmentService equipment = Objects.requireNonNull(
                getServer().getServicesManager().load(EquipmentService.class), "EquipmentService");
        ClassBranchService branches = Objects.requireNonNull(
                getServer().getServicesManager().load(ClassBranchService.class), "ClassBranchService");
        SkillDamageService skillDamage = Objects.requireNonNull(
                getServer().getServicesManager().load(SkillDamageService.class), "SkillDamageService");
        PluginCommand skills = Objects.requireNonNull(getCommand("skills"), "skills command");
        command = new SkillsCommand(
                this, profiles, skillService, branches, equipment, messages, skillDamage);
        getServer().getServicesManager().register(
                SkillHudService.class, command, this, ServicePriority.Normal);
        skills.setExecutor(command);
        skills.setTabCompleter(command);
        getServer().getPluginManager().registerEvents(command, this);
        getServer().getPluginManager().registerEvents(new SkillBookListener(this, profiles, messages), this);
        getServer().getScheduler().runTaskTimer(this, command::regenerateMana, 40L, 40L);
        // Cooldown labels change at one-second precision. Sending faster creates packets
        // without producing any visible information for the player.
        long hudTicks = Math.clamp(getConfig().getLong("hud.update-ticks", 20L), 20L, 40L);
        getServer().getScheduler().runTaskTimer(this, command::updateHud, hudTicks, hudTicks);
    }

    @Override
    public void onDisable() {
        if (command != null) command.shutdown();
        getServer().getServicesManager().unregisterAll(this);
    }

    private void validateCatalog() {
        validateSkillLeveling();
        ConfigurationSection configured = Objects.requireNonNull(
                getConfig().getConfigurationSection("skills"), "skills configuration");
        if (!configured.getKeys(false).equals(SkillCatalog.IMPLEMENTED)) {
            java.util.Set<String> missingEffects = new java.util.TreeSet<>(configured.getKeys(false));
            missingEffects.removeAll(SkillCatalog.IMPLEMENTED);
            java.util.Set<String> missingDefinitions = new java.util.TreeSet<>(SkillCatalog.IMPLEMENTED);
            missingDefinitions.removeAll(configured.getKeys(false));
            throw new IllegalStateException("Skill catalog/effect mismatch; no-effect=" + missingEffects
                    + " no-definition=" + missingDefinitions);
        }
        java.util.Map<String, Integer> classCounts = new java.util.HashMap<>();
        java.util.Map<String, Integer> starterCounts = new java.util.HashMap<>();
        for (String id : configured.getKeys(false)) {
            String owner = configured.getString(id + ".class", "");
            if (!java.util.Set.of("HEALER", "WARRIOR", "ASSASSIN", "MAGE", "RANGER", "FREEFORM")
                    .contains(owner)) {
                throw new IllegalStateException("Skill " + id + " has invalid class " + owner);
            }
            classCounts.merge(owner, 1, Integer::sum);
            int mana = configured.getInt(id + ".mana-cost", -1);
            long cooldown = configured.getLong(id + ".cooldown-seconds", -1L);
            int cost = configured.getInt(id + ".skill-points", -1);
            if (mana < 1 || cooldown < 1L || cost < 1) {
                throw new IllegalStateException("Skill " + id + " has invalid cost/cooldown");
            }
            boolean starter = configured.getStringList(id + ".prerequisites").isEmpty()
                    && configured.getInt(id + ".minimum-level", 1) <= 1
                    && configured.getInt(id + ".minimum-ascension", 0) <= 0
                    && configured.getString(id + ".branch", "").isBlank();
            if (starter) {
                ConfigurationSection requirements = configured.getConfigurationSection(id + ".requirements");
                int attributeBudget = requirements == null ? 0 : requirements.getKeys(false).stream()
                        .mapToInt(requirements::getInt).sum();
                if (attributeBudget != 5 || cost != 1) {
                    throw new IllegalStateException("Starter skill " + id
                            + " must fit exactly five starting attributes and one skill point");
                }
                Archetype archetype = Archetype.valueOf(owner);
                java.util.Map<CoreAttribute, Integer> configuredRequirements =
                        new java.util.EnumMap<>(CoreAttribute.class);
                if (requirements != null) requirements.getKeys(false).forEach(key ->
                        configuredRequirements.put(CoreAttribute.valueOf(key), requirements.getInt(key)));
                if (!StarterBuildRules.requirements(archetype).equals(configuredRequirements)) {
                    throw new IllegalStateException("Starter build/skill gate mismatch for " + owner
                            + ": " + configuredRequirements);
                }
                starterCounts.merge(owner, 1, Integer::sum);
            }
            for (String prerequisite : configured.getStringList(id + ".prerequisites")) {
                if (!configured.contains(prerequisite)) {
                    throw new IllegalStateException("Skill " + id + " references missing prerequisite " + prerequisite);
                }
                if (!owner.equals(configured.getString(prerequisite + ".class"))) {
                    throw new IllegalStateException("Skill " + id + " has a cross-class prerequisite " + prerequisite);
                }
            }
        }
        for (String owner : java.util.Set.of("HEALER", "WARRIOR", "ASSASSIN", "MAGE", "RANGER", "FREEFORM")) {
            if (classCounts.getOrDefault(owner, 0) != 7) {
                throw new IllegalStateException("Playable class " + owner + " must expose seven concrete skills");
            }
            if (starterCounts.getOrDefault(owner, 0) != 1) {
                throw new IllegalStateException("Playable class " + owner
                        + " must expose exactly one five-attribute starter skill");
            }
        }
        java.util.Set<String> borrowedSkills = new java.util.HashSet<>(
                EquipmentTraitCatalog.borrowedSkills().values());
        if (borrowedSkills.size() != 6 || !configured.getKeys(false).containsAll(borrowedSkills)) {
            throw new IllegalStateException("Every base class must expose one concrete equipment-borrowed skill: "
                    + borrowedSkills);
        }
        for (String id : borrowedSkills) {
            boolean starter = configured.getStringList(id + ".prerequisites").isEmpty()
                    && configured.getInt(id + ".minimum-level", 1) <= 1
                    && configured.getInt(id + ".minimum-ascension", 0) <= 0
                    && configured.getString(id + ".branch", "").isBlank();
            if (!starter) throw new IllegalStateException(
                    "Equipment borrowing is restricted to bounded base skills: " + id);
        }

        org.bukkit.plugin.Plugin classPlugin = getServer().getPluginManager().getPlugin("RpgClasses");
        if (!(classPlugin instanceof JavaPlugin classes)) {
            throw new IllegalStateException("RpgClasses must be enabled before validating skill branches");
        }
        ConfigurationSection branchRoot = Objects.requireNonNull(
                classes.getConfig().getConfigurationSection("branches"), "class branches configuration");
        java.util.Set<String> enabledBranches = new java.util.HashSet<>();
        java.util.Map<String, String> branchOwners = new java.util.HashMap<>();
        java.util.Map<String, Integer> branchStages = new java.util.HashMap<>();
        for (String owner : branchRoot.getKeys(false)) {
            ConfigurationSection ownerBranches = branchRoot.getConfigurationSection(owner);
            if (ownerBranches == null) continue;
            for (String branch : ownerBranches.getKeys(false)) {
                int stage = Math.clamp(ownerBranches.getInt(branch + ".stage", 1), 1, 3);
                if (ownerBranches.getBoolean(branch + ".enabled", stage == 1)) {
                    enabledBranches.add(branch);
                    if (branchOwners.putIfAbsent(branch, owner.toUpperCase(java.util.Locale.ROOT)) != null) {
                        throw new IllegalStateException("Class branch IDs must be globally unique: " + branch);
                    }
                    branchStages.put(branch, stage);
                }
            }
        }
        java.util.Set<String> implementedBranches = new java.util.HashSet<>();
        for (String id : configured.getKeys(false)) {
            String branch = configured.getString(id + ".branch", "");
            if (!branch.isBlank()) {
                String skillOwner = configured.getString(id + ".class", "");
                implementedBranches.add(branch);
                if (!enabledBranches.contains(branch)) {
                    throw new IllegalStateException("Skill " + id + " targets a missing or disabled branch " + branch);
                }
                if (!skillOwner.equals(branchOwners.get(branch))) {
                    throw new IllegalStateException("Skill " + id + " targets a branch owned by another class: " + branch);
                }
                int minimumAscension = configured.getInt(id + ".minimum-ascension", 0);
                if (minimumAscension != branchStages.get(branch)) {
                    throw new IllegalStateException("Skill " + id + " must unlock at its branch ascension "
                            + branchStages.get(branch) + ", not " + minimumAscension);
                }
            }
        }
        if (!implementedBranches.containsAll(enabledBranches)) {
            java.util.Set<String> contentless = new java.util.TreeSet<>(enabledBranches);
            contentless.removeAll(implementedBranches);
            throw new IllegalStateException("Enabled class branches have no concrete skills: " + contentless);
        }

        ConfigurationSection books = Objects.requireNonNull(
                getConfig().getConfigurationSection("skill-books"), "skill-books configuration");
        if (!books.getKeys(false).equals(SkillBookCatalog.IMPLEMENTED)) {
            throw new IllegalStateException("Skill-book IDs without concrete settlement are forbidden: "
                    + books.getKeys(false));
        }
        for (String id : books.getKeys(false)) {
            if (!SkillBookCatalog.SKILL_POINTS.equals(
                    books.getString(id + ".effect", SkillBookCatalog.SKILL_POINTS))) {
                throw new IllegalStateException("Skill book " + id + " has no implemented settlement effect");
            }
            int minimum = books.getInt(id + ".points.minimum", 0);
            int maximum = books.getInt(id + ".points.maximum", 0);
            if (minimum < 1 || maximum < minimum) {
                throw new IllegalStateException("Skill book " + id + " has an invalid point range");
            }
        }
    }

    /**
     * G-106 回归门：隐藏路线的任一前置（技能 / 隐藏副本 / 门票来源 / 技能书）
     * 若比路线自身开放更晚，路线永久不可达 —— 具名报出。
     *
     * <p>默认只告警；{@code hidden-route-reachability.fail-on-contradiction=true} 时
     * 升级为启动失败（fail-fast）。技能与技能书门来自本插件 config.yml，
     * 隐藏副本与门票来源门来自 RpgDungeons（softdepend，未安装时跳过并记录，不阻塞启动）。
     */
    private void validateHiddenRouteReachability() {
        if (!getConfig().getBoolean("hidden-route-reachability.enabled", true)) return;
        ConfigurationSection skills = getConfig().getConfigurationSection("skills");
        if (skills == null) return;
        org.bukkit.plugin.Plugin dungeonPlugin = getServer().getPluginManager().getPlugin("RpgDungeons");
        ConfigurationSection dungeons = dungeonPlugin instanceof JavaPlugin dungeonsPlugin
                ? dungeonsPlugin.getConfig().getConfigurationSection("dungeons")
                : null;
        // 分支选择门（RpgClasses 硬依赖）：技能门必须与它相等，但它是另一份配置，
        // 因此一并读入，避免「技能门调低、分支 stage 仍是高转」这种漏检形态。
        org.bukkit.plugin.Plugin classPlugin = getServer().getPluginManager().getPlugin("RpgClasses");
        ConfigurationSection branches = classPlugin instanceof JavaPlugin classesPlugin
                ? classesPlugin.getConfig().getConfigurationSection("branches")
                : null;
        HiddenRouteReachability.Report report = HiddenRouteReachability.inspect(skills, dungeons, branches);
        if (!report.skipped().isEmpty()) {
            getLogger().warning("隐藏路线可达性自检跳过部分前置：" + report.skipped());
        }
        if (report.reachable()) return;
        String description = report.describe().trim();
        // 默认只告警：shipped 配置存在待人类裁决的 G-106 矛盾，置 fail-on-contradiction
        // 为 true 后立即升级为启动失败（fail-fast），防止新的同类矛盾静默上线。
        if (getConfig().getBoolean("hidden-route-reachability.fail-on-contradiction", false)) {
            throw new IllegalStateException(description);
        }
        getLogger().warning("隐藏路线可达性自检发现矛盾（fail-on-contradiction=false，仅告警）：\n" + description);
    }

    private void validateSkillLeveling() {
        java.util.List<Integer> caps = getConfig().getIntegerList(
                "skill-leveling.maximum-level-by-ascension");
        if (caps.size() != SkillLevelRules.ASCENSION_STAGES) {
            throw new IllegalStateException("Skill leveling must define one cap for ascensions 0 through 3");
        }
        for (int ascension = 0; ascension < caps.size(); ascension++) {
            if (caps.get(ascension) != ascension + 2) {
                throw new IllegalStateException(
                        "Each ascension must open exactly one skill level; expected " + (ascension + 2)
                                + " at ascension " + ascension);
            }
        }
        java.util.List<Integer> costs = getConfig().getIntegerList(
                "skill-leveling.upgrade-point-costs");
        if (costs.size() != SkillLevelRules.absoluteMaximumLevel(caps) - 1
                || costs.stream().anyMatch(cost -> cost == null || cost < 1 || cost > 1000)) {
            throw new IllegalStateException("Skill leveling must define one bounded point cost per upgrade");
        }
        double reduction = getConfig().getDouble(
                "skill-leveling.cooldown-reduction-per-level", Double.NaN);
        double floor = getConfig().getDouble(
                "skill-leveling.minimum-cooldown-multiplier", Double.NaN);
        double finalMultiplier = 1.0 - (SkillLevelRules.absoluteMaximumLevel(caps) - 1) * reduction;
        if (!Double.isFinite(reduction) || reduction <= 0.0 || reduction > 0.10
                || !Double.isFinite(floor) || floor < 0.50 || floor > finalMultiplier + 1.0E-9) {
            throw new IllegalStateException(
                    "Skill-level cooldown growth must improve every level and remain bounded");
        }
    }
}
