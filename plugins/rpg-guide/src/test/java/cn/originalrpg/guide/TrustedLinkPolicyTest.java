package cn.originalrpg.guide;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedLinkPolicyTest {
    @Test
    void onlyExplicitHttpLinksWithHostsAreAccepted() {
        assertEquals("https://mc.sivitahub.com/map/",
                TrustedLinkPolicy.httpUrl("  https://mc.sivitahub.com/map/  ").orElseThrow());
        assertEquals("http://192.168.77.25:8081/",
                TrustedLinkPolicy.httpUrl("http://192.168.77.25:8081/").orElseThrow());
        assertTrue(TrustedLinkPolicy.httpUrl("javascript:alert(1)").isEmpty());
        assertTrue(TrustedLinkPolicy.httpUrl("file:///etc/passwd").isEmpty());
        assertTrue(TrustedLinkPolicy.httpUrl("https://user:secret@example.com/").isEmpty());
        assertTrue(TrustedLinkPolicy.httpUrl("not a url").isEmpty());
    }

    @Test
    void configuredLinksKeepOrderAndRemoveDuplicatesOrInvalidValues() {
        assertEquals(List.of("https://mc.sivitahub.com/map/", "http://192.168.77.25:8081/"),
                TrustedLinkPolicy.uniqueHttpUrls(
                        "https://mc.sivitahub.com/map/",
                        "javascript:alert(1)",
                        "https://mc.sivitahub.com/map/",
                        "http://192.168.77.25:8081/"));
    }
}
