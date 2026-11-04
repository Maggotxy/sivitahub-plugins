package cn.originalrpg.world;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.FieldJournalEntry;
import cn.originalrpg.api.model.FieldJournalRecord;
import cn.originalrpg.api.model.FieldJournalUpdate;
import cn.originalrpg.api.service.FieldJournalService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.ui.TrustedMenuHolder;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Logger;

/** Main-thread advancement adapter, recovery loop and read-only collection menus. */
final class FieldJournalController implements Listener, CommandExecutor, TabCompleter {
    private static final int[] ENTRY_SLOTS = {10, 11, 12, 14, 15, 16};
    private static final int PAGE_SIZE = 28;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    private final Plugin plugin;
    private final Server server;
    private final BukkitScheduler scheduler;
    private final MessageService messages;
    private final Logger logger;
    private final FieldJournalService service;
    private final FieldJournalCatalog catalog;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private Map<FieldJournalEntry, Advancement> available;
    private BukkitTask recoveryTask;
    private int recoveryCursor;
    private volatile boolean closed;

    FieldJournalController(Plugin plugin, MessageService messages, FieldJournalService service) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.scheduler = server.getScheduler();
        this.messages = messages;
        this.logger = plugin.getLogger();
        this.service = service;
        try (InputStream input = plugin.getResource("field-journal.yml")) {
            catalog = FieldJournalCatalog.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read the field journal catalog", exception);
        }
        validateRegistry();
    }

    void start() {
        long interval = Math.clamp(plugin.getConfig().getLong("field-journal.reconcile-interval-ticks", 200), 100, 12_000);
        recoveryTask = scheduler.runTaskTimer(plugin, this::recoverBatch, interval, interval);
    }

    void shutdown() {
        closed = true;
        if (recoveryTask != null) recoveryTask.cancel();
        for (Session session : List.copyOf(sessions.values())) closeVisibleMenu(session);
        sessions.clear();
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        Session session = new Session(player);
        sessions.put(player.getUniqueId(), session);
        reconcile(session, false);
    }

    @EventHandler
    public void onRevoked(AuthenticatedPlayerRevokedEvent event) {
        removeSession(event.player());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeSession(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Session session = session(event.getPlayer());
        if (session == null) return;
        FieldJournalEntry.fromAdvancementKey(event.getAdvancement().getKey().toString())
                .filter(available::containsKey).ifPresent(ignored -> reconcile(session, true));
    }

    @EventHandler
    public void onResourcesReloaded(ServerResourcesReloadedEvent event) {
        validateRegistry();
        for (Session session : List.copyOf(sessions.values())) reconcile(session, false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        Session session = session(player);
        if (session == null) {
            send(player, "journal.not-ready", NamedTextColor.YELLOW);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("help")) {
            send(player, "journal.help", NamedTextColor.AQUA);
            send(player, "journal.rule", NamedTextColor.GRAY);
            return true;
        }
        FieldJournalEntry detail = null;
        boolean status = args.length > 0 && args[0].equalsIgnoreCase("status");
        int page = 0;
        if (args.length > 0 && !status) {
            detail = FieldJournalEntry.fromId(args[0].toLowerCase(Locale.ROOT)).orElse(null);
            if (detail == null) {
                send(player, "journal.help", NamedTextColor.YELLOW);
                return true;
            }
            if (args.length > 1) {
                try {
                    page = Math.max(0, Integer.parseInt(args[1]) - 1);
                } catch (NumberFormatException exception) {
                    send(player, "journal.help", NamedTextColor.YELLOW);
                    return true;
                }
            }
        }
        session.request = new ScreenRequest(player.getOpenInventory().getTopInventory(), status, detail, page);
        reconcile(session, false);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> choices = new ArrayList<>(List.of("help", "status"));
        Arrays.stream(FieldJournalEntry.values()).map(FieldJournalEntry::id).forEach(choices::add);
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.startsWith(prefix)).toList();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof JournalMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = session(player);
        if (session == null || session.visibleMenu != menu || !menu.token.equals(session.token)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= menu.inventory.getSize()) return;
        onMain(() -> {
            if (!current(session) || session.visibleMenu != menu
                    || player.getOpenInventory().getTopInventory() != menu.inventory) return;
            if (menu.detail == null) {
                for (int index = 0; index < ENTRY_SLOTS.length; index++) {
                    if (slot == ENTRY_SLOTS[index]) {
                        showMenu(session, FieldJournalEntry.values()[index], 0);
                        return;
                    }
                }
                if (slot == 22) player.closeInventory();
                if (slot == 26) {
                    player.closeInventory();
                    player.performCommand("menu");
                }
            } else if (slot == 49) {
                showMenu(session, null, 0);
            } else if (slot == 45 && menu.page > 0) {
                showMenu(session, menu.detail, menu.page - 1);
            } else if (slot == 53 && (menu.page + 1) * PAGE_SIZE < observe(session, menu.detail).remaining().size()) {
                showMenu(session, menu.detail, menu.page + 1);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof JournalMenu) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) return;
        if (session.visibleMenu != null && session.visibleMenu.inventory == event.getInventory()) session.visibleMenu = null;
        if (session.request != null && session.request.expectedInventory == event.getInventory()) session.request = null;
    }

    private void validateRegistry() {
        available = catalog.resolve(server::getAdvancement, logger::severe);
    }

    private void recoverBatch() {
        List<Session> active = sessions.values().stream().filter(this::current).toList();
        if (active.isEmpty()) {
            recoveryCursor = 0;
            return;
        }
        int limit = Math.clamp(plugin.getConfig().getInt("field-journal.players-per-pass", 2), 1, 16);
        for (int index = 0; index < Math.min(limit, active.size()); index++) {
            reconcile(active.get(Math.floorMod(recoveryCursor++, active.size())), true);
        }
    }

    private void reconcile(Session session, boolean announce) {
        if (!current(session)) return;
        if (session.inFlight) {
            session.refreshAgain = true;
            session.announceAgain |= announce;
            return;
        }
        Set<FieldJournalEntry> completed = EnumSet.noneOf(FieldJournalEntry.class);
        for (FieldJournalEntry entry : available.keySet()) {
            if (observe(session, entry).completed()) completed.add(entry);
        }
        session.inFlight = true;
        // Everything crossing this boundary is immutable; the database worker
        // never sees a Player, Advancement, Inventory or live progress object.
        service.recordCompleted(session.playerId, Set.copyOf(completed)).whenComplete((update, error) ->
                onMain(() -> finishReconcile(session, announce, update, error)));
    }

    private void finishReconcile(Session session, boolean announce, FieldJournalUpdate update, Throwable error) {
        if (!current(session)) return;
        session.inFlight = false;
        if (error != null || update == null) {
            logger.warning("Field journal retry pending for " + session.playerId + ": "
                    + (error == null ? "missing database result" : error.getMessage()));
            if (session.request != null) send(session.player, "journal.retry", NamedTextColor.YELLOW);
        } else {
            // Merge immutable receipts so an older asynchronous read can never
            // erase a receipt observed by this connection in a newer callback.
            update.records().forEach(record -> session.records.putIfAbsent(record.entry(), record.recordedAt()));
            if (announce) {
                for (FieldJournalEntry entry : update.newlyRecorded()) {
                    session.player.sendMessage(Component.text(messages.text(session.player, "journal.discovered", Map.of(
                                    "entry", name(session.player, entry))), NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/journal " + entry.id())));
                }
            }
        }
        if (session.refreshAgain) {
            boolean again = session.announceAgain;
            session.refreshAgain = false;
            session.announceAgain = false;
            reconcile(session, again);
            return;
        }
        ScreenRequest request = session.request;
        session.request = null;
        if (request != null && session.player.getOpenInventory().getTopInventory() == request.expectedInventory) {
            if (request.status) showStatus(session);
            else showMenu(session, request.detail, request.page);
        } else if (session.visibleMenu != null
                && session.player.getOpenInventory().getTopInventory() == session.visibleMenu.inventory) {
            render(session, session.visibleMenu);
        }
    }

    private FieldJournalProgress observe(Session session, FieldJournalEntry entry) {
        Advancement advancement = available.get(entry);
        Instant recorded = session.records.get(entry);
        if (advancement == null) return FieldJournalProgress.unavailable(recorded);
        AdvancementProgress progress = session.player.getAdvancementProgress(advancement);
        return FieldJournalProgress.observe(advancement.getCriteria(), progress.getAwardedCriteria(), progress.isDone(), recorded);
    }

    private void showStatus(Session session) {
        send(session.player, "journal.title", NamedTextColor.GOLD);
        for (FieldJournalEntry entry : FieldJournalEntry.values()) {
            FieldJournalProgress progress = observe(session, entry);
            session.player.sendMessage(Component.text(name(session.player, entry) + ": ", NamedTextColor.AQUA)
                    .append(text(session.player, progress.stateKey(), NamedTextColor.WHITE))
                    .append(Component.text(progress.available()
                            ? " (" + progress.awardedCriteria() + "/" + progress.totalCriteria() + ")" : "", NamedTextColor.GRAY))
                    .clickEvent(ClickEvent.runCommand("/journal " + entry.id())));
        }
        send(session.player, "journal.rule", NamedTextColor.GRAY);
    }

    private void showMenu(Session session, FieldJournalEntry detail, int requestedPage) {
        int maximumPage = detail == null ? 0 : Math.max(0, (observe(session, detail).remaining().size() - 1) / PAGE_SIZE);
        JournalMenu menu = new JournalMenu(session.token, detail, Math.clamp(requestedPage, 0, maximumPage));
        menu.inventory = server.createInventory(menu, detail == null ? 27 : 54,
                text(session.player, "journal.title", NamedTextColor.DARK_GREEN));
        render(session, menu);
        session.player.openInventory(menu.inventory);
        session.visibleMenu = menu;
    }

    private void render(Session session, JournalMenu menu) {
        Player player = session.player;
        menu.inventory.clear();
        if (menu.detail == null) {
            menu.inventory.setItem(4, item(Material.WRITABLE_BOOK, text(player, "journal.title", NamedTextColor.GOLD),
                    List.of(text(player, "journal.intro", NamedTextColor.GRAY),
                            Component.text(messages.text(player, "journal.collection-count", Map.of(
                                    "count", session.records.size(), "total", FieldJournalEntry.values().length)), NamedTextColor.AQUA),
                            text(player, "journal.rule", NamedTextColor.GRAY)), false));
            for (int index = 0; index < ENTRY_SLOTS.length; index++) {
                FieldJournalEntry entry = FieldJournalEntry.values()[index];
                menu.inventory.setItem(ENTRY_SLOTS[index], entryItem(session, entry));
            }
            menu.inventory.setItem(22, item(Material.BARRIER, text(player, "journal.close", NamedTextColor.YELLOW), List.of(), false));
            menu.inventory.setItem(26, item(Material.COMPASS, text(player, "common.back", NamedTextColor.AQUA), List.of(), false));
            return;
        }
        FieldJournalProgress progress = observe(session, menu.detail);
        menu.inventory.setItem(4, entryItem(session, menu.detail));
        if (progress.remaining().isEmpty()) {
            menu.inventory.setItem(22, item(progress.recordedAt() != null ? Material.EMERALD : Material.PAPER,
                    text(player, progress.stateKey(), NamedTextColor.GREEN),
                    List.of(text(player, "journal.rule", NamedTextColor.GRAY)), progress.recordedAt() != null));
        } else {
            int start = menu.page * PAGE_SIZE;
            List<String> page = progress.remaining().subList(Math.min(start, progress.remaining().size()),
                    Math.min(start + PAGE_SIZE, progress.remaining().size()));
            for (int index = 0; index < page.size(); index++) {
                String criterion = page.get(index);
                int slot = 10 + index / 7 * 9 + index % 7;
                menu.inventory.setItem(slot, item(criterionIcon(menu.detail, criterion),
                        criterionName(menu.detail, criterion),
                        List.of(text(player, "journal.remaining-criterion", NamedTextColor.YELLOW),
                                text(player, "journal.entries." + menu.detail.id() + ".action", NamedTextColor.GRAY)), false));
            }
        }
        if (menu.page > 0) menu.inventory.setItem(45, item(Material.ARROW,
                text(player, "journal.previous", NamedTextColor.YELLOW), List.of(), false));
        menu.inventory.setItem(49, item(Material.BOOK, text(player, "journal.back", NamedTextColor.AQUA),
                List.of(Component.text(messages.text(player, "journal.page", Map.of(
                        "page", menu.page + 1, "pages", Math.max(1, (progress.remaining().size() + PAGE_SIZE - 1) / PAGE_SIZE))),
                        NamedTextColor.GRAY)), false));
        if ((menu.page + 1) * PAGE_SIZE < progress.remaining().size()) menu.inventory.setItem(53,
                item(Material.ARROW, text(player, "journal.next", NamedTextColor.YELLOW), List.of(), false));
    }

    private ItemStack entryItem(Session session, FieldJournalEntry entry) {
        Player player = session.player;
        FieldJournalProgress progress = observe(session, entry);
        List<Component> lore = new ArrayList<>();
        for (String line : messages.lines(player, "journal.entries." + entry.id() + ".description")) {
            lore.add(Component.text(line, NamedTextColor.GRAY));
        }
        lore.add(text(player, progress.stateKey(), progress.recordedAt() == null ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
        if (progress.available()) lore.add(Component.text(messages.text(player, "journal.criteria-progress", Map.of(
                "done", progress.awardedCriteria(), "total", progress.totalCriteria())), NamedTextColor.AQUA));
        if (progress.recordedAt() != null) lore.add(Component.text(messages.text(player, "journal.recorded-date", Map.of(
                "date", DATE.format(progress.recordedAt()))), NamedTextColor.GRAY));
        lore.add(text(player, "journal.click-details", NamedTextColor.YELLOW));
        return item(icon(entry), Component.text(name(player, entry), NamedTextColor.GOLD), lore, progress.recordedAt() != null);
    }

    private static ItemStack item(Material material, Component title, List<Component> lore, boolean glint) {
        ItemStack result = new ItemStack(material);
        var meta = result.getItemMeta();
        meta.displayName(title);
        meta.lore(lore);
        if (glint) meta.setEnchantmentGlintOverride(true);
        result.setItemMeta(meta);
        return result;
    }

    private static Material icon(FieldJournalEntry entry) {
        return switch (entry) {
            case OVERWORLD_EXPLORER -> Material.GRASS_BLOCK;
            case NETHER_EXPLORER -> Material.NETHERRACK;
            case NATURALIST -> Material.WHEAT;
            case FORAGER -> Material.APPLE;
            case VILLAGE_DEFENDER -> Material.SHIELD;
            case ARMOR_ARTISAN -> Material.SMITHING_TABLE;
        };
    }

    static String criterionPath(FieldJournalEntry entry, String criterion) {
        if (entry == FieldJournalEntry.ARMOR_ARTISAN) {
            return criterion.replace("armor_trimmed_minecraft:", "").replace("_smithing_trim", "");
        }
        return criterion.replace("minecraft:", "");
    }

    static Component criterionName(FieldJournalEntry entry, String criterion) {
        String path = criterionPath(entry, criterion);
        String translation = switch (entry) {
            case OVERWORLD_EXPLORER, NETHER_EXPLORER -> "biome.minecraft." + path;
            case NATURALIST -> "entity.minecraft." + path;
            case FORAGER, ARMOR_ARTISAN -> "item.minecraft." + path;
            case VILLAGE_DEFENDER -> "advancements.adventure.hero_of_the_village.title";
        };
        return Component.translatable(translation, NamedTextColor.WHITE);
    }

    private static Material criterionIcon(FieldJournalEntry entry, String criterion) {
        String path = criterionPath(entry, criterion);
        if (entry == FieldJournalEntry.NATURALIST) path += "_spawn_egg";
        if (entry == FieldJournalEntry.NATURALIST || entry == FieldJournalEntry.FORAGER || entry == FieldJournalEntry.ARMOR_ARTISAN) {
            Material material = Material.matchMaterial(path.toUpperCase(Locale.ROOT));
            if (material != null && material.isItem()) return material;
        }
        return icon(entry);
    }

    private String name(Player player, FieldJournalEntry entry) {
        return messages.text(player, "journal.entries." + entry.id() + ".name");
    }

    private Component text(Player player, String key, NamedTextColor color) {
        return Component.text(messages.text(player, key), color);
    }

    private void send(Player player, String key, NamedTextColor color) {
        player.sendMessage(text(player, key, color));
    }

    private Session session(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.player == player && current(session) ? session : null;
    }

    private boolean current(Session session) {
        return !closed && sessions.get(session.playerId) == session && session.player.isOnline()
                && server.getPlayer(session.playerId) == session.player;
    }

    private void removeSession(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) return;
        sessions.remove(session.playerId, session);
        closeVisibleMenu(session);
    }

    private void closeVisibleMenu(Session session) {
        if (session.visibleMenu != null && session.player.getOpenInventory().getTopInventory() == session.visibleMenu.inventory) {
            session.player.closeInventory();
        }
        session.request = null;
        session.visibleMenu = null;
    }

    private void onMain(Runnable action) {
        if (closed) return;
        try {
            scheduler.runTask(plugin, () -> { if (!closed) action.run(); });
        } catch (IllegalPluginAccessException | RejectedExecutionException exception) {
            if (!closed) logger.warning("Field journal callback could not be scheduled: " + exception.getMessage());
        }
    }

    private static final class Session {
        private final Player player;
        private final UUID playerId;
        private final UUID token = UUID.randomUUID();
        private final Map<FieldJournalEntry, Instant> records = new EnumMap<>(FieldJournalEntry.class);
        private boolean inFlight;
        private boolean refreshAgain;
        private boolean announceAgain;
        private ScreenRequest request;
        private JournalMenu visibleMenu;

        private Session(Player player) {
            this.player = player;
            this.playerId = player.getUniqueId();
        }
    }

    private record ScreenRequest(Inventory expectedInventory, boolean status, FieldJournalEntry detail, int page) {
    }

    private static final class JournalMenu implements TrustedMenuHolder {
        private final UUID token;
        private final FieldJournalEntry detail;
        private final int page;
        private Inventory inventory;

        private JournalMenu(UUID token, FieldJournalEntry detail, int page) {
            this.token = token;
            this.detail = detail;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
