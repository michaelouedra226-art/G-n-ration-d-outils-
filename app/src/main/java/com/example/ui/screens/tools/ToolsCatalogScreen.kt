package com.example.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.data.repository.InstalledTool
import com.example.domain.manifest.ToolCategory
import com.example.domain.manifest.ToolStatus
import com.example.ui.components.ForgeCard
import com.example.ui.components.ForgeEmptyState
import com.example.ui.components.ForgePill
import com.example.ui.components.resolveToolIcon
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.ForgeEmerald
import com.example.ui.theme.ForgeRose

@Composable
fun ToolsCatalogScreen(
    viewModel: ToolsViewModel,
    onRunTool: (String) -> Unit,
    onInspectTool: (String) -> Unit,
    onNavigateToAtelier: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tools by viewModel.tools.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Plugins installés",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Chaque outil est isolé dans un sandbox indépendant avec ses propres permissions et son historique de versions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (tools.isNotEmpty() || searchQuery.isNotBlank() || selectedCategory != null) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = viewModel::onSearchQueryChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tools_search_input"),
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                        placeholder = { Text("Rechercher un plugin...") },
                        singleLine = true
                    )
                }

                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { viewModel.onCategorySelected(null) },
                                label = { Text("Tous") }
                            )
                        }
                        items(ToolCategory.entries) { category ->
                            FilterChip(
                                selected = selectedCategory == category,
                                onClick = {
                                    viewModel.onCategorySelected(
                                        if (selectedCategory == category) null else category
                                    )
                                },
                                label = { Text(category.label) }
                            )
                        }
                    }
                }
            }

            if (tools.isEmpty()) {
                item {
                    ForgeEmptyState(
                        icon = Icons.Outlined.Extension,
                        title = if (searchQuery.isBlank() && selectedCategory == null) {
                            "Aucun outil installé"
                        } else {
                            "Aucun résultat"
                        },
                        subtitle = if (searchQuery.isBlank() && selectedCategory == null) {
                            "ForgeAI démarre sans données de démonstration. Décrivez votre premier outil dans l'Atelier pour le générer."
                        } else {
                            "Modifiez vos filtres ou votre recherche."
                        },
                        actionLabel = if (searchQuery.isBlank() && selectedCategory == null) {
                            "Aller à l'Atelier"
                        } else null,
                        actionTestTag = "empty_tools_atelier_button",
                        onAction = onNavigateToAtelier
                    )
                }
            } else {
                items(tools, key = { it.manifest.id }) { item ->
                    ToolCatalogItemCard(
                        item = item,
                        onRun = { onRunTool(item.manifest.id) },
                        onInspect = { onInspectTool(item.manifest.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCatalogItemCard(
    item: InstalledTool,
    onRun: () -> Unit,
    onInspect: () -> Unit
) {
    val manifest = item.manifest
    val statusColor = when (manifest.status) {
        ToolStatus.ACTIVE -> ForgeEmerald
        ToolStatus.PAUSED -> ForgeAmber
        ToolStatus.DRAFT -> MaterialTheme.colorScheme.secondary
        ToolStatus.ARCHIVED -> ForgeRose
    }

    ForgeCard(
        onClick = onRun,
        modifier = Modifier.testTag("tool_card_${manifest.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = resolveToolIcon(manifest.iconName),
                contentDescription = manifest.name,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = manifest.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${manifest.category.label} • v${manifest.version} • ${item.entity.executionCount} exéc.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ForgePill(text = manifest.status.label, color = statusColor)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = manifest.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (manifest.permissions.isEmpty()) {
                    "Sandbox pur (0 permission)"
                } else {
                    "${manifest.permissions.size} permission(s)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                IconButton(
                    onClick = onInspect,
                    modifier = Modifier.testTag("inspect_tool_${manifest.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Détails et manifeste"
                    )
                }
                IconButton(
                    onClick = onRun,
                    modifier = Modifier.testTag("run_tool_${manifest.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = "Exécuter l'outil",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
