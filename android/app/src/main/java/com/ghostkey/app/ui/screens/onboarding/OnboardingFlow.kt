package com.ghostkey.app.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.AppLanguage
import com.ghostkey.app.RelayTestState
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.GhostTheme

/**
 * Telegram-clean single-task Onboarding Flow:
 * 0: Welcome + Language toggle
 * 1: Display Name + Relay URL (with Test Connection) + Ephemeral toggle
 * 2: 6-digit PIN via In-App Keypad (with Skip) -> Keygen Spinner
 */
@Composable
fun OnboardingFlow(
    language: AppLanguage,
    onToggleLanguage: (AppLanguage) -> Unit,
    relayTestState: RelayTestState,
    onTestRelay: (String) -> Unit,
    onResetRelayTest: () -> Unit,
    isGeneratingKey: Boolean,
    onCompleteOnboarding: (alias: String, relayUrl: String, appPin: String?, ephemeralMode: Boolean) -> Unit
) {
    val palette = GhostTheme.colors
    var step by remember { mutableIntStateOf(0) }
    var alias by remember { mutableStateOf("") }
    var relayUrl by remember { mutableStateOf("") }
    var ephemeralMode by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = palette.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .padding(innerPadding)
        ) {
            if (isGeneratingKey) {
                KeygenSpinnerStep()
            } else {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it / 3 } + fadeOut())
                        } else {
                            (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                                (slideOutHorizontally { it / 3 } + fadeOut())
                        }
                    },
                    label = "OnboardingStep"
                ) { currentStep ->
                    when (currentStep) {
                        0 -> WelcomeStep(
                            language = language,
                            onToggleLanguage = onToggleLanguage,
                            onStart = { step = 1 }
                        )
                        1 -> IdentityRelayStep(
                            alias = alias,
                            onAliasChange = { alias = it },
                            relayUrl = relayUrl,
                            onRelayUrlChange = {
                                relayUrl = it
                                onResetRelayTest()
                            },
                            ephemeralMode = ephemeralMode,
                            onEphemeralChange = { ephemeralMode = it },
                            relayTestState = relayTestState,
                            onTestRelay = { onTestRelay(relayUrl) },
                            onBack = { step = 0 },
                            onContinue = {
                                if (ephemeralMode) {
                                    onCompleteOnboarding(alias, relayUrl, null, true)
                                } else {
                                    step = 2
                                }
                            }
                        )
                        else -> PinSetupStep(
                            onBack = { step = 1 },
                            onSkip = { onCompleteOnboarding(alias, relayUrl, null, ephemeralMode) },
                            onPinConfirmed = { pin -> onCompleteOnboarding(alias, relayUrl, pin, ephemeralMode) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeygenSpinnerStep() {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = AppIcons.Key,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        CircularProgressIndicator(
            color = palette.accent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = strings.keygenTitle,
            color = palette.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = strings.keygenSubtitle,
            color = palette.textSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}
