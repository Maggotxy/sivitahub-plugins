package cn.originalrpg.community;

import cn.originalrpg.api.model.CommunityProfile;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.service.CommunityService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Issues a short-lived, signed browser link without copying the AuthMe password anywhere. */
final class AccountCommand implements CommandExecutor, TabCompleter {
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RpgCommunityPlugin plugin;
    private final CommunityService communities;
    private final ProfileService profiles;
    private final JourneyService journeys;
    private final MessageService messages;
    private final Map<UUID, IssueLease> issuedAt = new ConcurrentHashMap<>();
    private final byte[] signingKey;
    private final String publicUrl;

    AccountCommand(
            RpgCommunityPlugin plugin,
            CommunityService communities,
            ProfileService profiles,
            JourneyService journeys,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.communities = communities;
        this.profiles = profiles;
        this.journeys = journeys;
        this.messages = messages;
        this.publicUrl = validatePublicUrl(plugin.getConfig().getString(
                "account-link.public-url", "https://mc.sivitahub.com/account"));
        this.signingKey = loadOrCreateKey(plugin.getConfig().getString(
                "account-link.secret-file", "secrets/account-link.key"));
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (args.length > 1 || args.length == 1 && !args[0].equalsIgnoreCase("link")) {
            help(player);
            return true;
        }
        issue(player);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("link", "help").stream().filter(option -> option.startsWith(prefix)).toList();
    }

    void clear() {
        issuedAt.clear();
    }

    void clearPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        IssueLease lease = issuedAt.get(playerId);
        if (lease != null && lease.player() == player) issuedAt.remove(playerId, lease);
    }

    private void issue(Player player) {
        if (!plugin.isAuthenticated(player)) return;
        UUID sessionToken = plugin.sessionToken(player);
        if (sessionToken == null) return;
        long now = Instant.now().getEpochSecond();
        long cooldown = Math.clamp(plugin.getConfig().getLong("account-link.cooldown-seconds", 30L), 10L, 300L);
        IssueLease previous = issuedAt.get(player.getUniqueId());
        if (previous != null && now - previous.issuedAt() < cooldown) {
            player.sendMessage(Component.text(messages.text(player, "account.cooldown",
                    Map.of("seconds", cooldown - (now - previous.issuedAt()))), NamedTextColor.YELLOW));
            return;
        }
        IssueLease issue = new IssueLease(player, sessionToken, now);
        issuedAt.put(player.getUniqueId(), issue);
        boolean journeyComplete = journeys.progress(player.getUniqueId()).complete();
        profiles.get(player.getUniqueId())
                .thenCombine(communities.profile(player.getUniqueId(), plugin.periodKey(), plugin.projectGoal()),
                        (profile, community) -> new LinkSnapshot(profile, community, journeyComplete,
                                plugin.playMinutes(player)))
                .whenComplete((snapshot, error) -> plugin.onMain(() -> {
                    if (!plugin.isAuthenticated(player, sessionToken)) return;
                    if (error != null) {
                        issuedAt.remove(player.getUniqueId(), issue);
                        player.sendMessage(Component.text(messages.text(player, "account.error"), NamedTextColor.RED));
                        plugin.getLogger().warning("Account link snapshot failed for " + player.getUniqueId()
                                + ": " + error.getMessage());
                        return;
                    }
                    try {
                        String token = token(player, snapshot, now);
                        String link = publicUrl + "#link=" + token;
                        player.sendMessage(Component.text(messages.text(player, "account.ready"), NamedTextColor.GREEN));
                        player.sendMessage(Component.text(messages.text(player, "account.open"), NamedTextColor.AQUA)
                                .decorate(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl(link))
                                .hoverEvent(HoverEvent.showText(Component.text(
                                        messages.text(player, "account.open-hover"), NamedTextColor.GRAY))));
                        player.sendMessage(Component.text(messages.text(player, "account.security"), NamedTextColor.DARK_GRAY));
                    } catch (RuntimeException exception) {
                        issuedAt.remove(player.getUniqueId(), issue);
                        player.sendMessage(Component.text(messages.text(player, "account.error"), NamedTextColor.RED));
                        plugin.getLogger().warning("Account link signing failed: " + exception.getMessage());
                    }
                }));
    }

    private String token(Player player, LinkSnapshot snapshot, long issued) {
        long expiry = issued + Math.clamp(plugin.getConfig().getLong(
                "account-link.token-expiry-seconds", 300L), 60L, 600L);
        byte[] nonce = new byte[18];
        RANDOM.nextBytes(nonce);
        String payload = String.join("|",
                player.getUniqueId().toString(),
                player.getName(),
                Long.toString(issued),
                Long.toString(expiry),
                BASE64.encodeToString(nonce),
                snapshot.community().inviteCode(),
                Integer.toString(snapshot.profile().effectiveLevel()),
                Long.toString(snapshot.playMinutes()),
                snapshot.journeyComplete() ? "1" : "0",
                snapshot.community().mentorVerified() ? "1" : "0",
                Integer.toString(snapshot.community().verifiedMentees()));
        String body = "v1." + BASE64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + BASE64.encodeToString(hmac(body));
    }

    private byte[] hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private void help(Player player) {
        for (String line : messages.lines(player, "account.help")) {
            player.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
    }

    private String validatePublicUrl(String configured) {
        try {
            URI uri = URI.create(configured.strip());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getFragment() != null) {
                throw new IllegalArgumentException("account link URL must be an absolute HTTP(S) URL without fragment");
            }
            return configured.strip().replaceAll("/+$", "");
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid account-link.public-url", exception);
        }
    }

    private byte[] loadOrCreateKey(String configured) {
        Path root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path path = root.resolve(configured).normalize();
        if (!path.startsWith(root)) throw new IllegalStateException("Account-link secret must stay inside runtime");
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) createKey(path);
            String encoded = Files.readString(path, StandardCharsets.US_ASCII).strip();
            byte[] decoded = BASE64_DECODER.decode(encoded);
            if (decoded.length < 32) throw new IllegalStateException("Account-link key is shorter than 256 bits");
            securePermissions(path);
            return decoded;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not load account-link signing key", exception);
        }
    }

    private void createKey(Path path) throws IOException {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        Path temporary = Files.createTempFile(path.getParent(), ".account-link-", ".tmp");
        try {
            Files.writeString(temporary, BASE64.encodeToString(raw) + System.lineSeparator(),
                    StandardCharsets.US_ASCII);
            securePermissions(temporary);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path);
            } catch (FileAlreadyExistsException exception) {
                Files.deleteIfExists(temporary);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void securePermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX development filesystems rely on their directory ACLs.
        }
    }

    private record LinkSnapshot(
            PlayerProfile profile,
            CommunityProfile community,
            boolean journeyComplete,
            long playMinutes
    ) {
    }

    private record IssueLease(Player player, UUID sessionToken, long issuedAt) {
    }
}
