package com.example.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.ai.AnswerResponse
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.example.ui.components.AiAnswerCard
import com.example.ui.components.SearchResultsContent
import com.example.ui.theme.PersonalLibraryTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure synthetic presentation. No provider or user-library access. */
@RunWith(AndroidJUnit4::class)
class AiAnswerUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val first = ItemWithCollections(ItemEntity("synthetic-source-a", "Friends export", "birthday", "TEXT"), emptyList())
    private val second = ItemWithCollections(ItemEntity("synthetic-source-b", "Birthday note", "birthday", "TEXT"), emptyList())

    @Test fun answerSourceOpensTheCorrectLocalItemAndShowsPage() {
        var opened: String? = null
        val state = AiAnswerState(AnswerResponse("ANSWERED", "Sarah's birthday appears to be August 19.", listOf("s0")),
            listOf(AiAnswerSource("s0", first, 7)))
        compose.runOnUiThread {
            compose.activity.setContent { PersonalLibraryTheme { AiAnswerCard(state, { opened = it.item.id }) } }
        }
        compose.onNodeWithTag("ai_answer_text").assertTextEquals(state.response.answer)
        compose.onNodeWithContentDescription("Open source: Friends export, page 7").performClick()
        compose.runOnIdle { assertEquals(first.item.id, opened) }
    }

    @Test fun conflictingAnswerShowsBothAccessibleSources() {
        val state = AiAnswerState(AnswerResponse("CONFLICTING", "Two dates are present in your sources.", listOf("s0", "s1")),
            listOf(AiAnswerSource("s0", first, 7), AiAnswerSource("s1", second, null)))
        compose.runOnUiThread {
            compose.activity.setContent { PersonalLibraryTheme { AiAnswerCard(state, {}) } }
        }
        compose.onNodeWithTag("ai_answer_heading").assertTextEquals("Conflicting evidence")
        compose.onNodeWithContentDescription("Open source: Friends export, page 7").assertHasClickAction()
        compose.onNodeWithContentDescription("Open source: Birthday note").assertHasClickAction()
    }

    @Test fun insufficientEvidenceUsesHonestLocalWordingWithoutSourceButtons() {
        val state = AiAnswerState(AnswerResponse("INSUFFICIENT", "", emptyList()), emptyList())
        compose.runOnUiThread {
            compose.activity.setContent { PersonalLibraryTheme { AiAnswerCard(state, {}) } }
        }
        compose.onNodeWithTag("ai_answer_text").assertTextContains("enough evidence", substring = true)
        compose.onNodeWithTag("ai_answer_source_s0").assertDoesNotExist()
    }

    @Test fun directAnswerAndLocalResultsCoexist() {
        val state = AiAnswerState(AnswerResponse("ANSWERED", "August 19.", listOf("s0")), listOf(AiAnswerSource("s0", first, 7)))
        compose.runOnUiThread {
            compose.activity.setContent {
                PersonalLibraryTheme {
                    SearchResultsContent("birthday", listOf(first), emptyList(), true, null, null, false,
                        false, false, false, emptySet(), { null }, { _, _ -> null }, {}, {}, {}, {}, {}, answer = state)
                }
            }
        }
        compose.onNodeWithTag("item_card_synthetic-source-a").assertIsDisplayed()
        compose.onNodeWithTag("search_results_list").performScrollToNode(hasTestTag("ai_answer_card"))
        compose.onNodeWithTag("ai_answer_text").assertTextEquals("August 19.")
    }
}
