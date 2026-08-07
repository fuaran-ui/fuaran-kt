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
        val trimmed = url.trim { it.code <= 0x20 }
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.contains('\\')) return null
        if (trimmed.startsWith("//")) return null
        val scheme = schemeOf(trimmed) ?: return trimmed
        return if (scheme in allowedSchemes) trimmed else null
    }

    /** [sanitize], with the refusal reason retained for the [SanitizedUrl] cases. */
    fun classify(url: String): SanitizedUrl {
        sanitize(url)?.let { return SanitizedUrl.Allowed(it) }
        val trimmed = url.trim { it.code <= 0x20 }
        return when {
            trimmed.contains('\\') ->
                SanitizedUrl.Rejected(url, "backslash forms are refused (they normalise to '//')")
            trimmed.startsWith("//") ->
                SanitizedUrl.Rejected(url, "protocol-relative '//' URLs are refused")
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
 * For a `Navigate` action, its `route` put through the URL floor; `null` for every other action. A
 * `Navigate` route is always a literal on the wire, so this never reports `Dynamic`.
 *
 * The surface itself never routes a `Navigate` anywhere — `dispatchAction` hands it back to the
 * embedding app precisely so the app decides — which is exactly why the app must floor it before
 * turning it into an `Intent`.
 */
val Action.sanitizedNavigateRoute: SanitizedUrl?
    get() = (this as? NavigateAction)?.let { FuaranUrlPolicy.classify(it.route) }
