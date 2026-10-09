// From SSHBorg (https://github.com/payne1982/sshborg), app/src/main/kotlin/com/sshborg/terminal/TextStyle.kt
// Copyright payne1982. Licensed under the GNU GPL v3.0, see LICENSE. Copied unchanged on 2026-10-09.

package com.sshborg.terminal

/**
 * Visual attributes for a single terminal cell.
 * Colors are encoded as:
 *   -1       = default
 *   0..15    = standard ANSI colors
 *   16..255  = 256-color palette
 *   0x1000000..0x1FFFFFF = 24-bit RGB (0x1_RRGGBB)
 */
data class TextStyle(
    val fg: Int = COLOR_DEFAULT,
    val bg: Int = COLOR_DEFAULT,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val blink: Boolean = false,
    val inverse: Boolean = false,
    val invisible: Boolean = false,
) {
    companion object {
        const val COLOR_DEFAULT = -1
        val DEFAULT = TextStyle()
    }
}
