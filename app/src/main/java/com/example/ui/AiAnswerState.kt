package com.example.ui

import com.example.ai.AnswerResponse
import com.example.data.ItemWithCollections

data class AiAnswerSource(val sourceId: String, val item: ItemWithCollections, val pageNumber: Int?)
data class AiAnswerState(val response: AnswerResponse, val sources: List<AiAnswerSource>)
