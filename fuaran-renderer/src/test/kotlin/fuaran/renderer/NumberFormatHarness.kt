// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.CurrencyValueFormat
import fuaran.ui.DurationStyle
import fuaran.ui.DurationUnit
import fuaran.ui.JsonString
import fuaran.ui.NumberValueFormat
import fuaran.ui.PercentValueFormat
import fuaran.ui.SignificantDigitsValueFormat
import fuaran.ui.StaticBinding
import java.util.Locale

/**
 * The number-formatting goldens (Phase 1541), asserted in the plain-JVM gate.
 *
 * **It runs the whole set under `Locale.GERMANY`, and that is the entire point.** `String.format`
 * with no locale uses the JVM's DEFAULT locale, so `formatCellValue` rendered `GBP 1234,50` and
 * `12,5%` on any machine set to a decimal-comma locale — most of continental Europe, and any Android
 * device configured that way. A formatted datum crosses the wire as TEXT and is compared, keyed and
 * re-parsed downstream (a grid column's tone map is keyed on the author's raw value), so a decimal
 * comma is not a prettier spelling: it is a different string that silently stops matching, on the
 * reader's device and nowhere near the author.
 *
 * A gate run under a POSIX locale cannot see any of that — which is why the fix is NOT to pin the
 * test's locale to the one that passes. It is the opposite: pin the test's locale to the one that
 * FAILS on the old code, and fix the formatter. Under `Locale.ROOT` these goldens are green either
 * way and prove nothing.
 *
 * The default locale is restored afterwards, because a harness that leaves the JVM's locale changed
 * would silently decide the outcome of whichever leg runs next.
 *
 * The same goldens are asserted on the sibling Swift surface, where the platform default already runs
 * the safe way and the change there was to STATE the invariance rather than to repair it.
 */

/** How many checks [numberFormatFailures] runs — reported so a leg that shrank is visible. */
var numberFormatChecksRun = 0
    private set

internal fun numberFormatFailures(): List<String> {
    val c = SentimentChecks()
    val original = Locale.getDefault()
    try {
        // A decimal-comma, non-POSIX locale. Under it the pre-1541 formatter emits `1234,50`.
        Locale.setDefault(Locale.GERMANY)

        c.check("currencyIsLocaleInvariant") {
            c.eq("GBP", "GBP 1234.50", formatCellValue("1234.5", CurrencyValueFormat("GBP")))
        }
        c.check("percentIsLocaleInvariant") {
            c.eq("one decimal", "12.5%", formatCellValue("0.125", PercentValueFormat(1)))
            c.eq("no decimals", "7%", formatCellValue("0.07", PercentValueFormat(0)))
        }
        c.check("fixedDecimalsAreLocaleInvariant") {
            c.eq("two decimals", "3.14", formatCellValue("3.14159", NumberValueFormat(2)))
        }
        c.check("significantDigitsAreLocaleInvariant") {
            c.eq("three digits", "12.3", formatCellValue("12.3456", SignificantDigitsValueFormat(3)))
        }
        c.check("clockDurationIsLocaleInvariant") {
            // `%02d` under a locale with its own digit shapes must still be ASCII digits and colons.
            c.eq("clock", "01:05:00", formatDuration(3900.0, DurationUnit.Seconds, DurationStyle.Clock))
        }

        // The parse side of the same claim. `toDoubleOrNull` is locale-INVARIANT, so the wire's
        // decimal point is a full stop wherever the host runs — and a comma is NOT a number, rather
        // than being read as one under a German default and refused under a POSIX one.
        c.check("resolveDoubleReadsTheWiresDecimalPoint") {
            val ctx = BindingContext.Empty
            c.eq("point", 3.7, ctx.resolveDouble(StaticBinding(JsonString("3.7")), 0.0))
            c.eq("comma is not a number", 0.0, ctx.resolveDouble(StaticBinding(JsonString("3,7")), 0.0))
        }

        // Why `Double` and not `Float`: the resolved value is written straight back to the session as
        // a number, so a round trip through `Float` replaces the author's lexeme with the nearest
        // float widened back out — `3.7` becomes `3.700000047683716` in the slot every other reader
        // of that state then sees.
        c.check("resolvingThroughDoubleKeepsTheAuthorsLexeme") {
            val ctx = BindingContext.Empty
            c.eq("double", "3.7", ctx.resolveDouble(StaticBinding(JsonString("3.7")), 0.0).toString())
            c.eq("float would not", false, "3.7".toFloat().toDouble().toString() == "3.7")
        }
    } finally {
        Locale.setDefault(original)
    }

    numberFormatChecksRun = c.passed + c.failures.size
    return c.failures
}

fun main() {
    println("== number formatting :: locale invariance (asserted under Locale.GERMANY) ==")
    val failures = numberFormatFailures()
    if (failures.isEmpty()) {
        println("PASS: $numberFormatChecksRun number-format checks green under a decimal-comma default locale.")
    } else {
        println("FAIL: ${failures.size} number-format check(s) failed")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
