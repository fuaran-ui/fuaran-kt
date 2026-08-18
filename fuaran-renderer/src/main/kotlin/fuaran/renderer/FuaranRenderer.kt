// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fuaran.ui.AutoLayout
import fuaran.ui.Badge
import fuaran.ui.Box
import fuaran.ui.BoxLayout
import fuaran.ui.BoxRole
import fuaran.ui.Button
import fuaran.ui.ButtonCell
import fuaran.ui.ButtonGroupCell
import fuaran.ui.ButtonVariant
import fuaran.ui.Callout
import fuaran.ui.Chart
import fuaran.ui.CheckboxCell
import fuaran.ui.CheckboxField
import fuaran.ui.ChoiceField
import fuaran.ui.CodeBlock
import fuaran.ui.GridColumn
import fuaran.ui.Custom
import fuaran.ui.CustomCell
import fuaran.ui.DataGrid
import fuaran.ui.DateCell
import fuaran.ui.DateField
import fuaran.ui.DateRangeField
import fuaran.ui.Disclosure
import fuaran.ui.Drawing
import fuaran.ui.EditableCell
import fuaran.ui.ErrorBoundary
import fuaran.ui.Fact
import fuaran.ui.FileUpload
import fuaran.ui.Filters
import fuaran.ui.FlexLayout
import fuaran.ui.Form
import fuaran.ui.FormField
import fuaran.ui.FormFieldKind
import fuaran.ui.FragmentDecl
import fuaran.ui.FragmentRef
import fuaran.ui.GridLayout
import fuaran.ui.Heading
import fuaran.ui.Image
import fuaran.ui.Icon
import fuaran.ui.IconSize
import fuaran.ui.JsonValue
import fuaran.ui.LabelShape
import fuaran.ui.LabelValueRow
import fuaran.ui.Link
import fuaran.ui.LinkCell
import fuaran.ui.ListNode
import fuaran.ui.MapNode
import fuaran.ui.Markdown
import fuaran.ui.Math
import fuaran.ui.Metric
import fuaran.ui.Modal
import fuaran.ui.Mount
import fuaran.ui.Node
import fuaran.ui.NodeKind
import fuaran.ui.NumberField
import fuaran.ui.NumericCell
import fuaran.ui.Orientation
import fuaran.ui.PillCell
import fuaran.ui.Progress
import fuaran.ui.ProgressCell
import fuaran.ui.RangeField
import fuaran.ui.RangedNumberField
import fuaran.ui.ResolvedRows
import fuaran.ui.ScrollArea
import fuaran.ui.SegmentedChoiceField
import fuaran.ui.Select
import fuaran.ui.Skeleton
import fuaran.ui.Sparkline
import fuaran.ui.SplitPanel
import fuaran.ui.Stepper
import fuaran.ui.SummaryList
import fuaran.ui.Switch
import fuaran.ui.Tabs
import fuaran.ui.TextAreaField
import fuaran.ui.TextCell
import fuaran.ui.TextField
import fuaran.ui.Toast
import fuaran.ui.ToggleField
import fuaran.ui.ToneVariant
import fuaran.ui.TonedPillCell
import fuaran.ui.discriminator
import androidx.compose.foundation.layout.Box as CBox
import androidx.compose.material3.Button as M3Button

// --------------------------------------------------------------------------- //
// Public entry — the else-free exhaustive dispatch spine
// --------------------------------------------------------------------------- //

/**
 * Render a decoded [Node] as Jetpack Compose. A **pure projection** of the sealed model — no
 * wire-JSON parsing happens here (decode ran first, in `:fuaran-ui`). The `when` over the sealed
 * [NodeKind] is **exhaustive with no `else`**, so a new wire kind is a compile error until its arm
 * lands (the render-floor twin of Phase 542's decode spine).
 */
@Composable
fun FuaranNode(node: Node, ctx: BindingContext = BindingContext.Empty) {
    LocalRenderCoverage.current?.count(node.kind.discriminator())
    when (val k = node.kind) {
        // Layout
        is Box -> RenderBox(k, ctx)
        is SplitPanel -> RenderSplitPanel(k, ctx)
        is Tabs -> RenderTabs(k, ctx)
        is Stepper -> RenderStepper(k, ctx)
        is SummaryList -> RenderSummaryList(k, ctx)
        is Disclosure -> RenderDisclosure(k, ctx)
        is Modal -> RenderModal(k, ctx)
        is ScrollArea -> RenderScrollArea(k, ctx)
        is Mount -> InfoCard("Mount", k.scopeId + " · " + k.capabilities.joinToString(","))
        is Switch -> RenderSwitch(k, ctx)
        // Display
        is Heading -> RenderHeading(k, ctx)
        is Markdown -> Text(ctx.resolveText(k.text), modifier = Modifier.padding(2.dp))
        is Metric -> RenderMetric(k, ctx)
        is Badge -> RenderBadge(k, ctx)
        is Sparkline -> RenderSparkline()
        is Callout -> RenderCallout(k, ctx)
        is Progress -> RenderProgress(k, ctx)
        is Skeleton -> RenderSkeleton(k)
        is LabelValueRow -> RenderLabelValueRow(k, ctx)
        is Fact -> RenderFact(k, ctx)
        is Icon -> RenderIcon(k, ctx)
        is Link -> RenderLink(k, ctx)
        is Image -> RenderImage(k, ctx)
        is ListNode -> RenderList(k, ctx)
        is Toast -> RenderToast(k, ctx)
        is CodeBlock -> RenderCodeBlock(k)
        is Math -> Text(k.source, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(2.dp))
        is Drawing -> RenderDrawing(k, ctx)
        // Input
        is Form -> RenderForm(k, ctx)
        is Button -> RenderButton(k, ctx)
        is FileUpload -> OutlinedButton(onClick = {}, enabled = !ctx.resolveBool(k.disabled)) { Text(ctx.resolveText(k.label)) }
        is Select -> RenderSelect(k, ctx)
        is Filters -> RenderFilters(k, ctx)
        // Visualisation
        is DataGrid -> RenderDataGrid(k, node.id, ctx)
        is Chart -> RenderChart(k, ctx)
        is MapNode -> InfoCard("Map", "lat ${k.centreLatitude}, lng ${k.centreLongitude} · z${k.zoom}")
        // Structural
        is Custom -> InfoCard("Custom", k.moduleId + "/" + k.componentId)
        is ErrorBoundary -> FuaranNode(k.child, ctx)
        is FragmentDecl -> FuaranNode(k.body, ctx)
        is FragmentRef -> InfoCard("Fragment", k.name)
    }
}

/**
 * The defined visible development placeholder for a kind a host deliberately leaves unimplemented
 * (never a silent skip). Structurally unreachable from [FuaranNode]'s exhaustive spine — a new kind
 * is a compile error — it exists so hosts have a loud, coverage-tracked fallback rather than a blank.
 */
@Composable
fun FallbackPlaceholder(discriminator: String) {
    LocalRenderCoverage.current?.fallback(discriminator)
    CBox(
        Modifier
            .border(1.dp, Color.Red, RoundedCornerShape(4.dp))
            .padding(6.dp)
            .testTag("fuaran-fallback"),
    ) {
        Text("⚠ unrendered node kind: $discriminator", color = Color.Red, fontSize = 12.sp)
    }
}

// --------------------------------------------------------------------------- //
// Shared helpers
// --------------------------------------------------------------------------- //

@Composable
private fun InfoCard(title: String, subtitle: String) {
    Card(Modifier.padding(2.dp)) {
        Column(Modifier.padding(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun RenderChildren(children: List<Node>, layout: BoxLayout, ctx: BindingContext) {
    when (layout) {
        is FlexLayout -> {
            val gap = (layout.gap ?: 4).dp
            if (layout.direction == Orientation.Horizontal) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { children.forEach { FuaranNode(it, ctx) } }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(gap)) { children.forEach { FuaranNode(it, ctx) } }
            }
        }
        is GridLayout -> {
            val cols = layout.cols.coerceAtLeast(1)
            val gap = (layout.gap ?: 4).dp
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                children.chunked(cols).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) { rowItems.forEach { FuaranNode(it, ctx) } }
                }
            }
        }
        AutoLayout -> Column { children.forEach { FuaranNode(it, ctx) } }
    }
}

// --------------------------------------------------------------------------- //
// Layout
// --------------------------------------------------------------------------- //

@Composable
private fun RenderBox(k: Box, ctx: BindingContext) {
    val body: @Composable () -> Unit = {
        k.heading?.let { Text(ctx.resolveText(it), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 2.dp)) }
        RenderChildren(k.children, k.layout, ctx)
    }
    when (k.role) {
        BoxRole.Card -> Card(Modifier.padding(4.dp)) { Column(Modifier.padding(10.dp)) { body() } }
        BoxRole.Dashboard -> Column(Modifier.padding(8.dp)) { body() }
        BoxRole.Group -> Column(Modifier.padding(2.dp)) { body() }
        BoxRole.Separator -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun RenderSplitPanel(k: SplitPanel, ctx: BindingContext) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        k.children.forEach { CBox(Modifier.padding(2.dp)) { FuaranNode(it, ctx) } }
    }
}

@Composable
private fun RenderTabs(k: fuaran.ui.Tabs, ctx: BindingContext) {
    val active = ctx.resolveInt(k.activeIndex, 0).coerceIn(0, (k.children.size - 1).coerceAtLeast(0))
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            k.children.indices.forEach { i ->
                val label = k.tabHeaders?.getOrNull(i)?.let { ctx.resolveText(it.label) } ?: "Tab ${i + 1}"
                Text(
                    label,
                    fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
        HorizontalDivider()
        k.children.getOrNull(active)?.let { FuaranNode(it, ctx) }
    }
}

@Composable
private fun RenderStepper(k: Stepper, ctx: BindingContext) {
    val active = ctx.resolveInt(k.activeStep, 0)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        k.children.forEachIndexed { i, child ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ", fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal)
                FuaranNode(child, ctx)
            }
        }
    }
}

@Composable
private fun RenderSummaryList(k: SummaryList, ctx: BindingContext) {
    Column {
        k.heading?.let { Text(ctx.resolveText(it), fontWeight = FontWeight.SemiBold) }
        k.children.forEach { FuaranNode(it, ctx) }
    }
}

@Composable
private fun RenderDisclosure(k: Disclosure, ctx: BindingContext) {
    val open = if (k.open is fuaran.ui.StaticBinding) ctx.resolveBool(k.open) else k.defaultOpen
    Column {
        Text((if (open) "▼ " else "▶ ") + ctx.resolveText(k.heading), fontWeight = FontWeight.SemiBold)
        if (open) Column(Modifier.padding(start = 12.dp)) { k.children.forEach { FuaranNode(it, ctx) } }
    }
}

@Composable
private fun RenderModal(k: Modal, ctx: BindingContext) {
    Card(Modifier.padding(4.dp)) {
        Column(Modifier.padding(10.dp)) {
            k.heading?.let { Text(ctx.resolveText(it), fontWeight = FontWeight.Bold) }
            k.children.forEach { FuaranNode(it, ctx) }
        }
    }
}

@Composable
private fun RenderScrollArea(k: ScrollArea, ctx: BindingContext) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        k.children.forEach { FuaranNode(it, ctx) }
    }
}

@Composable
private fun RenderSwitch(k: Switch, ctx: BindingContext) {
    // `on` is the more specific declaration and wins where both are present; the decoder has
    // already refused a Switch carrying neither, so the elvis tail is unreachable rather than a
    // silent default-to-empty.
    val selector = k.on ?: k.stateKey?.let { fuaran.ui.StateBinding(it) }
    val current = selector?.let { ctx.resolve(it) } ?: ""
    val chosen = k.cases.firstOrNull { it.match == current }?.child ?: k.default
    FuaranNode(chosen, ctx)
}

// --------------------------------------------------------------------------- //
// Display
// --------------------------------------------------------------------------- //

@Composable
private fun RenderHeading(k: Heading, ctx: BindingContext) {
    val size = when (k.level.coerceIn(1, 6)) {
        1 -> 26.sp
        2 -> 22.sp
        3 -> 19.sp
        4 -> 17.sp
        5 -> 15.sp
        else -> 13.sp
    }
    Text(ctx.resolveText(k.text), fontSize = size, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun RenderMetric(k: Metric, ctx: BindingContext) {
    // The metric value carries the tone accent — server-emitted tone, native styling.
    val accent = tone(k.tone).accent
    Column(Modifier.padding(4.dp)) {
        Text(ctx.resolveText(k.label), fontSize = 12.sp, color = Color.Gray)
        Text(ctx.resolve(k.value), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent)
        k.subtext?.let { Text(ctx.resolveText(it), fontSize = 11.sp, color = Color.Gray) }
        k.trend?.let { Text(ctx.resolve(it), fontSize = 11.sp) }
    }
}

@Composable
private fun RenderBadge(k: Badge, ctx: BindingContext) {
    val swatch = badge(k.variant)
    CBox(
        Modifier
            .background(swatch.container, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(ctx.resolveText(k.label), fontSize = 12.sp, color = swatch.onContainer)
    }
}

@Composable
private fun RenderSparkline() {
    CBox(
        Modifier
            .width(80.dp)
            .height(16.dp)
            .background(Color(0xFFB0BEC5), RoundedCornerShape(2.dp)),
    ) {}
}

@Composable
private fun RenderCallout(k: Callout, ctx: BindingContext) {
    val swatch = tone(k.tone)
    CBox(Modifier.background(swatch.container, RoundedCornerShape(6.dp)).padding(4.dp)) {
        Column(Modifier.padding(10.dp)) {
            k.heading?.let { Text(ctx.resolveText(it), fontWeight = FontWeight.Bold, color = swatch.onContainer) }
            Text(ctx.resolveText(k.body), color = swatch.onContainer)
        }
    }
}

@Composable
private fun RenderProgress(k: Progress, ctx: BindingContext) {
    Column {
        k.label?.let { Text(ctx.resolveText(it), fontSize = 12.sp) }
        val accent = tone(k.tone).accent
        if (k.indeterminate) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
        } else {
            LinearProgressIndicator(
                progress = { ctx.resolveFloat(k.fraction, 0f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = accent,
            )
        }
        k.caveat?.let { Text(ctx.resolveText(it), fontSize = 11.sp, color = Color.Gray) }
    }
}

@Composable
private fun RenderSkeleton(k: Skeleton) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(k.rows.coerceIn(0, 20)) {
            CBox(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(2.dp)),
            ) {}
        }
    }
}

@Composable
private fun RenderLabelValueRow(k: LabelValueRow, ctx: BindingContext) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(ctx.resolveText(k.label), color = Color.Gray)
        Text(ctx.resolve(k.value), fontWeight = if (k.emphasis) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun RenderFact(k: Fact, ctx: BindingContext) {
    // The labeled text-fact — Metric's complementary kind: label + TextSource value.
    val accent = tone(k.tone).accent
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(ctx.resolveText(k.label), color = Color.Gray)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                ctx.resolveText(k.value),
                fontWeight = if (k.emphasis) FontWeight.Bold else FontWeight.Normal,
                color = accent,
            )
            k.help?.let { Text(ctx.resolveText(it), fontSize = 11.sp, color = Color.Gray) }
        }
    }
}

@Composable
private fun RenderIcon(k: Icon, ctx: BindingContext) {
    // The uniform icon-hook contract the HTML hosts also honour: the glyph NAME is the payload
    // and the HOST owns the name -> glyph mapping, so this floor must not invent a glyph set.
    // What it CAN do faithfully is carry the name, the size and the tone through, and get the
    // accessibility right - which is the half a placeholder box would silently drop.
    //
    // Accessibility mirrors the HTML hosts exactly: no `label` means DECORATIVE, so the node is
    // cleared from the semantics tree rather than read out as its internal glyph name; a labelled
    // icon carries the label instead. An icon announced as "sparkles" is worse than one announced
    // as nothing at all.
    val px = when (k.size) {
        IconSize.Small -> 12.sp
        IconSize.Medium -> 16.sp
        IconSize.Large -> 22.sp
    }
    Text(
        text = k.icon,
        fontSize = px,
        color = tone(k.tone).accent,
        modifier = Modifier
            .padding(2.dp)
            .semantics {
                if (k.label != null) {
                    contentDescription = k.label
                    role = Role.Image
                } else {
                    hideFromAccessibility()
                }
            },
    )
}

@Composable
private fun RenderLink(k: Link, ctx: BindingContext) {
    Text(ctx.resolveText(k.label), color = Color(0xFF1565C0), modifier = Modifier.padding(2.dp))
}

@Composable
private fun RenderImage(k: Image, ctx: BindingContext) {
    // Render floor has no network image loader — a labelled placeholder box (visible, non-fallback).
    CBox(
        Modifier
            .size(72.dp)
            .background(Color(0xFFF0F0F0), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(ctx.resolveText(k.alt).ifEmpty { "image" }, fontSize = 10.sp, color = Color.Gray)
    }
}

@Composable
private fun RenderList(k: ListNode, ctx: BindingContext) {
    Column {
        k.items.forEachIndexed { i, item ->
            val marker = if (k.ordered) "${i + 1}. " else "• "
            Text(marker + ctx.resolveText(item))
        }
    }
}

@Composable
private fun RenderToast(k: Toast, ctx: BindingContext) {
    val swatch = tone(k.tone)
    CBox(Modifier.background(swatch.container, RoundedCornerShape(6.dp)).padding(4.dp)) {
        Text(ctx.resolveText(k.message), color = swatch.onContainer, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun RenderCodeBlock(k: CodeBlock) {
    CBox(Modifier.background(Color(0xFFF5F5F5), RoundedCornerShape(4.dp)).padding(8.dp)) {
        Text(k.code, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun RenderDrawing(k: Drawing, ctx: BindingContext) {
    Card(Modifier.padding(2.dp)) {
        Column(Modifier.padding(8.dp)) {
            k.title?.let { Text(ctx.resolveText(it), fontWeight = FontWeight.SemiBold) }
            Text("Drawing · ${k.shapes.size} shape(s)", fontSize = 12.sp, color = Color.Gray)
            k.shapes.filterIsInstance<LabelShape>().forEach { Text(ctx.resolveText(it.text), fontSize = 12.sp) }
        }
    }
}

// --------------------------------------------------------------------------- //
// Input (render-only — interaction is Phase 545)
// --------------------------------------------------------------------------- //

@Composable
private fun RenderForm(k: Form, ctx: BindingContext) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(4.dp)) {
        k.fields.forEach { RenderFormField(it, ctx) }
        M3Button(onClick = {}, enabled = !ctx.resolveBool(k.disabled)) { Text(ctx.resolveText(k.submitLabel)) }
    }
}

@Composable
private fun RenderFormField(field: FormField, ctx: BindingContext) {
    val label = ctx.resolveText(field.label) + if (field.required) " *" else ""
    // Write-back (Phase 545): a state-backed field edit writes through the session's $state channel
    // when a live host is present; the session (Rust validator) is the authority on acceptance.
    val sink = LocalActionSink.current
    when (val kind = field.kind) {
        is TextField -> {
            var v by remember { mutableStateOf(ctx.resolve(kind.value)) }
            val key = stateKeyOf(kind.value)
            OutlinedTextField(
                value = v,
                onValueChange = { v = it; if (key != null) sink?.writeBack(key, it) },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is NumberField -> {
            var v by remember { mutableStateOf(ctx.resolve(kind.value)) }
            val key = stateKeyOf(kind.value)
            OutlinedTextField(
                value = v,
                onValueChange = { v = it; if (key != null) sink?.writeBack(key, it) },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is TextAreaField -> {
            var v by remember { mutableStateOf(ctx.resolve(kind.value)) }
            val key = stateKeyOf(kind.value)
            OutlinedTextField(
                value = v,
                onValueChange = { v = it; if (key != null) sink?.writeBack(key, it) },
                label = { Text(label) },
                minLines = kind.rows.coerceIn(1, 12),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is CheckboxField -> {
            var checked by remember { mutableStateOf(ctx.resolveBool(kind.value)) }
            val key = stateKeyOf(kind.value)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { checked = it; if (key != null) sink?.writeBack(key, it) })
                Text(label)
            }
        }
        is ToggleField -> {
            // The switch affordance over the same boolean slot as CheckboxField. Write-back is
            // wired for the same reason it is there: the control is genuinely interactive, so
            // omitting the sink would leave a switch that moves and changes nothing.
            var on by remember { mutableStateOf(ctx.resolveBool(kind.value)) }
            val key = stateKeyOf(kind.value)
            Row(verticalAlignment = Alignment.CenterVertically) {
                M3Switch(checked = on, onCheckedChange = { on = it; if (key != null) sink?.writeBack(key, it) })
                Text(label)
            }
        }
        is ChoiceField -> {
            // NON-WRITABLE BY CONSTRUCTION (Phase 667 audit). This arm is `readOnly`, so
            // `onValueChange` never fires from user input — there is no dropdown to pick from.
            // Wiring `writeBack` here would be DEAD CODE that merely looked like a fix; the real
            // remedy is a genuine choice control (an ExposedDropdownMenuBox), which is renderer
            // feature work rather than closing a write-back gap. Recorded here so the next reader
            // sees a decision, not an omission.
            val v = ctx.resolve(kind.value)
            OutlinedTextField(value = v, onValueChange = {}, label = { Text(label) }, readOnly = true, modifier = Modifier.fillMaxWidth())
        }
        is SegmentedChoiceField -> {
            val selected = ctx.resolve(kind.value)
            val key = stateKeyOf(kind.value)
            val options = ctx.resolve(kind.options).split(",").map { it.trim() }.filter { it.isNotEmpty() }
            Column {
                Text(label, fontSize = 12.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.ifEmpty { listOf(selected.ifEmpty { "—" }) }.forEach { opt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = opt == selected,
                                onClick = { if (key != null) sink?.writeBack(key, opt) },
                            )
                            Text(opt)
                        }
                    }
                }
            }
        }
        is RangedNumberField -> {
            var v by remember { mutableStateOf(ctx.resolveFloat(kind.value, (kind.min ?: 0.0).toFloat())) }
            val key = stateKeyOf(kind.value)
            Column {
                Text("$label: $v", fontSize = 12.sp)
                Slider(
                    value = v,
                    onValueChange = { v = it; if (key != null) sink?.writeBack(key, it.toDouble()) },
                    valueRange = (kind.min ?: 0.0).toFloat()..(kind.max ?: 100.0).toFloat(),
                )
            }
        }
        is RangeField -> {
            // The dual-thumb pair (0.2.0). Floor: render the bound {min,max} pair as a read-only
            // summary + a slider over the span midpoint (a full dual-thumb control is a later pass).
            // NON-WRITABLE BY CONSTRUCTION (Phase 667 audit): the slider shows a midpoint, not the
            // pair, so committing its value would write a number into a {min,max} slot — worse than
            // writing nothing. The write-back lands with the real dual-thumb control.
            val pair = ctx.resolve(kind.value)
            Column {
                Text("$label: ${pair.ifEmpty { "—" }}", fontSize = 12.sp)
                Slider(
                    value = ((kind.min ?: 0.0).toFloat() + (kind.max ?: 100.0).toFloat()) / 2f,
                    onValueChange = {},
                    valueRange = (kind.min ?: 0.0).toFloat()..(kind.max ?: 100.0).toFloat(),
                )
            }
        }
        is DateField -> {
            var v by remember { mutableStateOf(ctx.resolve(kind.value)) }
            val key = stateKeyOf(kind.value)
            OutlinedTextField(
                value = v,
                onValueChange = { v = it; if (key != null) sink?.writeBack(key, it) },
                label = { Text("$label (${kind.variant})") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 0.7.0 — the single-control date range. The structural intent that carries
        // over from the reference renderer is ONE pair with ONE write-back, not two
        // coordinated DateFields — so this is a single field over the resolved
        // "from – to" pair rather than two independent controls.
        is DateRangeField -> {
            var v by remember { mutableStateOf(ctx.resolve(kind.value)) }
            val key = stateKeyOf(kind.value)
            OutlinedTextField(
                value = v,
                onValueChange = { v = it; if (key != null) sink?.writeBack(key, it) },
                label = { Text("$label (${kind.variant} range)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RenderButton(k: Button, ctx: BindingContext) {
    val enabled = !ctx.resolveBool(k.disabled)
    val label = ctx.resolveText(k.label)
    // Interaction round-trip (Phase 545): dispatch onClick through the live host when one is present;
    // inert (the pre-545 static-render behaviour) when LocalActionSink is null.
    val sink = LocalActionSink.current
    val onClick: () -> Unit = { sink?.dispatch(k.onClick) }
    when (k.variant) {
        ButtonVariant.Primary, ButtonVariant.Destructive -> M3Button(onClick = onClick, enabled = enabled) { Text(label) }
        ButtonVariant.Secondary -> OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
        ButtonVariant.Tertiary -> TextButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}

@Composable
private fun RenderSelect(k: Select, ctx: BindingContext) {
    val display = k.value?.let { ctx.resolve(it) }.orEmpty().ifEmpty { k.placeholder?.let { ctx.resolveText(it) }.orEmpty() }
    OutlinedTextField(
        value = display,
        onValueChange = {},
        label = { Text(ctx.resolveText(k.label)) },
        readOnly = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RenderFilters(k: Filters, ctx: BindingContext) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        k.items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ctx.resolveText(item.label) + ": ", fontSize = 12.sp, color = Color.Gray)
                Text(item.name, fontSize = 12.sp)
            }
        }
    }
}

// --------------------------------------------------------------------------- //
// Visualisation
// --------------------------------------------------------------------------- //

@Composable
private fun RenderDataGrid(k: DataGrid, nodeId: String, ctx: BindingContext) {
    Column(Modifier.border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(4.dp)).padding(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            k.columns.forEach { col -> Text(col.label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
        }
        HorizontalDivider()
        val static = k.staticRows
        if (static != null) {
            static.rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { cell -> Text(ctx.resolveText(cell), fontSize = 12.sp) }
                }
            }
        } else {
            // Phase 753 — data-bound rows, seeded by the host from the core's resolved-rows
            // hand-off. The outcomes render differently ON PURPOSE: an unresolved source is
            // not an empty grid, and showing "no data" for "not yet" is the failure this seam
            // exists to prevent.
            when (val resolved = ctx.rowsFor(nodeId)) {
                is ResolvedRows.Rows ->
                    if (resolved.rows.isEmpty()) {
                        Text("No rows", fontSize = 11.sp, color = Color.Gray)
                    } else {
                        resolved.rows.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                k.columns.forEach { col -> RenderGridCell(col, row, ctx) }
                            }
                        }
                    }
                ResolvedRows.NotResolved -> Text("Loading…", fontSize = 11.sp, color = Color.Gray)
                ResolvedRows.NoRowSource -> Text("(no row source)", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

/**
 * One grid cell: the column's kind decides the rendering, the row supplies the datum. An
 * **exhaustive `when` with no `else`**, so a new cell kind is a compile error here rather than
 * a silently blank cell.
 *
 * Closure-bearing kinds render their decoded-path floor (an unchecked box, a destination-less
 * link, a zero progress bar) rather than promising an interaction the wire cannot carry.
 */
@Composable
private fun RenderGridCell(col: GridColumn, row: JsonValue, ctx: BindingContext) {
    val value = col.field?.let { formatCellValue(projectRowFieldString(row, it), col.format) } ?: ""
    when (val kind = col.kind) {
        TextCell, NumericCell, DateCell -> Text(value, fontSize = 12.sp)
        // Inert: the write-back seam is a separate concern, so the value shows but does not
        // commit. An editable-looking control would promise an interaction that is not wired.
        EditableCell ->
            Text(
                value,
                fontSize = 12.sp,
                modifier =
                    Modifier.border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        // The `get` accessor is a closure, so a decoded tree carries no state: unchecked,
        // matching the other hosts' decoded-path floor.
        CheckboxCell -> Checkbox(checked = false, onCheckedChange = null)
        is ButtonCell -> TextButton(onClick = {}) { Text(ctx.resolveText(kind.label), fontSize = 12.sp) }
        is ButtonGroupCell ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                kind.labels.forEach { label -> TextButton(onClick = {}) { Text(ctx.resolveText(label), fontSize = 12.sp) } }
            }
        // `hrefFn` is a closure and never rides the wire, so the projected value is shown
        // link-styled without a destination — visible, not clickable.
        LinkCell -> Text(value, fontSize = 12.sp, color = tone(ToneVariant.Brand).accent, textDecoration = TextDecoration.Underline)
        // The closure pill: its tone is `(row) -> ToneVariant`, which the wire cannot carry, so
        // every row takes the default tone. That flatness is exactly the gap TonedPill closes.
        PillCell -> PillChip(value, tone(ToneVariant.Default))
        is TonedPillCell -> {
            // Phase 750 — the declarative twin. Deliberately the SAME chip as the closure arm
            // above: the wire variant exists to make the tone rule expressible, not to render
            // differently.
            val (label, pillTone) = tonedPillOf(row, kind.field, kind.map, kind.defaultTone)
            PillChip(label, tone(pillTone))
        }
        // The fraction accessor is a closure; a decoded tree reads 0.
        ProgressCell -> LinearProgressIndicator(progress = { 0f }, modifier = Modifier.width(48.dp))
        CustomCell -> Text("—", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
private fun PillChip(label: String, swatch: ToneSwatch) {
    CBox(
        Modifier
            .background(swatch.container, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, fontSize = 12.sp, color = swatch.onContainer)
    }
}

@Composable
private fun RenderChart(k: Chart, ctx: BindingContext) {
    Card(Modifier.padding(2.dp)) {
        Column(Modifier.padding(8.dp)) {
            k.title?.let { Text(ctx.resolveText(it), fontWeight = FontWeight.SemiBold) }
            Text("${k.kind} chart · ${k.xField} × ${k.yFields.joinToString(",")}", fontSize = 12.sp, color = Color.Gray)
        }
    }
}
