package ch.amicalewifi.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaptivePortalParamFilterTest {

    @Test
    void stripQuery_preserves_scheme_host_path() {
        assertEquals("https://example.com/page",
                CaptivePortalParamFilter.stripQuery("https://example.com/page"));
    }

    @Test
    void stripQuery_drops_query_string_and_marks_redacted() {
        assertEquals("https://example.com/reset?[REDACTED]",
                CaptivePortalParamFilter.stripQuery("https://example.com/reset?token=secret123&email=a@b.c"));
    }

    @Test
    void stripQuery_drops_fragment() {
        assertEquals("https://example.com/path?[REDACTED]",
                CaptivePortalParamFilter.stripQuery("https://example.com/path#access_token=xyz"));
    }

    @Test
    void stripQuery_keeps_explicit_port() {
        assertEquals("https://example.com:8443/x",
                CaptivePortalParamFilter.stripQuery("https://example.com:8443/x"));
    }

    @Test
    void stripQuery_passes_through_blank_and_null() {
        assertNull(CaptivePortalParamFilter.stripQuery(null));
        assertEquals("", CaptivePortalParamFilter.stripQuery(""));
    }

    @Test
    void stripQuery_returns_marker_for_invalid_url() {
        assertEquals("[invalid-url]",
                CaptivePortalParamFilter.stripQuery("https://exa mple.com/with space"));
    }

    @Test
    void redactValue_masks_password_like_names() {
        assertEquals("[REDACTED]", CaptivePortalParamFilter.redactValue("password", "hunter2"));
        assertEquals("[REDACTED]", CaptivePortalParamFilter.redactValue("Authorization", "Bearer abc"));
        assertEquals("[REDACTED]", CaptivePortalParamFilter.redactValue("api_key", "k-123"));
        assertEquals("[REDACTED]", CaptivePortalParamFilter.redactValue("reset_token", "rt-xyz"));
        assertEquals("[REDACTED]", CaptivePortalParamFilter.redactValue("MY_SECRET", "s"));
    }

    @Test
    void redactValue_strips_query_for_url_params() {
        assertEquals("https://example.com/x?[REDACTED]",
                CaptivePortalParamFilter.redactValue("url", "https://example.com/x?token=abc"));
        assertEquals("https://example.com/y",
                CaptivePortalParamFilter.redactValue("redirect", "https://example.com/y"));
    }

    @Test
    void redactValue_keeps_normal_values() {
        assertEquals("aa:bb:cc:dd:ee:ff",
                CaptivePortalParamFilter.redactValue("id", "aa:bb:cc:dd:ee:ff"));
        assertEquals("AmicaleWifi",
                CaptivePortalParamFilter.redactValue("ssid", "AmicaleWifi"));
        assertEquals("1715600000",
                CaptivePortalParamFilter.redactValue("t", "1715600000"));
    }
}
