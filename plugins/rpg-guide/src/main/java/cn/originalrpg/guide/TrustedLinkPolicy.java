package cn.originalrpg.guide;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Restricts menu-owned external links to explicit, operator-configured HTTP(S) URLs. */
final class TrustedLinkPolicy {
    private TrustedLinkPolicy() {
    }

    static Optional<String> httpUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) return Optional.empty();
        try {
            URI uri = new URI(candidate.strip()).normalize();
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null) return Optional.empty();
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
                return Optional.empty();
            }
            return Optional.of(uri.toASCIIString());
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }
    }

    static List<String> uniqueHttpUrls(String... candidates) {
        Set<String> unique = new LinkedHashSet<>();
        for (String candidate : candidates) httpUrl(candidate).ifPresent(unique::add);
        return List.copyOf(new ArrayList<>(unique));
    }
}
