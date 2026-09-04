// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

/**
 * The URL safety floor for tree-supplied destinations.
 *
 * This surface has **no WebView** and no HTML-parsing text path — `Markdown.text` and every other
 * `TextSource` render as native text — so it carries no direct script-injection sink at all. That
 * is a structural property of rendering a decoded tree into native views, and it is worth saying
 * because it is the reason this file is small.
 *
 * What the absence of a WebView does NOT remove is the **destination** sink. `Link.href`,
 * `Image.src` and `Navigate.route` all arrive from the tree — usually from a model — and an
 * embedding app that hands one to an `Intent`, a `CustomTabsIntent`, an image loader or a web view
 * it adds later has a scheme-injection / deep-link sink the wire format never promised it was safe
 * from.
 *
 * The projection deliberately hands those values on **raw**: `href` / `src` are [Binding]s whose
 * value may not exist until the core resolves a `State`, `Query` or `Format` slot at render time,
 * so a decode-time allowlist would be checking a placeholder, and filtering during decode would
 * also stop the projection being a faithful view of the wire. The floor therefore lives here — a
 * pure function the app calls when it has a resolved string, plus `sanitized*` accessors for the
 * common case where the binding IS a literal.
 *
 * Consumer obligation, stated once and loudly: **never pass a tree-supplied URL to an
 * open/navigate/fetch API without routing it through [FuaranUrlPolicy] first.**
 */
sealed interface SanitizedUrl {
    /** A literal destination that passed the allowlist. Safe to open. */
    data class Allowed(val url: String) : SanitizedUrl

    /** A literal destination that failed the allowlist. Do not open it. */
    data class Rejected(val raw: String, val reason: String) : SanitizedUrl

    /**
     * The slot holds a non-literal [Binding] — resolve it against the session, then call
     * [FuaranUrlPolicy.sanitize] on the resolved string. A distinct case rather than a null,
     * because "refused" and "not knowable yet" call for different handling.
     */
    data object Dynamic : SanitizedUrl

    /** The URL when it is both literal and allowed; `null` in every other case. */
    val openable: String?
        get() = (this as? Allowed)?.url
}

/**
 * The scheme allowlist every tree-supplied destination is measured against.
 *
 * Deliberately narrower than a browser's: a native surface has no legitimate use for `data:`,
 * `blob:`, `file:` or a custom app scheme it was handed by a document, and an unknown scheme is
 * refused rather than passed through — the deny-by-default posture the decoder takes everywhere
 * else, applied to destinations.
 */
object FuaranUrlPolicy {
    /**
     * The four schemes a tree may name. `mailto` and `tel` are here because a contact link is an
     * ordinary thing for a document to carry and both are handled by the platform without a fetch.
     */
    val allowedSchemes: List<String> = listOf("http", "https", "mailto", "tel")

    /**
     * Schemes refused by name, so the refusal can teach rather than say "unknown scheme". Anything
     * outside [allowedSchemes] is refused regardless of whether it appears here.
     */
    val deniedSchemes: List<String> = listOf("javascript", "vbscript", "data", "file", "blob", "about", "intent")

    /**
     * The scheme candidate of a URL, lower-cased, or `null` when the URL carries no scheme
     * (relative path, query, or fragment).
     *
     * ASCII whitespace and C0 controls are stripped from the candidate before comparison, so
     * `java\tscript:` classifies as `javascript` — the classic evasion, and the reason a naive
     * `startsWith("javascript:")` check is not a floor.
     */
    /**
     * WIRE_FORMAT 19 rule 1 - normalise a URL string exactly as the WHATWG URL Standard's
     * basic URL parser does BEFORE it parses anything, ASCII-exact, in this order:
     *
     *  1. remove leading and trailing C0-control-or-space - ALL of U+0000..U+0020, not
     *     merely the whitespace subset;
     *  2. remove every U+0009 / U+000A / U+000D from anywhere in what remains.
     *
     * Deliberately NOT [String.trim] with the default predicate. A native trim answers a
     * different question in every language - Python's `strip` also removes U+001C..U+001F
     * where Kotlin, JS, .NET, Go and Rust do not - and all of them remove non-ASCII
     * whitespace (U+00A0, U+2028) that the parser KEEPS. The floor's whole purpose is that
     * a tree vetted on one host is safe on another, so the normalisation must be defined by
     * the parser that will actually consume the string, not by the host's standard library.
     *
     * Step 2 is those three code points ONLY: the parser removes U+000B and U+000C at the
     * EDGES (step 1) and keeps them in the INTERIOR, so `/<VT>/host/x` is an ordinary
     * same-origin path and must stay one.
     *
     * The normalised form is also what is EMITTED on acceptance, so an accepted URL
     * carrying an interior tab loses it - which is what the browser would have parsed
     * anyway. Emitting the raw string instead would hand the embedding app a value the
     * floor never actually inspected.
     */
    internal fun normaliseForFloor(url: String): String {
        var lo = 0
        var hi = url.length - 1
        while (lo <= hi && url[lo].code <= 0x20) lo++
        while (hi >= lo && url[hi].code <= 0x20) hi--
        val sb = StringBuilder(hi - lo + 1)
        for (i in lo..hi) {
            val c = url[i]
            if (c.code == 0x09 || c.code == 0x0A || c.code == 0x0D) continue
            sb.append(c)
        }
        return sb.toString()
    }

    /**
     * A protocol-relative URL: `//host/path`, plus the backslash forms browsers normalise
     * to it. WHATWG URL parsing treats `\` as `/` for special schemes, so `\\host`,
     * `/\host` and `\/host` all resolve exactly as `//host` does.
     *
     * These carry no scheme, so the schemeless branch of [sanitize] would otherwise admit
     * them - but the resolver supplies the CURRENT origin's scheme and lands OFF-ORIGIN,
     * defeating the same-origin intent that makes a schemeless URL safe in the first place.
     *
     * The test is POSITIONAL - the first two characters - rather than "contains a
     * backslash". That distinction is the whole finding: a blanket contains-check refuses
     * `\host` (a single leading backslash, which the parser reads as the same-origin path
     * `/host`) while a single interior tab slips `/<TAB>/host` past a `startsWith("//")`
     * check entirely. Normalisation first, then position, gets both right.
     */
    internal fun isProtocolRelative(url: String): Boolean {
        if (url.length < 2) return false
        val a = url[0]
        val b = url[1]
        return (a == '/' || a == '\\') && (b == '/' || b == '\\')
    }

    internal fun schemeOf(url: String): String? {
        val candidate = StringBuilder()
        for (ch in url) {
            when {
                ch == ':' -> return candidate.toString().trim().lowercase()
                ch == '/' || ch == '?' || ch == '#' -> return null
                ch.code > 0x20 -> candidate.append(ch)
            }
        }
        return null
    }

    /**
     * Apply the floor to an already-resolved URL string. Returns the URL when it is acceptable,
     * `null` when it must not be opened.
     *
     * Accepted: the empty string (a same-page destination), a relative path / query / fragment, and
     * an absolute URL whose scheme is in [allowedSchemes]. Refused: every other scheme; a
     * **protocol-relative** `//host` form (it inherits whatever scheme the caller happens to be
     * using, which on a native surface is not a meaningful thing to inherit); and any **backslash**
     * form (`\\host`, `/\host`), which several URL parsers normalise to `//` and which therefore
     * smuggles a protocol-relative URL past a `//` check.
     */
    fun sanitize(url: String): String? {
        val trimmed = normaliseForFloor(url)
        if (trimmed.isEmpty()) return trimmed
        if (isProtocolRelative(trimmed)) return null
        val scheme = schemeOf(trimmed) ?: return trimmed
        return if (scheme in allowedSchemes) trimmed else null
    }

    /** [sanitize], with the refusal reason retained for the [SanitizedUrl] cases. */
    fun classify(url: String): SanitizedUrl {
        sanitize(url)?.let { return SanitizedUrl.Allowed(it) }
        val trimmed = normaliseForFloor(url)
        return when {
            isProtocolRelative(trimmed) ->
                SanitizedUrl.Rejected(
                    url,
                    "protocol-relative URLs are refused - '$trimmed' resolves off-origin " +
                        "(the backslash forms normalise to '//' too)",
                )
            else -> {
                val scheme = schemeOf(trimmed) ?: "<none>"
                if (scheme in deniedSchemes) {
                    SanitizedUrl.Rejected(url, "the '$scheme:' scheme is refused")
                } else {
                    SanitizedUrl.Rejected(
                        url,
                        "scheme '$scheme:' is not one of ${allowedSchemes.joinToString(" ") { "$it:" }} (deny by default)",
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- //
// Sanitising accessors on the decoded tree
// --------------------------------------------------------------------------- //
//
// These cover the literal case — by far the most common shape a model emits — and report
// `Dynamic` for everything else rather than guessing.

/** The literal string this binding carries, when it is a `Static` string; `null` for every dynamic case. */
val Binding.literalString: String?
    get() = ((this as? StaticBinding)?.value as? JsonString)?.value

/** This binding's literal value put through the URL floor. */
val Binding.sanitizedUrl: SanitizedUrl
    get() = literalString?.let { FuaranUrlPolicy.classify(it) } ?: SanitizedUrl.Dynamic

/** `href` put through the URL floor. **Use this, not `href`, when the value is about to be opened.** */
val Link.sanitizedHref: SanitizedUrl
    get() = href.sanitizedUrl

/** `src` put through the URL floor. **Use this, not `src`, when the value is about to be fetched.** */
val Image.sanitizedSrc: SanitizedUrl
    get() = src.sanitizedUrl

/**
 * A `srcSet` candidate's `src` put through the floor (WIRE_FORMAT.md 3.6.4). A candidate is a URL a
 * client fetches with no user act — the same class as the primary `src`, and therefore the same
 * obligation; a slot that skipped the floor would be a documented way around it.
 *
 * Note the REMEDY differs from the primary source's, and a consumer must not copy it across: an
 * `<img>` must have a `src`, so a refused primary collapses to the refusal substitute, whereas a
 * refused CANDIDATE is DROPPED from the list. The primary remains the fallback the whole mechanism
 * rests on, so offering one fewer rendition costs nothing; offering one guaranteed to fail does.
 */
val SrcSetEntry.sanitizedSrc: SanitizedUrl
    get() = src.sanitizedUrl

/** A media element's `src` put through the URL floor, on the same terms as an image's. */
val Media.sanitizedSrc: SanitizedUrl
    get() = src.sanitizedUrl

/**
 * A text track's `src` put through the floor (3.6.6, Phase 1110). A track file is fetched by the
 * client with no user act, exactly as [Media.src] is, so it carries the same obligation — a slot
 * that skipped the floor would be a documented way around it.
 *
 * The REMEDY is the POSTER's rather than the source's, and a consumer must not copy the source's
 * across: an element must have a source, but it need not have this track, so a refused track is
 * **DROPPED**. A `<track>` pointing at the refusal URL is a caption menu entry that opens onto
 * nothing.
 */
val TrackEntry.sanitizedSrc: SanitizedUrl
    get() = src.sanitizedUrl

/**
 * The `embed` egress class (WIRE_FORMAT.md 19.1, Phase 1111) — a **stricter** floor for a slot
 * that EXECUTES.
 *
 * Everything else §19 governs is fetch-and-display or navigate-on-a-click; an embed fetches a
 * document and lets it RUN, so it does not ride [FuaranUrlPolicy.allowedSchemes] at all. Rule 1's
 * normalisation and rule 2's scheme extraction are shared unchanged — that is what makes any
 * positional or prefix test see the string a parser will see — and then the accept set is
 * `https` and nothing else.
 *
 * **Two of the exclusions are things §19 accepts, and both are deliberate.** `http` is refused
 * because a document delivered over a channel any intermediary can rewrite is an intermediary's
 * script running in a frame this page created — a risk that does not arise when the same channel
 * delivers an image. And a **schemeless** reference is refused, which is the sharper departure: a
 * relative reference names a same-origin document, and a same-origin frame is exactly the shape
 * where a document granted both `AllowSameOrigin` and `AllowScripts` can reach its own frame
 * ELEMENT and strip the sandbox from it.
 *
 * Because the class admits exactly one scheme it performs no positional test, so it needs no
 * analogue of §19's protocol-relative rule and cannot inherit that rule's historic evasions.
 *
 * **This is a RENDER-time obligation and not a wire constraint**: a document naming an `http` or
 * relative embed source is a valid wire document, the decoder does not reject it, and this
 * accessor is what a consumer consults before mounting a browsing context. The remedy on refusal
 * is likewise its own: emit the element with **no source at all** — never `about:blank`, never the
 * original value — because a frame pointed at a refusal URL renders that page, where one with no
 * source is a well-defined empty context that fetches nothing.
 */
val Embed.sanitizedSrc: SanitizedUrl
    get() =
        src.literalString?.let { raw ->
            val trimmed = FuaranUrlPolicy.normaliseForFloor(raw)
            when (FuaranUrlPolicy.schemeOf(trimmed)) {
                "https" -> SanitizedUrl.Allowed(trimmed)
                null ->
                    SanitizedUrl.Rejected(
                        raw,
                        "the embed class admits no schemeless reference — a same-origin frame granted " +
                            "AllowSameOrigin and AllowScripts can reach its own frame element and remove " +
                            "the sandbox; compose your own content with Mount instead",
                    )
                else ->
                    SanitizedUrl.Rejected(
                        raw,
                        "the embed class accepts 'https:' and nothing else — a document that EXECUTES " +
                            "does not ride the ordinary §19 accept set",
                    )
            }
        } ?: SanitizedUrl.Dynamic

/**
 * A video's `poster` put through the floor (3.6.6). Fetched with no user act exactly as `src` is,
 * and — like a `srcSet` candidate and unlike the primary source — a refused poster is **dropped**
 * rather than neutered: a `<video>` with no poster shows its first frame, which is a working
 * rendering, while a poster pointing at the refusal URL is a broken image painted over the player.
 *
 * `null` when the case declares no poster (or is [Audio], which has no such slot at all).
 */
val MediaKind.sanitizedPoster: SanitizedUrl?
    get() =
        when (this) {
            is Video -> poster?.sanitizedUrl
            Audio -> null
        }

/**
 * For a `Navigate` action, its `route` put through the URL floor; `null` for every other action. A
 * `Navigate` route is always a literal on the wire, so this never reports `Dynamic`.
 *
 * The surface itself never routes a `Navigate` anywhere — `dispatchAction` hands it back to the
 * embedding app precisely so the app decides — which is exactly why the app must floor it before
 * turning it into an `Intent`.
 */
val Action.sanitizedNavigateRoute: SanitizedUrl?
    get() = (this as? NavigateAction)?.let { FuaranUrlPolicy.classify(it.route) }
