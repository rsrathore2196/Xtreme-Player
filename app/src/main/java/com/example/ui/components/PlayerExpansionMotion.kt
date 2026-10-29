package com.example.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal val LocalPlayerExpansionMotion = staticCompositionLocalOf<PlayerExpansionMotion?> { null }
internal enum class PlayerMorphSide { MINI, EXPANDED }
internal enum class PlayerMorphPart { SURFACE, ARTWORK, TITLE, ARTIST, PLAY_PAUSE, NEXT, EQUALIZER }

/** Playback stays live; both visual endpoints keep one recording until handoff. */
@Composable
internal fun <T> rememberPlayerHandoff(value: T, visibility: MutableTransitionState<Boolean>): T {
    var settled by remember { mutableStateOf(value) }
    SideEffect { if (visibility.isIdle) settled = value }
    return if (visibility.isIdle) value else settled
}

/** A retained display list, never a screen bitmap or another artwork request. */
internal class PlayerMorphCapture(
    val context: GraphicsContext, val layer: GraphicsLayer,
    val side: PlayerMorphSide, val part: PlayerMorphPart
) {
    var bounds: Rect? = null
    var radius = 0f
    var presentation = 0
    var recordedPresentation = 0
    var active = true
    var key = ""
    var recordedKey: String? = null
    var ready = false
    var image: ImageBitmap? = null
    var direction = LayoutDirection.Ltr
    var detached = false
    private var released = false
    fun release() {
        if (!released) { released = true; context.releaseGraphicsLayer(layer) }
    }
}

/** Piecewise geometry preserves position when an inset, font scale or real anchor changes.
 * Reversals traverse the same path, including its measured rebase point. */
internal class PlayerMorphBoundsPath {
    private var mini: Rect? = null
    private var expanded: Rect? = null
    private var pivot: Rect? = null
    private var pivotFraction = 0f
    fun value(fraction: Float, miniBounds: Rect, expandedBounds: Rect): Rect {
        val p = fraction.coerceIn(0f, 1f)
        if (mini != miniBounds || expanded != expandedBounds) {
            val previous = if (mini != null && expanded != null) sample(p) else null
            mini = miniBounds; expanded = expandedBounds
            pivot = previous?.takeIf { p > .001f && p < .999f }
            pivotFraction = p
        }
        return sample(p)
    }
    private fun sample(p: Float): Rect {
        val start = checkNotNull(mini); val end = checkNotNull(expanded)
        val middle = pivot ?: return androidx.compose.ui.geometry.lerp(start, end, p)
        return if (p <= pivotFraction) androidx.compose.ui.geometry.lerp(start, middle, p / pivotFraction)
        else androidx.compose.ui.geometry.lerp(middle, end, (p - pivotFraction) / (1f - pivotFraction))
    }
}

private class PlayerMorphSession(val key: String) {
    val nodes = mutableMapOf<Pair<PlayerMorphSide, PlayerMorphPart>, PlayerMorphCapture>()
    val paths = PlayerMorphPart.entries.associateWith { PlayerMorphBoundsPath() }
}

/** One clock owns surface clipping, artwork, metadata and control handoff. */
@Stable
internal class PlayerExpansionMotion {
    private var animation by mutableStateOf<State<Float>>(mutableStateOf(1f))
    private var visibility by mutableStateOf<MutableTransitionState<Boolean>?>(null)
    private var revision by mutableIntStateOf(0)
    private val captures = mutableListOf<PlayerMorphCapture>()
    private var session: PlayerMorphSession? = null
    private var containerPath = PlayerMorphBoundsPath()
    private val artworkBlendPaint = Paint()
    var viewport by mutableStateOf<Rect?>(null)
        internal set
    var trackId = ""
    val fraction: Float get() = animation.value.coerceIn(0f, 1f)
    val expandedFraction: Float get() = 1f - fraction
    val transitioning: Boolean get() = visibility?.isIdle == false
    val miniInteractive: Boolean get() = visibility?.let { it.isIdle && !it.currentState && !it.targetState } ?: true
    val expandedInteractive: Boolean get() = visibility?.targetState == true && expandedFraction >= .8f
    val covered: Boolean get() = !miniInteractive
    val miniBounds: Rect? get() = best(PlayerMorphSide.MINI, PlayerMorphPart.SURFACE, requireReady = false)?.bounds

    fun bind(value: State<Float>, state: MutableTransitionState<Boolean>? = null) {
        animation = value
        visibility = state
        if (state?.isIdle == true) finishSession()
    }
    internal fun register(capture: PlayerMorphCapture) { captures.add(capture); invalidate() }
    internal fun unregister(capture: PlayerMorphCapture) {
        captures.remove(capture)
        capture.detached = true
        if (!sessionOwns(capture)) capture.release()
        invalidate()
    }
    internal fun invalidate() { revision++ }
    private fun sessionOwns(capture: PlayerMorphCapture) = session?.nodes?.values?.any { it === capture } == true
    // A successful image can still be inside Coil's fade-in. Honor its own draw
    // invalidations while hidden; freezing that display list can stall the painter
    // on an empty frame until the mini becomes visible again. No frame ticker or
    // new image request is needed, and metadata keeps its retained display lists.
    internal fun frozen(capture: PlayerMorphCapture) =
        !(capture.side == PlayerMorphSide.MINI && capture.part == PlayerMorphPart.ARTWORK) &&
        capture.ready && capture.presentation == capture.recordedPresentation && transitioning && sessionOwns(capture)
    internal fun hides(capture: PlayerMorphCapture): Boolean = viewport != null && covered &&
        (capture.side == PlayerMorphSide.MINI || transitioning)

    private fun best(side: PlayerMorphSide, part: PlayerMorphPart, key: String? = null,
        requireReady: Boolean = true): PlayerMorphCapture? =
        captures.lastOrNull { it.side == side && it.part == part && it.active && (!requireReady || it.ready) &&
            it.bounds.valid() && (key == null || it.recordedKey == key) }

    private fun snapshot(): PlayerMorphSession {
        // Observed by the overlay's draw node only, not by the playback/Home composition.
        @Suppress("UNUSED_VARIABLE") val geometryRevision = revision
        val current = session ?: PlayerMorphSession(trackId.ifBlank {
            best(PlayerMorphSide.EXPANDED, PlayerMorphPart.TITLE)?.recordedKey
                ?: best(PlayerMorphSide.MINI, PlayerMorphPart.SURFACE)?.recordedKey.orEmpty()
        }).also { session = it }
        for (side in PlayerMorphSide.entries) for (part in PlayerMorphPart.entries) {
            val id = side to part
            val previous = current.nodes[id]
            if (previous == null || previous.detached || !previous.active) {
                best(side, part, current.key, requireReady = part != PlayerMorphPart.ARTWORK)?.let { replacement ->
                    current.nodes[id] = replacement
                    if (previous !== replacement && previous?.detached == true) previous.release()
                }
            }
        }
        return current
    }
    internal fun finishSession() {
        session?.nodes?.values?.distinct()?.filter { it.detached }?.forEach { it.release() }
        session = null
        containerPath = PlayerMorphBoundsPath()
    }
    internal fun containerBounds(fallback: Rect? = null): Rect? {
        val full = viewport ?: return null
        // A restored player may precede its mini's first placement. Stay at the
        // measured viewport until an actual source arrives; never invent a dock position.
        val mini = miniBounds ?: fallback?.takeIf { it.valid() } ?: full
        return containerPath.value(expandedFraction, mini, full)
    }
    internal fun surfaceAlpha() = smooth(expandedFraction / .22f)
    internal fun controlsAlpha() = smooth((expandedFraction - .22f) / .6f)
    internal fun miniControlsAlpha() = 1f - smooth(expandedFraction / .24f)

    internal fun DrawScope.drawMiniSurface(fallback: Rect?) {
        if (!transitioning) return
        val source = best(PlayerMorphSide.MINI, PlayerMorphPart.SURFACE) ?: return
        val bounds = containerBounds(fallback) ?: return
        drawCapture(source, bounds.local(), 1f - surfaceAlpha(), crop = false)
    }
    internal fun DrawScope.drawSharedElements() {
        if (!transitioning || viewport == null) return
        val current = snapshot()
        val p = expandedFraction
        val miniArt = current.nodes[PlayerMorphSide.MINI to PlayerMorphPart.ARTWORK]
        val expandedArt = current.nodes[PlayerMorphSide.EXPANDED to PlayerMorphPart.ARTWORK]
        val artStart = miniArt?.bounds ?: expandedArt?.bounds
        val artEnd = expandedArt?.bounds ?: artStart
        val artBounds = if (artStart != null && artEnd != null)
            current.paths.getValue(PlayerMorphPart.ARTWORK).value(p, artStart, artEnd).local() else null
        for (part in listOf(PlayerMorphPart.ARTWORK, PlayerMorphPart.TITLE, PlayerMorphPart.ARTIST)) {
            val mini = current.nodes[PlayerMorphSide.MINI to part]
            val expanded = current.nodes[PlayerMorphSide.EXPANDED to part]
            if (mini == null && expanded == null) continue
            val start = mini?.bounds?.takeIf { it.valid() } ?: expanded?.bounds ?: continue
            val end = expanded?.bounds?.takeIf { it.valid() } ?: start
            val target = current.paths.getValue(part).value(p, start, end).local()
            if (part == PlayerMorphPart.ARTWORK) {
                val miniImage = mini?.takeIf { it.ready }
                val expandedImage = expanded?.takeIf { it.ready }
                val radius = (mini?.radius ?: 0f) * (1f - p) + (expanded?.radius ?: mini?.radius ?: 0f) * p
                val outline = Path().apply { addRoundRect(RoundRect(target, CornerRadius(radius.coerceAtLeast(0f)))) }
                clipPath(outline) {
                    // Prefer the already decoded full-size display list throughout.
                    // Different faces (credits/fullscreen edge mask) blend inside this
                    // ONE moving artwork viewport instead of showing two artworks.
                    if (expandedImage != null && (miniImage == null || miniImage.presentation == expandedImage.presentation))
                        drawCapture(expandedImage, target, 1f, crop = true)
                    else if (miniImage != null && expandedImage != null) {
                        // Add premultiplied contributions in a bounded transparent
                        // layer so the two faces don't darken or reveal a gap.
                        drawContext.canvas.saveLayer(target, artworkBlendPaint)
                        drawCapture(miniImage, target, 1f - smooth(p), crop = true)
                        drawCapture(expandedImage, target, smooth(p), crop = true, blend = BlendMode.Plus)
                        drawContext.canvas.restore()
                    } else {
                        (expandedImage ?: miniImage)?.let { drawCapture(it, target, 1f, crop = true) }
                    }
                }
            } else {
                val blend = smooth((p - .3f) / .4f)
                // Horizontal mini metadata becomes vertical metadata in portrait.
                // Fade through the small occlusion interval instead of painting
                // moving letters over the shared artwork or changing either layout.
                val clearance = if (artBounds != null && miniArt?.bounds != null && mini?.bounds != null) {
                    val originalGap = max(mini.bounds!!.left - miniArt.bounds!!.right,
                        mini.bounds!!.top - miniArt.bounds!!.bottom).coerceAtLeast(1f)
                    val separation = max(max(target.left - artBounds.right, artBounds.left - target.right),
                        max(target.top - artBounds.bottom, artBounds.top - target.bottom))
                    smooth(separation / originalGap)
                } else 1f
                clipRect(target.left, target.top, target.right, target.bottom) {
                    mini?.let { drawCapture(it, target, clearance * if (expanded == null) 1f else 1f - blend, crop = null) }
                    expanded?.let { drawCapture(it, target, clearance * if (mini == null) controlsAlpha() else blend, crop = null) }
                }
            }
        }
        // Controls sit above the contracting surface, not behind its glass.
        // These are the original button display lists at their measured positions.
        for (part in listOf(PlayerMorphPart.PLAY_PAUSE, PlayerMorphPart.NEXT, PlayerMorphPart.EQUALIZER)) {
            current.nodes[PlayerMorphSide.MINI to part]?.let { control ->
                control.bounds?.let { drawCapture(control, it.local(), miniControlsAlpha(), crop = false) }
            }
        }
    }
    private fun Rect.local() = translate(-(viewport?.topLeft ?: Offset.Zero))
    private fun DrawScope.drawCapture(capture: PlayerMorphCapture, target: Rect, opacity: Float, crop: Boolean?,
        blend: BlendMode = BlendMode.SrcOver) {
        val size = capture.layer.size
        if (opacity <= 0f || size.width <= 0 || size.height <= 0 || !target.valid()) return
        val sx: Float; val sy: Float; val dx: Float; val dy: Float
        if (crop == false) {
            sx = target.width / size.width; sy = target.height / size.height; dx = 0f; dy = 0f
        } else {
            sx = if (crop == true) max(target.width / size.width, target.height / size.height) else target.height / size.height
            sy = sx
            dx = if (crop == true) (target.width - size.width * sx) / 2f
                else if (capture.direction == LayoutDirection.Rtl) target.width - size.width * sx else 0f
            dy = if (crop == true) (target.height - size.height * sy) / 2f else 0f
        }
        capture.layer.alpha = opacity.coerceIn(0f, 1f)
        capture.layer.blendMode = blend
        withTransform({ translate(target.left + dx, target.top + dy); scale(sx, sy, Offset.Zero) }) {
            val image = capture.image
            if (image != null && capture.part == PlayerMorphPart.ARTWORK) {
                // Coil's successful painter can still be fading from transparency.
                // Reuse its decoded bitmap for the shared image, at the SAME crop,
                // opacity and bounds; the normal painter keeps its existing fade.
                val side = minOf(image.width, image.height)
                drawImage(image, srcOffset = IntOffset((image.width - side) / 2, (image.height - side) / 2),
                    srcSize = IntSize(side, side), dstSize = size, alpha = opacity.coerceIn(0f, 1f), blendMode = blend)
            } else drawLayer(capture.layer)
        }
    }
}

private fun Rect?.valid() = this != null && width.isFinite() && height.isFinite() &&
    left.isFinite() && top.isFinite() && width > 0f && height > 0f
private fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

/** Capture the original component once per real content invalidation. Progress is
 * read only in layers/the overlay; image decoding and text layout never run per frame. */
@Composable
internal fun Modifier.playerMorphElement(
    side: PlayerMorphSide, part: PlayerMorphPart, key: String,
    radius: Dp = 0.dp, active: Boolean = true, presentation: Int = 0, ready: Boolean = true,
    radiusPixels: (() -> Float)? = null, image: ImageBitmap? = null
): Modifier {
    val motion = LocalPlayerExpansionMotion.current ?: return this
    val graphics = LocalGraphicsContext.current
    val direction = LocalLayoutDirection.current
    val imageReady = rememberUpdatedState(ready)
    val capture = remember(motion, graphics, side, part) {
        PlayerMorphCapture(graphics, graphics.createGraphicsLayer(), side, part)
    }
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { radius.toPx() }
    DisposableEffect(capture) {
        motion.register(capture)
        onDispose { motion.unregister(capture) }
    }
    SideEffect {
        capture.key = key; capture.radius = radiusPx; capture.active = active
        capture.presentation = presentation; capture.direction = direction
        if (capture.image !== image) { capture.image = image; motion.invalidate() }
    }
    return onGloballyPositioned {
        val bounds = it.boundsInRoot()
        if (capture.bounds != bounds) { capture.bounds = bounds; motion.invalidate() }
    }.drawWithContent {
        val contentReady = imageReady.value
        radiusPixels?.let { capture.radius = it() }
        if (!motion.frozen(capture)) {
            capture.layer.alpha = 1f
            capture.layer.blendMode = BlendMode.SrcOver
            capture.layer.record(IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))) {
                this@drawWithContent.drawContent()
            }
            val changed = capture.recordedKey != key || capture.ready != contentReady
            capture.recordedKey = key; capture.ready = contentReady
            capture.recordedPresentation = capture.presentation
            if (changed) motion.invalidate()
        }
        if (!motion.hides(capture)) {
            capture.layer.alpha = 1f; capture.layer.blendMode = BlendMode.SrcOver
            drawLayer(capture.layer)
        }
    }
}

internal fun Modifier.playerMiniTransition(motion: PlayerExpansionMotion): Modifier = graphicsLayer {
    alpha = if (motion.covered && !motion.transitioning) 0f else 1f
}
internal fun Modifier.playerDockTransition(motion: PlayerExpansionMotion): Modifier = graphicsLayer {
    alpha = 1f - smooth(motion.expandedFraction / .28f)
}
@Composable
internal fun Modifier.playerMorphMiniControls(part: PlayerMorphPart, key: String): Modifier {
    val motion = LocalPlayerExpansionMotion.current ?: return this
    return graphicsLayer { alpha = motion.miniControlsAlpha() }
        .playerMorphElement(PlayerMorphSide.MINI, part, key)
}
@Composable
internal fun Modifier.playerMorphExpandedControls(): Modifier {
    val motion = LocalPlayerExpansionMotion.current ?: return this
    return graphicsLayer { alpha = motion.controlsAlpha() }
}

private class PlayerContainerOutline(private val motion: PlayerExpansionMotion, private val mini: () -> Rect?) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val viewport = motion.viewport ?: Rect(Offset.Zero, size)
        val rect = (motion.containerBounds(mini()) ?: viewport).translate(-viewport.topLeft)
        val radius = min((motion.miniBounds ?: mini() ?: viewport).height / 2f, rect.minDimension / 2f) * motion.fraction
        return Outline.Rounded(RoundRect(rect, CornerRadius(radius.coerceAtLeast(0f))))
    }
}

/** Fixed endpoint layout, independently clipped container, and one shared element overlay.
 * Retaining the scene after its first opening preserves lyrics/flip state while a
 * CREATED lifecycle and an unplaced layer stop hidden effects, input and collectors. */
@Composable
internal fun PlayerExpansionOverlay(
    visibleState: MutableTransitionState<Boolean>, motion: PlayerExpansionMotion,
    miniPlayerBounds: () -> Rect?, modifier: Modifier = Modifier,
    onReopen: () -> Unit = { visibleState.targetState = true }, content: @Composable () -> Unit
) {
    DisposableEffect(motion) { onDispose { motion.finishSession() } }
    val transition = rememberTransition(visibleState, label = "player_container_morph")
    val collapse = transition.animateFloat(
        // Critical damping settles a full stroke in about 520ms. Unlike a tween,
        // interrupted springs retain velocity without a replacement/bouncy spec.
        transitionSpec = { spring(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = .001f) },
        label = "player_expansion_bounds"
    ) { if (it) 0f else 1f }
    SideEffect { motion.bind(collapse, visibleState) }
    var mounted by remember { mutableStateOf(visibleState.currentState || visibleState.targetState) }
    if (visibleState.targetState && !mounted) SideEffect { mounted = true }
    val placed = visibleState.currentState || visibleState.targetState || !visibleState.isIdle
    if (!mounted && !placed) return
    val interactive by remember(motion) { derivedStateOf { motion.expandedInteractive } }
    val parent = LocalLifecycleOwner.current.lifecycle
    val lifecycle = remember(parent) { PlayerSceneLifecycle(parent) }
    DisposableEffect(parent, lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> lifecycle.update() }
        parent.addObserver(observer)
        onDispose { parent.removeObserver(observer); lifecycle.destroy() }
    }
    SideEffect { lifecycle.visible = placed; lifecycle.update() }
    val shape = remember(motion) { PlayerContainerOutline(motion, miniPlayerBounds) }
    CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalPlayerExpansionMotion provides motion,
        LocalVisualEffectsActive provides (placed && LocalVisualEffectsActive.current)) {
        Layout(modifier = modifier.fillMaxSize().onGloballyPositioned {
            val bounds = it.boundsInRoot()
            if (motion.viewport != bounds) motion.viewport = bounds
        }, content = {
            Box(Modifier.fillMaxSize()
                .then(if (!interactive) Modifier.clearAndSetSemantics { } else Modifier)
                .pointerInput(interactive, visibleState.targetState) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val rootPoint = down.position + (motion.viewport?.topLeft ?: Offset.Zero)
                        val blocked = !interactive || motion.containerBounds(miniPlayerBounds())?.contains(rootPoint) != true
                        val beganClosing = !visibleState.targetState
                        if (blocked) down.consume()
                        var moved = false
                        var released = false
                        while (!released) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (blocked) event.changes.forEach { it.consume() }
                            released = change == null || !change.pressed
                        }
                        if (blocked && beganClosing && !moved && !visibleState.targetState) {
                            if (motion.containerBounds(miniPlayerBounds())?.contains(rootPoint) == true) onReopen()
                        }
                    }
                }) {
                Box(Modifier.fillMaxSize().graphicsLayer { this.shape = shape; clip = true }
                    .testTag("expanded_player_transition")) {
                    Canvas(Modifier.fillMaxSize()) { with(motion) { drawMiniSurface(miniPlayerBounds()) } }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = motion.surfaceAlpha() }) { content() }
                    Canvas(Modifier.fillMaxSize().graphicsLayer()) { with(motion) { drawSharedElements() } }
                }
            }
        }) { measurables, constraints ->
            val child = measurables.single().measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight))
            layout(child.width, child.height) { if (placed) child.place(0, 0) }
        }
    }
}

private class PlayerSceneLifecycle(private val parent: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    var visible = false
    override val lifecycle: Lifecycle get() = registry
    fun update() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        if (parent.currentState == Lifecycle.State.DESTROYED) { destroy(); return }
        registry.currentState = if (visible) parent.currentState else minOf(parent.currentState, Lifecycle.State.CREATED)
    }
    fun destroy() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
