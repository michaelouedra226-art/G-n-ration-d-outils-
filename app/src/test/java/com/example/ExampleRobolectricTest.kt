package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `app_name string matches ForgeAI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("ForgeAI", context.getString(R.string.app_name))
    }

    @Test
    fun `app starts empty across tools and api tabs`() {
        composeRule.setContent {
            ForgeAppRoot()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Atelier ForgeAI").assertIsDisplayed()
        composeRule.onNodeWithTag("firebase_ai_notice_banner").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tools").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Aucun outil installé").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_apis").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Aucun fournisseur API").assertIsDisplayed()
    }
}
