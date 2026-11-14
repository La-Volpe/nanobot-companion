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
    val brand = Color(red = 0.85f, green = 0.45f, blue = 0.18f)

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
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dark -> darkColorScheme(
            primary = Color(0xFFEDEDED),
            onPrimary = Color(0xFF151515),
            surface = Color(0xFF151413),
            onSurface = Color(0xFFEDEBE9),
            surfaceVariant = Color(0xFF232120),
            onSurfaceVariant = Color(0xFFB9B4AF),
            outline = Color(0xFF3A3735),
            error = Color(0xFFE5675C),
        )

        else -> lightColorScheme(
            primary = Color(0xFF1B1A19),
            onPrimary = Color(0xFFFDFCFB),
            surface = Color(0xFFFDFCFB),
            onSurface = Color(0xFF1B1A19),
            surfaceVariant = Color(0xFFF2EFEC),
            onSurfaceVariant = Color(0xFF5C5754),
            outline = Color(0xFFDAD5D0),
            error = Color(0xFFB3261E),
        )
    }
    val typography = Typography().let { base ->
        base.copy(
            bodyLarge = base.bodyLarge.copy(fontSize = Design.Reading.size, lineHeight = Design.Reading.lineHeight),
            bodyMedium = base.bodyMedium.copy(fontSize = Design.Reading.size, lineHeight = Design.Reading.lineHeight),
            bodySmall = base.bodySmall.copy(fontSize = Design.Interface.captionSize),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

/** The reading style a prose row uses: Open Sans at the reading scale. */
val readingStyle = TextStyle(
    fontFamily = OpenSans,
    fontSize = Design.Reading.size,
    lineHeight = Design.Reading.lineHeight,
)
