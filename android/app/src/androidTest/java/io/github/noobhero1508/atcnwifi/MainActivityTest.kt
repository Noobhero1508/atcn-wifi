package io.github.noobhero1508.atcnwifi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryActionsAndSettingsAreReachable() {
        val activity = composeRule.activity
        io.github.noobhero1508.atcnwifi.monitor.MonitorPreferences(activity).priorityContinuous = false
        composeRule.onNodeWithText(activity.getString(R.string.connect_now)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.diagnose)).assertIsDisplayed()

        composeRule.onNodeWithText(activity.getString(R.string.nav_settings)).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.check_frequency)).assertIsDisplayed()
        val priority = composeRule.onNodeWithContentDescription(activity.getString(R.string.priority_accessibility))
        priority.assertIsOff()
        priority.performClick().assertIsOn()
        priority.performClick().assertIsOff()
    }
}
