package com.subbrain.zerotouch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subbrain.zerotouch.api.WikiPage
import com.subbrain.zerotouch.api.ZeroTouchApi
import com.subbrain.zerotouch.ui.theme.*
import kotlinx.coroutines.launch

private val api = ZeroTouchApi()

private data class ProjectGroup(
    val key: String,
    val label: String,
    val isMissing: Boolean,
    val categories: List<CategoryGroup>
)

private data class CategoryGroup(
    val key: String,
    val label: String,
    val isMissing: Boolean,
    val pages: List<WikiPage>
)

private fun resolveProjectKey(page: WikiPage) =
    page.project_id?.trim()?.ifBlank { null }
        ?: page.project_key?.trim()?.ifBlank { null }
        ?: "__no_project__"

private fun resolveProjectLabel(page: WikiPage) =
    page.project_name?.trim()?.ifBlank { null }
        ?: page.project_key?.trim()?.ifBlank { null }
        ?: "Uncategorized Project"

private fun resolveCategoryKey(page: WikiPage) =
    page.category?.trim()?.ifBlank { null } ?: "__no_category__"

private fun resolveCategoryLabel(page: WikiPage) =
    page.category?.trim()?.ifBlank { null } ?: "Uncategorized"

private fun buildGroups(pages: List<WikiPage>): List<ProjectGroup> {
    val projectMap = linkedMapOf<String, Pair<String, LinkedHashMap<String, Pair<String, MutableList<WikiPage>>>>>()
    for (page in pages) {
        val pk = resolveProjectKey(page)
        val pl = resolveProjectLabel(page)
        val ck = resolveCategoryKey(page)
        val cl = resolveCategoryLabel(page)
        val proj = projectMap.getOrPut(pk) { Pair(pl, linkedMapOf()) }
        val cat = proj.second.getOrPut(ck) { Pair(cl, mutableListOf()) }
        cat.second.add(page)
    }
    return projectMap.entries
        .map { (pk, pv) ->
            ProjectGroup(
                key = pk,
                label = pv.first,
                isMissing = pk == "__no_project__",
                categories = pv.second.entries.map { (ck, cv) ->
                    CategoryGroup(
                        key = ck,
                        label = cv.first,
                        isMissing = ck == "__no_category__",
                        pages = cv.second.sortedBy { it.title }
                    )
                }.sortedWith(compareBy({ it.isMissing }, { it.label }))
            )
        }
        .sortedWith(compareBy({ it.isMissing }, { it.label }))
}

@Composable
fun WikiScreen(
    modifier: Modifier = Modifier,
    deviceId: String? = null
) {
    var pages by remember { mutableStateOf<List<WikiPage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var collapsedProjects by remember { mutableStateOf(setOf<String>()) }
    var collapsedCategories by remember { mutableStateOf(setOf<String>()) }
    @Suppress("UNUSED_VARIABLE")
    val scope = rememberCoroutineScope()

    LaunchedEffect(deviceId) {
        isLoading = true
        errorMsg = null
        try {
            val response = api.listWikiPages(deviceId)
            pages = response.pages
            if (selectedId == null) selectedId = response.pages.firstOrNull()?.id
        } catch (e: Exception) {
            errorMsg = e.message
        } finally {
            isLoading = false
        }
    }

    val filteredPages = remember(pages, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) pages
        else pages.filter { page ->
            listOf(page.title, page.body, page.project_name, page.project_key,
                page.category, page.page_key, page.kind)
                .filterNotNull().joinToString(" ").lowercase().contains(q)
        }
    }

    val groups = remember(filteredPages) { buildGroups(filteredPages) }

    val activeId = remember(filteredPages, selectedId) {
        if (selectedId != null && filteredPages.any { it.id == selectedId }) selectedId
        else filteredPages.firstOrNull()?.id
    }

    val selectedPage = remember(activeId, pages) { pages.find { it.id == activeId } }

    val pageKeyToId = remember(pages) {
        pages.associate { (it.page_key?.trim()?.lowercase() ?: "") to it.id }
    }

    Row(modifier = modifier.background(ZtBackground)) {
        // Left tree panel
        Surface(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight(),
            color = ZtSurface,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Search bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ZtSurfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = ZtCaption,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = ZtOnBackground),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (query.isEmpty()) {
                                    Text("Search", style = MaterialTheme.typography.bodySmall, color = ZtCaption)
                                }
                                inner()
                            }
                        )
                    }
                }

                HorizontalDivider(color = ZtOutline, thickness = 1.dp)

                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ZtBlack, strokeWidth = 2.dp)
                    }
                } else if (!errorMsg.isNullOrBlank()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(errorMsg ?: "", style = MaterialTheme.typography.bodySmall, color = ZtError)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        groups.forEach { project ->
                            val projCollapsed = collapsedProjects.contains(project.key)
                            item(key = "proj_${project.key}") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            collapsedProjects = if (projCollapsed)
                                                collapsedProjects - project.key
                                            else
                                                collapsedProjects + project.key
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (projCollapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                                        contentDescription = null,
                                        tint = ZtCaption,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = project.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ZtOnSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    val totalPages = project.categories.sumOf { it.pages.size }
                                    Text(
                                        text = totalPages.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ZtCaption,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            if (!projCollapsed) {
                                project.categories.forEach { category ->
                                    val catNodeKey = "${project.key}:${category.key}"
                                    val catCollapsed = collapsedCategories.contains(catNodeKey)
                                    item(key = "cat_$catNodeKey") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    collapsedCategories = if (catCollapsed)
                                                        collapsedCategories - catNodeKey
                                                    else
                                                        collapsedCategories + catNodeKey
                                                }
                                                .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (catCollapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                                                contentDescription = null,
                                                tint = ZtCaption,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = category.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ZtOnSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = category.pages.size.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ZtCaption,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }

                                    if (!catCollapsed) {
                                        items(category.pages, key = { it.id }) { page ->
                                            val isActive = page.id == activeId
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 40.dp, end = 8.dp, top = 1.dp, bottom = 1.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isActive) ZtPrimaryContainer else Color.Transparent)
                                                    .clickable { selectedId = page.id }
                                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    text = page.title,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isActive) ZtPrimary else ZtOnBackground,
                                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(ZtOutline)
        )

        // Right content panel
        if (selectedPage != null) {
            WikiPageContent(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                page = selectedPage,
                pageKeyToId = pageKeyToId,
                onNavigate = { id -> selectedId = id }
            )
        } else if (!isLoading) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (pages.isEmpty()) "No Wiki pages found.\nRun Ingest to populate." else "Select a page",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZtCaption,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WikiPageContent(
    modifier: Modifier = Modifier,
    page: WikiPage,
    pageKeyToId: Map<String, String>,
    onNavigate: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(page.id) { scrollState.animateScrollTo(0) }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        // Metadata chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val chipBg = ZtSurfaceVariant
            if (!page.project_name.isNullOrBlank() || !page.project_key.isNullOrBlank()) {
                MetaChip(text = page.project_name ?: page.project_key ?: "", bg = chipBg)
            }
            if (!page.category.isNullOrBlank()) {
                MetaChip(text = "# ${page.category}", bg = chipBg)
            }
            if (!page.kind.isNullOrBlank()) {
                MetaChip(text = page.kind ?: "", bg = ZtPrimaryContainer, textColor = ZtPrimary)
            }
            MetaChip(text = "v${page.version ?: 1}", bg = chipBg)
        }

        Spacer(Modifier.height(16.dp))

        // Title
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = ZtOnBackground
        )

        Spacer(Modifier.height(16.dp))

        // Body
        val bodyText = page.body.trim()
        if (bodyText.isEmpty()) {
            Text("No content", style = MaterialTheme.typography.bodyMedium, color = ZtCaption)
        } else {
            val paragraphs = bodyText.split(Regex("\\n{2,}"))
            paragraphs.forEach { paragraph ->
                val lines = paragraph.split("\n")
                val isBullet = lines.all {
                    it.trimStart().startsWith("-") ||
                    it.trimStart().startsWith("・") ||
                    it.trimStart().startsWith("*")
                }
                if (isBullet) {
                    lines.forEach { line ->
                        val text = line.trimStart()
                            .removePrefix("-").removePrefix("・").removePrefix("*").trimStart()
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("• ", style = MaterialTheme.typography.bodyMedium, color = ZtOnBackground)
                            WikiLinkText(text = text, pageKeyToId = pageKeyToId, onNavigate = onNavigate)
                        }
                    }
                } else {
                    WikiLinkText(text = paragraph, pageKeyToId = pageKeyToId, onNavigate = onNavigate)
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))

        // Footer
        Text(
            text = "Updated: ${page.updated_at.take(16).replace("T", " ")}",
            style = MaterialTheme.typography.labelSmall,
            color = ZtCaption
        )
    }
}

@Composable
private fun WikiLinkText(
    text: String,
    pageKeyToId: Map<String, String>,
    onNavigate: (String) -> Unit
) {
    val pattern = Regex("\\[\\[([^\\]]+)\\]\\]")
    val annotated = buildAnnotatedString {
        var cursor = 0
        for (match in pattern.findAll(text)) {
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            val linkKey = match.groupValues[1].trim()
            val targetId = pageKeyToId[linkKey.lowercase()]
            if (targetId != null) {
                pushStringAnnotation("wiki_link", targetId)
                withStyle(SpanStyle(color = ZtPrimary, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) {
                    append(linkKey)
                }
                pop()
            } else {
                withStyle(SpanStyle(color = ZtError)) { append(linkKey) }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = ZtOnBackground),
        onClick = { offset ->
            annotated.getStringAnnotations("wiki_link", offset, offset).firstOrNull()?.let {
                onNavigate(it.item)
            }
        }
    )
}

@Composable
private fun MetaChip(text: String, bg: Color, textColor: Color = ZtOnSurfaceVariant) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 1)
    }
}
