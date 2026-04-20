package com.kevingraney.c47

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import android.view.HapticFeedbackConstants
import com.kevingraney.c47.R
import com.kevingraney.c47.engine.CalculatorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Palette
// ─────────────────────────────────────────────────────────────────────────────

private val OuterBg        = Color(0xFF080808)
private val BodyBg         = Color(0xFF1C1C1C)
private val DisplayFrameBg = Color(0xFF0E0E0E)

private val BtnFaceTop     = Color(0xFF3E3E3E)
private val BtnFaceBot     = Color(0xFF252525)
private val BtnDepth       = Color(0xFF0D0D0D)

private val BtnSkFaceTop   = Color(0xFF272727)
private val BtnSkFaceBot   = Color(0xFF181818)
private val BtnSkDepth     = Color(0xFF070707)

private val BtnOrgFaceTop  = Color(0xFFE88A26)
private val BtnOrgFaceBot  = Color(0xFFAF5C00)
private val BtnOrgDepth    = Color(0xFF703700)

private val BtnBluFaceTop  = Color(0xFF4E8AE4)
private val BtnBluFaceBot  = Color(0xFF2A5AB6)
private val BtnBluDepth    = Color(0xFF143470)

private val LabelWhite     = Color(0xFFFFFFFF)
private val ShiftOrange    = Color(0xFFE08020)
private val ShiftBlue      = Color(0xFF40A0C0)
private val CornerChar     = Color(0xFF787878)
private val BoxBorder      = Color(0xFFBEBEBE)
private val BoxBorderFill  = Color(0xFFE08020)

enum class BtnVariant { Normal, Softkey, Orange, Blue }

// ─────────────────────────────────────────────────────────────────────────────
// Fonts — vendored from c43-source/res/fonts. The TTFs use a custom 16-bit
// PUA encoding (see c43-source/src/c47/fonts.h): bytes \xXX\xYY in the C
// `#define STD_*` macros map to codepoint U+XXYY in the font's cmap, which
// Compose can address directly via "\uXXYY" string literals.
// ─────────────────────────────────────────────────────────────────────────────

private val C47Standard = FontFamily(Font(R.font.c47_standard))
private val C47Tiny     = FontFamily(Font(R.font.c47_tiny))

// ─────────────────────────────────────────────────────────────────────────────
// Root screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CalculatorScreen(vm: CalculatorViewModel? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OuterBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(BodyBg)
                .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 16.dp)
        ) {
            CalcHeader()
            Spacer(Modifier.height(4.dp))
            CalcDisplay(vm)
            Spacer(Modifier.height(10.dp))
            CalcKeyboard(vm)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CalcHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "SwissMicros",
            color = LabelWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            "R47",
            color = LabelWhite,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Display  (reuses the custom-View canvas drawing)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CalcDisplay(vm: CalculatorViewModel? = null) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .background(DisplayFrameBg, RoundedCornerShape(3.dp))
            .padding(4.dp)
    ) {
        if (vm != null) {
            // Vsync-aligned pump: LaunchedEffect runs at the display's native
            // refresh rate (60/90/120 Hz). pumpFrame is cheap when the engine
            // hasn't drawn anything new — just checks a dirty flag natively.
            LaunchedEffect(vm) {
                while (true) {
                    withFrameNanos { }
                    vm.pumpFrame()
                }
            }
            // Live engine framebuffer. The Bitmap is mutated in place each
            // dirty tick; `frame.version` changes force recomposition so
            // Compose re-reads the (now-updated) pixels. FilterQuality.None
            // keeps crisp LCD pixels when scaling from 400×240.
            val frame by vm.lcd.collectAsState()
            Image(
                bitmap = frame.bitmap.asImageBitmap(),
                contentDescription = "Calculator display",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        } else {
            // Preview / no-VM fallback: the hand-drawn mock from commit 984c97b.
            AndroidView(
                factory = { ctx -> CalculatorDisplayView(ctx) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Full keyboard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CalcKeyboard(vm: CalculatorViewModel? = null) {
    // remember(vm) so lambda identities are stable across CalcKeyboard
    // recompositions — otherwise every 43 Key composables' pointerInput
    // blocks would tear down + rebuild their gesture detectors (keyed on
    // these lambdas), briefly dropping in-flight touches.
    val onDown: (String) -> Unit = remember(vm) { { code -> vm?.onKeyDown(code) } }
    val onUp:   (String) -> Unit = remember(vm) { { code -> vm?.onKeyUp(code) } }

    Column(Modifier.fillMaxWidth()) {

        // ── Softkey row ──────────────────────────────────────────────────────
        // Softkey codes are single-char "1".."6"; determineFunctionKeyItem_C47
        // converts to 0..5 via `*(data) - '0' - 1` (keyboard.c:22).
        BtnRow(height = 32.dp) {
            for (c in 1..6) {
                Key("", v = BtnVariant.Softkey,
                    keyCode = "$c", onKeyDown = onDown, onKeyUp = onUp)
            }
        }

        // ── Row 1 ─ x², √x, 1/x, yˣ, LOG, LN ────────────────────────────
        // Data keys are 2-char flat indices "00".."36" into kbd_std_R47f_g
        // (assign.c:368). btnPressed parses via stringToKeyNumber at
        // keyboard.c:1440. Row 1 = entries 0..5.
        SRow {
            SMix2("i",             "\u2192R",  italic = true, fsize = 8)
            SMix2("i\u2080",       "\u2192P",  italic = true, fsize = 8)
            SMix2("x!",            "\u00B7ms", italic = true, fsize = 8)
            SMix2("\u02E3\u221Ay", "\u00B7d",  italic = true, fsize = 8)
            SMix2("10\u02E3",      "\u2192I",  italic = true, fsize = 8)
            SMix2("e\u02E3",       "#",        italic = true, fsize = 8)
        }
        BtnRow {
            Key("x\u00B2",   corner = "A", keyCode = "00", onKeyDown = onDown, onKeyUp = onUp)
            Key("\u221Ax",   corner = "B", keyCode = "01", onKeyDown = onDown, onKeyUp = onUp)
            Key("1/x",       corner = "C", keyCode = "02", onKeyDown = onDown, onKeyUp = onUp)
            Key("y\u02E3",   corner = "D", keyCode = "03", onKeyDown = onDown, onKeyUp = onUp)
            Key("LOG",       corner = "E", keyCode = "04", onKeyDown = onDown, onKeyUp = onUp)
            Key("LN",        corner = "F", keyCode = "05", onKeyDown = onDown, onKeyUp = onUp)
        }

        // ── Row 2 ─ STO, RCL, R↓, DRG, [orange], [blue] ─────────────────
        SRow {
            SMix2("|x|",      "\u25B3",       fsize = 8)
            SMix2("%",        "\u0394%",      fsize = 8)
            SMix2("\u03C0",   "R\u2191",      fsize = 8)
            SMix2("USER",     "ASN",          fsize = 8)
            SC("HOME",        LabelWhite)
            SC("CUST",        LabelWhite)
        }
        BtnRow {
            Key("STO",       corner = "G", keyCode = "06", onKeyDown = onDown, onKeyUp = onUp)
            Key("RCL",       corner = "H", keyCode = "07", onKeyDown = onDown, onKeyUp = onUp)
            Key("R\u2193",   corner = "I", keyCode = "08", onKeyDown = onDown, onKeyUp = onUp)
            Key("DRG",       corner = "J", keyCode = "09", onKeyDown = onDown, onKeyUp = onUp)
            Key("",          v = BtnVariant.Orange, keyCode = "10", onKeyDown = onDown, onKeyUp = onUp)
            Key("",          v = BtnVariant.Blue,   keyCode = "11", onKeyDown = onDown, onKeyUp = onUp)
        }

        // ── COMPLEX/ENTER row ─────────────────────────────────────────────
        // Labels align 1:1 with the button row below. ENTER spans 2 columns,
        // so "COMPLEX [CPX]" sits in a w=2 cell above it. Back (←) carries
        // both the orange ↵ glyph and the [CLR] box in one w=1 cell.
        SRow {
            SMC(w = 2f) { MixT("COMPLEX ", ShiftOrange); BoxT("CPX") }
            SMC(w = 1f) { MixT("LASTx ", ShiftOrange); BoxT("STK") }
            SMC(w = 1f) { BoxT2("DISP", "TRG") }
            SMC(w = 1f) { BoxT2("PFX", "EXP") }
            SMC(w = 1f) { MixT("\u21B5 ", ShiftOrange, fsize = 10); BoxT("CLR") }
        }
        BtnRow {
            Key("ENTER",     w = 2f, fsize = 16.sp, keyCode = "12", onKeyDown = onDown, onKeyUp = onUp)
            Key("x\u21C4y",  corner = "K", w = 1f, fsize = 12.sp, keyCode = "13", onKeyDown = onDown, onKeyUp = onUp)
            Key("CHS",       corner = "L", w = 1f, fsize = 14.sp, keyCode = "14", onKeyDown = onDown, onKeyUp = onUp)
            Key("EEX",       corner = "M", w = 1f, fsize = 14.sp, keyCode = "15", onKeyDown = onDown, onKeyUp = onUp)
            Key("\u2190",    w = 1f, fsize = 20.sp, keyCode = "16", onKeyDown = onDown, onKeyUp = onUp)
        }

        // ── α/GTO row ─────────────────────────────────────────────────────
        SRow {
            SMC(w = 1f) { BoxTFilled("\u03B1"); MixT(" GTO", ShiftBlue) }
            SMix2("SIN", "ASIN", w = 1f)
            SMix2("COS", "ACOS", w = 1f)
            SMix2("TAN", "ATAN", w = 1f)
            SMC(w = 1f) { BoxT2("STAT", "PLOT") }
        }
        BtnRow {
            Key("XEQ",       w = 1f, fsize = 16.sp, keyCode = "17", onKeyDown = onDown, onKeyUp = onUp)
            Key("7",         corner = "N", w = 1f, fsize = 18.sp, keyCode = "18", onKeyDown = onDown, onKeyUp = onUp)
            Key("8",         corner = "O", w = 1f, fsize = 18.sp, keyCode = "19", onKeyDown = onDown, onKeyUp = onUp)
            Key("9",         corner = "P", w = 1f, fsize = 18.sp, keyCode = "20", onKeyDown = onDown, onKeyUp = onUp)
            Key("\u00F7",    corner = "Q", w = 1f, fsize = 20.sp, keyCode = "21", onKeyDown = onDown, onKeyUp = onUp)
        }

        // ── ≡↑/REGS row ───────────────────────────────────────────────────
        SRow {
            SMC(w = 1f) { MixT("\u2261\u2191 ", ShiftOrange); MixT("REGS", ShiftBlue) }
            SMC(w = 1f) { BoxT2("BASE", "BITS") }
            SMC(w = 1f) { BoxT2("INTS", "REAL") }
            SMC(w = 1f) { BoxT2("MATX", "X.FN") }
            SMC(w = 1f) { BoxT2("EQN", "ADV") }
        }
        BtnRow {
            Key("\u2191",    w = 1f, fsize = 22.sp, keyCode = "22", onKeyDown = onDown, onKeyUp = onUp)
            Key("4",         corner = "R", w = 1f, fsize = 18.sp, keyCode = "23", onKeyDown = onDown, onKeyUp = onUp)
            Key("5",         corner = "S", w = 1f, fsize = 18.sp, keyCode = "24", onKeyDown = onDown, onKeyUp = onUp)
            Key("6",         corner = "T", w = 1f, fsize = 18.sp, keyCode = "25", onKeyDown = onDown, onKeyUp = onUp)
            Key("\u00D7",    corner = "U", w = 1f, fsize = 20.sp, keyCode = "26", onKeyDown = onDown, onKeyUp = onUp)
        }

        // ── ≡↓/FLGS row ───────────────────────────────────────────────────
        SRow {
            SMC(w = 1f) { MixT("\u2261\u2193 ", ShiftOrange); MixT("FLGS", ShiftBlue) }
            SMC(w = 1f) { BoxT2("PREF", "KEYS") }
            SMC(w = 1f) { BoxT2("CONV", "CLK") }
            SMC(w = 1f) { BoxT2("FLAG", "\u03B1.FN") }
            SMC(w = 1f) { BoxT2("PROB", "FIN") }
        }
        BtnRow {
            Key("\u2193",    w = 1f, fsize = 22.sp, keyCode = "27", onKeyDown = onDown, onKeyUp = onUp)
            Key("1",         corner = "V", w = 1f, fsize = 18.sp, keyCode = "28", onKeyDown = onDown, onKeyUp = onUp)
            Key("2",         corner = "W", w = 1f, fsize = 18.sp, keyCode = "29", onKeyDown = onDown, onKeyUp = onUp)
            Key("3",         corner = "X", w = 1f, fsize = 18.sp, keyCode = "30", onKeyDown = onDown, onKeyUp = onUp)
            Key("\u2212",    corner = "Y", w = 1f, fsize = 20.sp, keyCode = "31", onKeyDown = onDown, onKeyUp = onUp)
        }

        // ── EXIT row ──────────────────────────────────────────────────────
        SRow {
            SMC(w = 1f) { PowerGlyph(); Spacer(Modifier.width(2.dp)); BoxT("INFO") }
            SMC(w = 1f) { MixT("VIEW ", ShiftOrange); BoxT("I/O") }
            SMix2("SHOW", "ab/c", w = 1f, fsize = 7)
            SMC(w = 1f) { MixT("PRGM ", ShiftOrange); BoxT("P.FN") }
            SMC(w = 1f) { BoxT2("CAT", "CNST") }
        }
        BtnRow {
            Key("EXIT",      w = 1f, fsize = 16.sp, keyCode = "32", onKeyDown = onDown, onKeyUp = onUp)
            Key("0",         corner = "Z",  w = 1f, fsize = 18.sp, keyCode = "33", onKeyDown = onDown, onKeyUp = onUp)
            Key("\u00B7",    corner = ",",  w = 1f, fsize = 24.sp, keyCode = "34", onKeyDown = onDown, onKeyUp = onUp)
            Key("R/S",       corner = "?",  w = 1f, fsize = 13.sp, keyCode = "35", onKeyDown = onDown, onKeyUp = onUp)
            Key("+",         corner = "\u21B5", w = 1f, fsize = 20.sp, keyCode = "36", onKeyDown = onDown, onKeyUp = onUp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Layout helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Horizontal row for buttons. */
@Composable
private fun BtnRow(height: Dp = 44.dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(height), content = content)
}

/** Horizontal row for shift labels. Top padding creates vertical breathing
 *  room between the preceding button row and this label strip; labels remain
 *  visually anchored to the button row BELOW (matches GIF). */
@Composable
private fun SRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(22.dp).padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Calculator key  (3-D raised bevel)
// ─────────────────────────────────────────────────────────────────────────────

private val DEPTH_PX_FRACTION = 0.14f   // fraction of button height that is "depth"
private val CORNER_DP = 6f

@Composable
private fun RowScope.Key(
    label: String,
    corner: String = "",
    w: Float = 1f,
    v: BtnVariant = BtnVariant.Normal,
    fsize: TextUnit = 13.sp,
    keyCode: String? = null,
    onKeyDown: ((String) -> Unit)? = null,
    onKeyUp: ((String) -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    val (fTop, fBot, depth) = when (v) {
        BtnVariant.Normal  -> Triple(BtnFaceTop,    BtnFaceBot,    BtnDepth)
        BtnVariant.Softkey -> Triple(BtnSkFaceTop,  BtnSkFaceBot,  BtnSkDepth)
        BtnVariant.Orange  -> Triple(BtnOrgFaceTop, BtnOrgFaceBot, BtnOrgDepth)
        BtnVariant.Blue    -> Triple(BtnBluFaceTop, BtnBluFaceBot, BtnBluDepth)
    }

    // Outer cell (full width, no clip) — corner letter sits OUTSIDE the
    // clipped button face, in the dark inter-button gutter (matches GIF).
    Box(
        modifier = Modifier
            .weight(w)
            .fillMaxHeight()
    ) {
        // Inner pressable clipped button face. pointerInput + detectTapGestures
        // gives us distinct down/up callbacks (the engine needs both — see
        // keyboard.c ST_1_PRESS1 long-press state machine) while we still drive
        // `src` so the 3-D press animation works.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(CORNER_DP.dp))
                .pointerInput(keyCode, onKeyDown, onKeyUp) {
                    detectTapGestures(
                        onPress = { offset ->
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            val press = PressInteraction.Press(offset)
                            src.emitPress(scope, press)
                            keyCode?.let { onKeyDown?.invoke(it) }
                            val released = tryAwaitRelease()
                            keyCode?.let { onKeyUp?.invoke(it) }
                            src.emitRelease(scope, press, released)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // ── Canvas: depth shadow + gradient face + highlight ───────────
            Canvas(Modifier.fillMaxSize()) {
                val cr   = CornerRadius(CORNER_DP.dp.toPx())
                val dH   = size.height * DEPTH_PX_FRACTION
                val faceY = if (pressed) dH else 0f
                val faceH = size.height - dH

                // 1. Depth / side-wall layer  (only when not pressed)
                if (!pressed) {
                    drawRoundRect(
                        color = depth,
                        topLeft = Offset(0f, dH * 0.55f),
                        size = Size(size.width, size.height - dH * 0.55f),
                        cornerRadius = cr
                    )
                }

                // 2. Button face with vertical gradient
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(fTop, fBot),
                        startY = faceY,
                        endY   = faceY + faceH
                    ),
                    topLeft = Offset(0f, faceY),
                    size = Size(size.width, faceH),
                    cornerRadius = cr
                )

                // 3. Specular highlight line along top edge
                if (!pressed) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.18f),
                        start = Offset(CORNER_DP.dp.toPx() * 0.6f, faceY + 1.5f),
                        end   = Offset(size.width - CORNER_DP.dp.toPx() * 0.6f, faceY + 1.5f),
                        strokeWidth = 1.8f
                    )
                }

                // 4. Very subtle bottom-edge darkening on the face
                drawRoundRect(
                    color = Color.Black.copy(alpha = if (pressed) 0.0f else 0.25f),
                    topLeft = Offset(0f, faceY + faceH * 0.65f),
                    size = Size(size.width, faceH * 0.35f),
                    cornerRadius = cr
                )
            }

            // ── Label text ─────────────────────────────────────────────────
            val textYOffset = if (pressed)
                (DEPTH_PX_FRACTION * 22 * 0.5f).dp   // sink down with the face
            else
                -(DEPTH_PX_FRACTION * 44 * 0.5f).dp  // float up on raised face

            Text(
                text = label,
                modifier = Modifier
                    .offset(y = textYOffset)
                    .padding(horizontal = 3.dp),
                color = LabelWhite,
                fontFamily = C47Standard,
                fontSize = fsize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }

        // ── Corner letter (OUTSIDE the button face, in the gutter) ────────
        if (corner.isNotEmpty()) {
            Text(
                text = corner,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 0.dp, bottom = 0.dp),
                color = CornerChar,
                fontFamily = C47Tiny,
                fontSize = 8.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 9.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shift-label primitives
// ─────────────────────────────────────────────────────────────────────────────

/** Simple single-color shift label cell. */
@Composable
private fun RowScope.SC(
    text: String,
    color: Color = ShiftOrange,
    italic: Boolean = false,
    w: Float = 1f,
    fsize: Int = 9
) {
    Text(
        text = text,
        modifier = Modifier
            .weight(w)
            .fillMaxHeight()
            .wrapContentHeight(Alignment.CenterVertically),
        color = color,
        fontFamily = C47Tiny,
        fontSize = fsize.sp,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Mixed-content shift label cell (can hold BoxT, MixT, etc.). */
@Composable
private fun RowScope.SMC(
    w: Float = 1f,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .weight(w)
            .fillMaxHeight()
            .clipToBounds(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

/** Inline text fragment inside a SMC cell. */
@Composable
private fun RowScope.MixT(text: String, color: Color, italic: Boolean = false, fsize: Int = 7) {
    Text(
        text = text,
        color = color,
        fontFamily = C47Tiny,
        fontSize = fsize.sp,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontWeight = FontWeight.Medium,
        maxLines = 1
    )
}

/** Orange-bordered box label. Default color is blue (matches R47: CPX/STK/CLR/INFO/I/O/ab/c/P.FN). */
@Composable
private fun RowScope.BoxT(text: String, color: Color = ShiftBlue, fsize: Int = 7) {
    Box(
        modifier = Modifier
            .border(1.dp, BoxBorder, RoundedCornerShape(2.dp))
            .padding(horizontal = 1.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = C47Tiny,
            fontSize = fsize.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            lineHeight = (fsize + 2).sp
        )
    }
}

/** Orange-bordered box with two words: first orange, second blue
 *  (e.g. DISP TRG, BASE BITS, STAT PLOT, CAT CNST). */
@Composable
private fun RowScope.BoxT2(orange: String, blue: String) {
    Row(
        modifier = Modifier
            .border(1.dp, BoxBorder, RoundedCornerShape(2.dp))
            .padding(horizontal = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = orange,
            color = ShiftOrange,
            fontFamily = C47Tiny,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            lineHeight = 9.sp
        )
        Text(
            text = " ",
            fontFamily = C47Tiny,
            fontSize = 7.sp,
            maxLines = 1
        )
        Text(
            text = blue,
            color = ShiftBlue,
            fontFamily = C47Tiny,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            lineHeight = 9.sp
        )
    }
}

/** Two-color shift-label cell (no box): first word orange, second blue
 *  (e.g. SIN ASIN, COS ACOS, TAN ATAN). When `italic` is true, both halves
 *  render italic — used by row 1/2 where the symbol is a math-italic glyph. */
@Composable
private fun RowScope.SMix2(
    orange: String,
    blue: String,
    w: Float = 1f,
    italic: Boolean = false,
    fsize: Int = 9,
) {
    Row(
        modifier = Modifier
            .weight(w)
            .fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val style = if (italic) FontStyle.Italic else FontStyle.Normal
        Text(
            text = orange,
            color = ShiftOrange,
            fontFamily = C47Tiny,
            fontSize = fsize.sp,
            fontStyle = style,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Text(
            text = " ",
            fontFamily = C47Tiny,
            fontSize = fsize.sp,
            maxLines = 1
        )
        Text(
            text = blue,
            color = ShiftBlue,
            fontFamily = C47Tiny,
            fontSize = fsize.sp,
            fontStyle = style,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// InteractionSource emit helpers — detectTapGestures runs on a PointerInput
// scope, which is a coroutine but not a CoroutineScope; bridge via the
// composable's rememberCoroutineScope so the press animation fires.
// ─────────────────────────────────────────────────────────────────────────────

private fun MutableInteractionSource.emitPress(
    scope: CoroutineScope,
    press: PressInteraction.Press,
) {
    scope.launch { emit(press) }
}

private fun MutableInteractionSource.emitRelease(
    scope: CoroutineScope,
    press: PressInteraction.Press,
    released: Boolean,
) {
    scope.launch {
        emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
    }
}

/** Power symbol (⏻ U+23FB is missing from the C47 font). Circle with a vertical
 *  line gap at the top, drawn orange to match the GIF EXIT-row shift label. */
@Composable
private fun PowerGlyph(size: Dp = 9.dp, color: Color = ShiftOrange) {
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.14f
        val r = this.size.minDimension * 0.40f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        // Arc from ~30° past top clockwise nearly full circle (leaves gap at top).
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(cx, cy - r * 1.15f),
            end = Offset(cx, cy + r * 0.1f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/** Filled orange box label (italic α — matches GIF). */
@Composable
private fun RowScope.BoxTFilled(text: String) {
    Box(
        modifier = Modifier
            .background(BoxBorderFill, RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp, vertical = 0.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.Black,
            fontFamily = C47Standard,
            fontSize = 10.sp,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
