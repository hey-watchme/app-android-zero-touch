package com.example.zero_touch

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class VoiceMemoScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsBasicControls() {
        rule.onNodeWithText("ボイスメモ (最小版)").assertExists()
        rule.onNodeWithTag("record_button").assertExists()
        rule.onNodeWithTag("play_button").assertExists()
        rule.onNodeWithText("待機中").assertExists()
    }
}

