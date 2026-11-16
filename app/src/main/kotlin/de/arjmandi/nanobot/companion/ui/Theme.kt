package de.arjmandi.nanobot.companion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.arjmandi.nanobot.companion.R

/**
 * The design system's Android mapping in one place (design README, "Platform adaptation"), so no
 * screen spells a dp value or a radius out of thin air — the same role `Design.swift` plays on
 * iOS, with Android's own numbers:
 *
 * - reading is Open Sans 16 at a 1.55 line height (iOS reads at 17 / 1.5),
 * - interface type is the system stack at 14 / 12 / 11 (iOS: 15 / 13 / 11),
 * - corners are 8 / 12 / 16 / 28 dp (iOS: 12 / 18 / 22 / 28 pt),
 * - every control is at least 48 dp (iOS: 44 pt),
 * - primary actions are monochrome; the warm orange is the mark and status accents only.
 */
object Design {

    object Radius {
        val field = 8.dp
        val bubble = 12.dp
        val panel = 16.dp
        val sheet = 28.dp
    }

    /** The design's 48 dp rule for Android, used by every tappable row and control. */
    val minimumTarget = 48.dp

    /** The identity anchor from the design system. Never a button, never a surface fill. */
    val brand = Color(0xFFEF8E30)

    /**
     * How far up a hold has to travel before it locks: past this the finger may leave the screen
     * and the button becomes Send (T48). A distance, not a delay — nothing about starting a
     * recording waits for a clock any more.
     */
    val lockDragDistance = 64.dp

    /** The composer's bottom inset above the navigation bar. */
    val composerBottomPadding = 12.dp

    /** The interface scale from the design README (Android: 14 / 12 / 11). */
    object Interface {
        val bodySize = 14.sp
        val captionSize = 12.sp
        val microSize = 11.sp
    }

    /** Reading type: Open Sans, shipped with the app under the SIL OFL (`res/font`, OFL alongside). */
    object Reading {
        val size = 16.sp

        /** 1.55 × 16 ≈ 25 sp, the design's Android line height. */
        val lineHeight = 25.sp
    }

    /** The activity cluster's numbers (artifact: `.reasoning`, `.tool-row`, `.diff`). */
    object Activity {
        /** Reasoning is reading text, two points under the reading scale (iOS: 15 of 17). */
        val reasoningSize = 14.sp

        /** The diff is the smallest type in the app, and monospaced. */
        val diffSize = 11.sp

        /** A tool row is a row a thumb can land on, like every other row here. */
        val rowHeight = minimumTarget
    }
}

/** The reading face, registered from `res/font/open_sans.ttf`. */
val OpenSans = FontFamily(Font(R.font.open_sans, FontWeight.Normal))

/**
 * The app's Material 3 theme: neutral surfaces, a monochrome primary, and Open Sans where prose is
 * read. Only the three roles the screens use are set for reading; everything else is M3's own.
 */
@Composable
fun NanobotTheme(
    dark: Boolean = isSystemInDarkTheme(),
    readingScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        // Charcoal `hsl(0 0% 19%)`, not black and not navy — the design says so, and the iOS
        // palette carries the same numbers.
        dark -> darkColorScheme(
            primary = Color(0xFFFAFAFA),
            onPrimary = Color(0xFF171717),
            surface = Color(0xFF303030),
            onSurface = Color(0xFFF4F4F5),
            surfaceVariant = Color(0xFF383838),
            onSurfaceVariant = Color(0xFFB8B8B8),
            outline = Color(0xFF474747),
            error = Color(0xFFFCA5A5),
        )

        else -> lightColorScheme(
            primary = Color(0xFF27272A),
            onPrimary = Color(0xFFFAFAFA),
            surface = Color(0xFFFDFCFB),
            onSurface = Color(0xFF1E1E20),
            surfaceVariant = Color(0xFFF5F5F5),
            onSurfaceVariant = Color(0xFF62626A),
            outline = Color(0xFFE9E7E5),
            error = Color(0xFFB91C1C),
        )
    }
    val typography = Typography().let { base ->
        base.copy(
            bodyLarge = base.bodyLarge.copy(fontSize = Design.Reading.size, lineHeight = Design.Reading.lineHeight),
            bodyMedium = base.bodyMedium.copy(fontSize = Design.Reading.size, lineHeight = Design.Reading.lineHeight),
            bodySmall = base.bodySmall.copy(fontSize = Design.Interface.captionSize),
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalReadingScale provides readingScale) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** The reading style a prose row uses: Open Sans at the reading scale. */
val readingStyle = TextStyle(
    fontFamily = OpenSans,
    fontSize = Design.Reading.size,
    lineHeight = Design.Reading.lineHeight,
)

/**
 * Settings → Appearance moves the reading scale and nothing else, which is the design's rule: prose,
 * code and the composer follow it, navigation does not (A7).
 */
val LocalReadingScale = androidx.compose.runtime.compositionLocalOf { 1f }

/** The reading style at the scale the user chose, for a composable that draws prose. */
@Composable
fun scaledReadingStyle(): TextStyle = readingStyle.copy(
    fontSize = Design.Reading.size * LocalReadingScale.current,
    lineHeight = Design.Reading.lineHeight * LocalReadingScale.current,
)
