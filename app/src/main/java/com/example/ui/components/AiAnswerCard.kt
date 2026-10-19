package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ItemWithCollections
import com.example.ui.AiAnswerState

@Composable
fun AiAnswerCard(state: AiAnswerState, onOpenSource: (ItemWithCollections) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().testTag("ai_answer_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (state.response.status == "CONFLICTING") "Conflicting evidence" else "AI answer",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("ai_answer_heading"))
            Text(if (state.response.status == "INSUFFICIENT")
                "There isn't enough evidence in the selected library items to answer this question."
                else state.response.answer,
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("ai_answer_text"))
            if (state.sources.isNotEmpty()) {
                Text("Sources", style = MaterialTheme.typography.labelLarge)
                state.sources.forEach { source ->
                    val title = source.item.item.title.ifBlank { "Saved item" }
                    val page = source.pageNumber?.let { ", page $it" }.orEmpty()
                    OutlinedButton(
                        onClick = { onOpenSource(source.item) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("ai_answer_source_${source.sourceId}")
                            .semantics { contentDescription = "Open source: $title$page" }
                    ) {
                        Text(title + (source.pageNumber?.let { " · Page $it" }.orEmpty()))
                    }
                }
            }
        }
    }
}
