package cn.originalrpg.admin;

import cn.originalrpg.api.service.MechanismControlService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Console-only, bounded operator entry; Bukkit callbacks always use the supplied dispatcher. */
final class MechanismControlCommand implements AutoCloseable {
    private static final String USAGE = "/rpgadmin mechanism list | enable/disable <mechanism-id> CONFIRM <reason (1-56 chars)>";
    private final MechanismControlService service;
    private final Consumer<Runnable> dispatch;
    private final Logger logger;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed;

    MechanismControlCommand(MechanismControlService service, Consumer<Runnable> dispatch, Logger logger) {
        this.service = Objects.requireNonNull(service);
        this.dispatch = Objects.requireNonNull(dispatch);
        this.logger = Objects.requireNonNull(logger);
    }

    boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || !sender.hasPermission("rpg.admin")) {
            message(sender, "Mechanism control requires the authorized server console.", NamedTextColor.RED);
            return true;
        }
        if (closed) return true;
        boolean list = args.length == 2 && args[1].equalsIgnoreCase("list");
        boolean change = args.length >= 5 && (args[1].equalsIgnoreCase("enable") || args[1].equalsIgnoreCase("disable"))
                && args[3].equals("CONFIRM");
        String reason = change ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)).trim() : "";
        if (!list && (!change || reason.isBlank() || reason.length() > 56
                || reason.codePoints().anyMatch(Character::isISOControl))) {
            message(sender, USAGE, NamedTextColor.YELLOW);
            return true;
        }
        if (!busy.compareAndSet(false, true)) {
            message(sender, "A mechanism operation is pending; wait for its result.", NamedTextColor.YELLOW);
            return true;
        }
        try {
            if (list) {
                complete(sender, service.switches(), rows -> {
                    message(sender, "Durable switches; check counters below are local to this Paper process.", NamedTextColor.GOLD);
                    rows.forEach(row -> message(sender, row.id() + "=" + row.enabled()
                            + " allowedChecks=" + row.allowedChecks() + " deniedChecks=" + row.deniedChecks(), NamedTextColor.AQUA));
                });
            } else {
                boolean enabled = args[1].equalsIgnoreCase("enable");
                String id = args[2];
                complete(sender, service.setEnabled(id, enabled, "console:" + reason), row -> {
                    logger.info("Mechanism control committed id=" + row.id() + " enabled=" + row.enabled() + " reason=" + reason);
                    message(sender, row.id() + "=" + row.enabled() + ". New admission follows the switch; accepted operations/refunds retain their recovery path.", NamedTextColor.GREEN);
                });
            }
        } catch (RuntimeException error) {
            busy.set(false);
            message(sender, "Mechanism operation was not confirmed. Read current state before retrying.", NamedTextColor.RED);
        }
        return true;
    }

    private <T> void complete(CommandSender sender, CompletionStage<T> pending, Consumer<T> success) {
        pending.whenComplete((result, error) -> {
            if (closed) { busy.set(false); return; }
            try {
                dispatch.accept(() -> {
                    busy.set(false);
                    if (closed) return;
                    if (error != null) {
                        message(sender, "Mechanism operation was not confirmed. Read current state before retrying.", NamedTextColor.RED);
                    } else success.accept(result);
                });
            } catch (RuntimeException stopped) { busy.set(false); }
        });
    }

    static List<String> complete(String[] args) {
        if (args.length == 2) return List.of("list", "enable", "disable").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 4 && (args[1].equalsIgnoreCase("enable") || args[1].equalsIgnoreCase("disable")))
            return "CONFIRM".startsWith(args[3]) ? List.of("CONFIRM") : List.of();
        return List.of();
    }

    private static void message(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
    }

    @Override public void close() { closed = true; busy.set(false); }
}
