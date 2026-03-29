package com.subbrain.zerotouch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.subbrain.zerotouch.api.FactSummary
import com.subbrain.zerotouch.ui.theme.ZtCaption
import com.subbrain.zerotouch.ui.theme.ZtImportanceLv0
import com.subbrain.zerotouch.ui.theme.ZtImportanceLv1
import com.subbrain.zerotouch.ui.theme.ZtImportanceLv2
import com.subbrain.zerotouch.ui.theme.ZtImportanceLv3
import com.subbrain.zerotouch.ui.theme.ZtImportanceLv4
import com.subbrain.zerotouch.ui.theme.ZtImportanceLv5
import com.subbrain.zerotouch.ui.theme.ZtOnSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtOutlineVariant
import com.subbrain.zerotouch.ui.theme.ZtSurfaceVariant
import com.subbrain.zerotouch.ui.theme.ZtTopicBorder
import com.subbrain.zerotouch.ui.theme.ZtTopicSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    modifier: Modifier = Modifier,
    uiState: ZeroTouchUiState,
    selectedCategory: String?,
    onRefresh: () -> Unit
) {
    val topics = uiState.topicCards.sortedByDescending { it.updatedAtEpochMs }
    val scoredCount = topics.count { it.importanceLevel != null }
    val highValueCount = topics.count { (it.importanceLevel ?: -1) >= 3 }
    val rawFactsByTopic = uiState.factsByTopic
    val filteredFactsByTopic = if (selectedCategory.isNullOrBlank()) {
        rawFactsByTopic
    } else {
        rawFactsByTopic.mapValues { (_, facts) ->
            facts.filter { fact -> fact.categories.any { it == selectedCategory } }
        }.filterValues { it.isNotEmpty() }
    }
    val filteredTopics = if (selectedCategory.isNullOrBlank()) {
        topics
    } else {
        topics.filter { filteredFactsByTopic.containsKey(it.id) }
    }
    val selectedFacts = if (selectedCategory.isNullOrBlank()) {
        emptyList()
    } else {
        filteredFactsByTopic.values.flatten()
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AnalysisHeader(
                totalCount = topics.size,
                scoredCount = scoredCount,
                highValueCount = highValueCount
            )

            if (!selectedCategory.isNullOrBlank()) {
                CategorySummaryCard(
                    category = selectedCategory,
                    factCount = selectedFacts.size,
                    topicCount = filteredFactsByTopic.keys.size,
                    lastUpdated = selectedFacts
                        .mapNotNull { it.updated_at ?: it.created_at }
                        .maxOrNull()
                )
            }

            if (filteredTopics.isEmpty() && !uiState.isLoading) {
                EmptyAnalysisState()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(filteredTopics, key = { it.id }) { topic ->
                        val facts = filteredFactsByTopic[topic.id].orEmpty()
                        AnalysisTopicCard(topic = topic, facts = facts)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisHeader(
    totalCount: Int,
    scoredCount: Int,
    highValueCount: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, ZtOutlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "抽出された意味の状況",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeaderMetric(label = "Topic", value = totalCount.toString())
                HeaderMetric(label = "Scored", value = scoredCount.toString())
                HeaderMetric(label = "Lv.3+", value = highValueCount.toString())
            }
            Text(
                text = "スコアと抽出結果を確認しながら、重要度の高いトピックを優先して整理します。",
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption
            )
        }
    }
}

@Composable
private fun HeaderMetric(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = ZtSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ZtOnSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AnalysisTopicCard(topic: TopicFeedCard, facts: List<FactSummary>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ZtTopicSurface,
        border = BorderStroke(1.dp, ZtTopicBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = topic.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                    Text(
                        text = topic.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = ZtOnSurfaceVariant,
                        maxLines = 3
                    )
                }
                Spacer(Modifier.width(10.dp))
                ImportanceBadge(level = topic.importanceLevel)
            }

            if (!topic.importanceReason.isNullOrBlank()) {
                Text(
                    text = topic.importanceReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZtCaption
                )
            }

            if (facts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Facts ${facts.size}件",
                        style = MaterialTheme.typography.labelSmall,
                        color = ZtOnSurfaceVariant
                    )
                    facts.take(2).forEach { fact ->
                        Text(
                            text = "- ${fact.fact_text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2
                        )
                    }
                    if (facts.size > 2) {
                        Text(
                            text = "+${facts.size - 2} 件",
                            style = MaterialTheme.typography.labelSmall,
                            color = ZtCaption
                        )
                    }
                }
            } else {
                Text(
                    text = "Fact 未抽出",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = topic.displayDate.ifEmpty { "日付不明" },
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption
                )
                Text(
                    text = "カード ${topic.utteranceCount}件",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZtCaption
                )
            }
        }
    }
}

@Composable
private fun CategorySummaryCard(
    category: String,
    factCount: Int,
    topicCount: Int,
    lastUpdated: String?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, ZtOutlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = category,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeaderMetric(label = "Facts", value = factCount.toString())
                HeaderMetric(label = "Topics", value = topicCount.toString())
            }
            Text(
                text = "最終更新: ${formatIsoDate(lastUpdated)}",
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption
            )
        }
    }
}

private fun formatIsoDate(value: String?): String {
    if (value.isNullOrBlank()) return "-"
    val index = value.indexOf("T")
    return if (index > 0) value.substring(0, index) else value
}

@Composable
private fun ImportanceBadge(level: Int?) {
    val (label, color) = when (level) {
        0 -> "Lv.0" to ZtImportanceLv0
        1 -> "Lv.1" to ZtImportanceLv1
        2 -> "Lv.2" to ZtImportanceLv2
        3 -> "Lv.3" to ZtImportanceLv3
        4 -> "Lv.4" to ZtImportanceLv4
        5 -> "Lv.5" to ZtImportanceLv5
        else -> "未評価" to ZtOutlineVariant
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (level == null) ZtOnSurfaceVariant else color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun EmptyAnalysisState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, ZtOutlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "まだ分析対象がありません",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "トピックが確定し、スコアリングが完了するとここに表示されます。",
                style = MaterialTheme.typography.bodySmall,
                color = ZtCaption
            )
        }
    }
}
