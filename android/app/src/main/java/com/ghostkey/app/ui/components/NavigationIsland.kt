package com.ghostkey.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.AppNavScreen
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.GhostTheme

/**
 * Floating 3-tab Navigation Island inspired by Telegram's bottom pill bar.
 */
@Composable
fun NavigationIsland(
    currentScreen: AppNavScreen,
    onSelect: (AppNavScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings

    val items = listOf(
        Triple(AppNavScreen.CHATS_LIST, AppIcons.Chat, strings.navChats),
        Triple(AppNavScreen.SETTINGS, Icons.Default.Settings, strings.navSettings),
        Triple(AppNavScreen.PROFILE, Icons.Default.Person, strings.navProfile)
    )

    Surface(
        modifier = modifier
            .padding(horizontal = 28.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        shape = RoundedCornerShape(32.dp),
        color = palette.navIsland,
        shadowElevation = 10.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, palette.navIslandBorder, RoundedCornerShape(32.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (screen, icon, label) ->
                val selected = currentScreen == screen
                IslandNavItem(
                    icon = icon,
                    label = label,
                    selected = selected,
                    onClick = { onSelect(screen) }
                )
            }
        }
    }
}

@Composable
private fun RowScope.IslandNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = GhostTheme.colors
    val tint = if (selected) palette.navActive else palette.navInactive
    val pillBg = if (selected) palette.surfaceVariant else Color.Transparent

    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(pillBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
            if (selected) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = label,
                    color = tint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}
