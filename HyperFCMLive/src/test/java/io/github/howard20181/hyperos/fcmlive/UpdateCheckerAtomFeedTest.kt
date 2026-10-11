package io.github.howard20181.hyperos.fcmlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the update checker's Atom fallback parser and the
 * release-page URL whitelist — the two pieces of hand-rolled security-adjacent
 * logic between a tampered feed and an implicit ACTION_VIEW. No Android runtime
 * required.
 */
class UpdateCheckerAtomFeedTest {

    /** A trimmed but faithful slice of the real releases.atom layout. */
    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Release notes from HyperOS_FCM_Live</title>
          <id>tag:github.com,2008:https://github.com/iamqwert/HyperOS_FCM_Live/releases</id>
          <entry>
            <id>tag:github.com,2008:Repository/123456/v1.8.0.18</id>
            <link rel="alternate" type="text/html" href="https://github.com/iamqwert/HyperOS_FCM_Live/releases/tag/v1.8.0.18"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun parsesTagAndPageFromARealLayout() {
        val parsed = UpdateChecker.parseAtomFeed(feed)!!
        assertEquals("1.8.0.18", parsed[0])
        assertEquals(
            "https://github.com/iamqwert/HyperOS_FCM_Live/releases/tag/v1.8.0.18",
            parsed[1]
        )
    }

    @Test
    fun tagWithoutVPrefixIsTakenVerbatim() {
        val noV = feed.replace("/v1.8.0.18</id>", "/1.8.0.18</id>")
        assertEquals("1.8.0.18", UpdateChecker.parseAtomFeed(noV)!![0])
    }

    @Test
    fun feedWithoutEntryIsNull() {
        assertNull(UpdateChecker.parseAtomFeed("<feed></feed>"))
        assertNull(UpdateChecker.parseAtomFeed(""))
    }

    @Test
    fun entryWithoutSlashInIdIsNull() {
        val noSlash = feed.replace(
            "<id>tag:github.com,2008:Repository/123456/v1.8.0.18</id>",
            "<id>justatag</id>"
        )
        assertNull(UpdateChecker.parseAtomFeed(noSlash))
    }

    @Test
    fun untrustedPageFallsBackToTheFixedReleasesPage() {
        val hijacked = feed.replace(
            "https://github.com/iamqwert/HyperOS_FCM_Live/releases/tag/v1.8.0.18",
            "https://evil.example.com/phishing/releases/tag/v1.8.0.18"
        )
        val parsed = UpdateChecker.parseAtomFeed(hijacked)!!
        assertEquals("1.8.0.18", parsed[0])
        assertEquals("https://github.com/iamqwert/HyperOS_FCM_Live/releases", parsed[1])
    }

    @Test
    fun trustedPagesPassTheWhitelist() {
        assertTrue(
            UpdateChecker.isTrustedReleasePage(
                "https://github.com/iamqwert/HyperOS_FCM_Live/releases/tag/v1.8.0.18"
            )
        )
        assertTrue(
            UpdateChecker.isTrustedReleasePage(
                "https://github.com/iamqwert/HyperOS_FCM_Live/releases"
            )
        )
    }

    @Test
    fun everythingElseFailsTheWhitelist() {
        // Not https.
        assertFalse(
            UpdateChecker.isTrustedReleasePage(
                "http://github.com/iamqwert/HyperOS_FCM_Live/releases/tag/v1.8.0.18"
            )
        )
        // A host that merely contains github.com is not github.com.
        assertFalse(
            UpdateChecker.isTrustedReleasePage(
                "https://github.com.evil.com/releases/tag/v1.8.0.18"
            )
        )
        // Sibling subdomain is a different host.
        assertFalse(
            UpdateChecker.isTrustedReleasePage(
                "https://api.github.com/repos/iamqwert/HyperOS_FCM_Live/releases/latest"
            )
        )
        // A github.com page outside /releases/ is not a release page.
        assertFalse(
            UpdateChecker.isTrustedReleasePage("https://github.com/iamqwert/HyperOS_FCM_Live")
        )
        // Nothing at all.
        assertFalse(UpdateChecker.isTrustedReleasePage(null))
        assertFalse(UpdateChecker.isTrustedReleasePage(""))
    }
}
