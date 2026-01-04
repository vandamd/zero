package com.vandam.zero.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/**
 * Rotates the composable 90 degrees while swapping its width and height constraints.
 * Useful for creating vertical layouts that need rotated content.
 *
 * @param clockwise If true, rotates 90 degrees clockwise; if false, rotates counter-clockwise.
 */
fun Modifier.rotateVertically(clockwise: Boolean = true): Modifier {
    val rotateBy = if (clockwise) 90f else -90f

    return layout { measurable, constraints ->
        val swappedConstraints =
            constraints.copy(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            )

        val placeable = measurable.measure(swappedConstraints)

        layout(placeable.height, placeable.width) {
            placeable.placeWithLayer(
                x = -(placeable.width / 2) + (placeable.height / 2),
                y = -(placeable.height / 2) + (placeable.width / 2),
            ) {
                rotationZ = rotateBy
            }
        }
    }
}
