package cn.originalrpg.ainpc;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AiResponsePresentationTest {
    @Test
    void discardsAnInFlightChineseReplyAfterSwitchingToEnglish() {
        AtomicInteger fallbacks = new AtomicInteger();
        var presented = AiResponsePresentation.select("zh_cn", "en_us", "旧中文答复", true, locale -> {
            assertEquals("en_us", locale);
            fallbacks.incrementAndGet();
            return "Current English guidance";
        });
        assertEquals("Current English guidance", presented.answer());
        assertFalse(presented.remote());
        assertTrue(presented.languageChanged());
        assertEquals(1, fallbacks.get());
    }

    @Test
    void retainsACompletedResponseWithoutCallingFallbackWhenLocaleIsUnchanged() {
        var presented = AiResponsePresentation.select("EN-us", "en_us", "Provider response", true,
                locale -> { throw new AssertionError("Fallback should be lazy"); });
        assertEquals("Provider response", presented.answer());
        assertTrue(presented.remote());
        assertFalse(presented.languageChanged());
    }

    @Test
    void localCompletedResponsesAlsoRespectALanguageChange() {
        var presented = AiResponsePresentation.select("en_us", "zh_cn", "Old local response", false, locale -> "当前本地答复");
        assertEquals("当前本地答复", presented.answer());
        assertFalse(presented.remote());
        assertTrue(presented.languageChanged());
    }
}
