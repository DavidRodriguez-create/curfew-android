package dev.davidz.curfew.core

/**
 * The MVP blocklist is a fixed, curated candidate list — no "pick any app" browser yet.
 * Only the entries actually installed on the device are ever shown or enforced.
 */
data class CandidateApp(
    val pkg: String,
    val fallbackLabel: String,
    val onByDefault: Boolean,
)

object Blocklist {

    val CANDIDATES: List<CandidateApp> = listOf(
        CandidateApp("com.instagram.android", "Instagram", onByDefault = true),
        CandidateApp("com.google.android.youtube", "YouTube", onByDefault = true),
        CandidateApp("com.zhiliaoapp.musically", "TikTok", onByDefault = true),
        CandidateApp("com.ss.android.ugc.trill", "TikTok (Lite)", onByDefault = true),
        CandidateApp("com.android.chrome", "Chrome", onByDefault = true),
        CandidateApp("com.twitter.android", "X (Twitter)", onByDefault = true),
        CandidateApp("com.x.android", "X", onByDefault = true),
        CandidateApp("com.reddit.frontpage", "Reddit", onByDefault = true),
        CandidateApp("com.facebook.katana", "Facebook", onByDefault = true),
        CandidateApp("com.snapchat.android", "Snapchat", onByDefault = false),
        CandidateApp("com.netflix.mediaclient", "Netflix", onByDefault = false),
        CandidateApp("com.linkedin.android", "LinkedIn", onByDefault = false),
        CandidateApp("com.google.android.apps.youtube.music", "YouTube Music", onByDefault = false),
        CandidateApp("com.twitch.android.app", "Twitch", onByDefault = false),
        CandidateApp("tv.twitch.android.app", "Twitch", onByDefault = false),
        CandidateApp("com.pinterest", "Pinterest", onByDefault = false),
        CandidateApp("com.discord", "Discord", onByDefault = false),
        CandidateApp("org.mozilla.firefox", "Firefox", onByDefault = false),
        CandidateApp("com.brave.browser", "Brave", onByDefault = false),
        CandidateApp("com.sec.android.app.sbrowser", "Samsung Internet", onByDefault = false),
        CandidateApp("com.opera.browser", "Opera", onByDefault = false),
        CandidateApp("com.duckduckgo.mobile.android", "DuckDuckGo", onByDefault = false),
        CandidateApp("com.whatsapp", "WhatsApp", onByDefault = false),
        CandidateApp("org.telegram.messenger", "Telegram", onByDefault = false),
    )

    val DEFAULT_ON: Set<String> = CANDIDATES.filter { it.onByDefault }.map { it.pkg }.toSet()

    private val byPkg: Map<String, CandidateApp> = CANDIDATES.associateBy { it.pkg }

    fun fallbackLabelFor(pkg: String): String = byPkg[pkg]?.fallbackLabel ?: pkg
}
