package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Composable
internal fun GenericRecipeCollection(
    rows: List<Pair<NativeRecord, NativeRecipePresentation>>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    imageLoader: NativeImageLoader?,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val categories = remember(rows) { nativeRecipeCategories(rows) }
    val filteredRows = remember(rows, query, category) {
        filterNativeRecipeCollection(rows, query, category)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(
                start = NextcloudSpacing.Large,
                top = NextcloudSpacing.Medium,
                end = NextcloudSpacing.Large,
            ),
            label = { Text("Search recipes") },
            leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
            singleLine = true,
        )
        if (categories.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(
                    horizontal = NextcloudSpacing.Large,
                    vertical = NextcloudSpacing.Small,
                ),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                FilterChip(
                    selected = category == null,
                    onClick = { category = null },
                    label = { Text("All") },
                )
                categories.forEach { candidate ->
                    FilterChip(
                        selected = category.equals(candidate, ignoreCase = true),
                        onClick = { category = candidate },
                        label = { Text(candidate) },
                    )
                }
            }
        }
        if (filteredRows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "No recipes match your search.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(NextcloudSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                items(filteredRows, key = { (record, _) -> record.id }) { (record, recipe) ->
                    val interaction = onSelectRecord
                        ?.let { callback -> Modifier.clickable { callback(record) } }
                        ?: Modifier
                    Card(
                        modifier = interaction.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Column {
                            RecipeImage(
                                path = recipe.imagePath ?: recipe.placeholderImagePath,
                                title = recipe.title,
                                imageLoader = imageLoader,
                                modifier = Modifier.fillMaxWidth().height(138.dp),
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                            ) {
                                Text(
                                    recipe.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                recipe.collectionMetadata?.let { metadata ->
                                    Text(
                                        metadata,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (recipe.keywords.isNotEmpty()) {
                                    Text(
                                        recipe.keywords.take(3).joinToString(" · "),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
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

@Composable
internal fun GenericRecipeDetailHeader(
    recipe: NativeRecipePresentation,
    imageLoader: NativeImageLoader?,
    baseServings: Double?,
    selectedServings: Double?,
    onSelectedServingsChange: ((Double) -> Unit)?,
) {
    RecipeImage(
        path = recipe.imagePath ?: recipe.placeholderImagePath,
        title = recipe.title,
        imageLoader = imageLoader,
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp).height(260.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text(
            recipe.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        val headerMetadata = if (baseServings == null) {
            recipe.collectionMetadata
        } else {
            listOfNotNull(recipe.category, recipe.totalTime).distinct()
                .joinToString(" · ")
                .takeIf(String::isNotBlank)
        }
        headerMetadata?.let { metadata ->
            Text(
                metadata,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (baseServings != null && selectedServings != null && onSelectedServingsChange != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${formatRecipeQuantity(selectedServings)} ${
                        if (kotlin.math.abs(selectedServings - 1.0) < 0.000_001) "serving" else "servings"
                    }",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(
                    enabled = selectedServings > 1.0,
                    onClick = { onSelectedServingsChange((selectedServings - 1.0).coerceAtLeast(1.0)) },
                ) {
                    Text("-")
                }
                OutlinedButton(
                    enabled = selectedServings < 999.0,
                    onClick = { onSelectedServingsChange((selectedServings + 1.0).coerceAtMost(999.0)) },
                ) {
                    Text("+")
                }
                if (kotlin.math.abs(selectedServings - baseServings) > 0.000_001) {
                    OutlinedButton(onClick = { onSelectedServingsChange(baseServings) }) {
                        Text("Reset")
                    }
                }
            }
            Text(
                "Ingredient amounts are adjusted for display only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun GenericRecipeStructuredSection(
    recipeId: String,
    section: NativeStructuredDetailSection,
    ingredientMultiplier: Double = 1.0,
) {
    val items = remember(section) { section.recipeTextItems() }.orEmpty()
    val ingredient = section.isRecipeIngredientSection()
    val checked = remember(recipeId, section.fieldId) { mutableStateMapOf<Int, Boolean>() }
    Text(
        section.label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = NextcloudSpacing.Medium,
                        vertical = NextcloudSpacing.XSmall,
                    ),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    if (ingredient) {
                        Checkbox(
                            checked = checked[index] == true,
                            onCheckedChange = { value -> checked[index] = value },
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraLarge,
                        ) {
                            Text(
                                "${index + 1}",
                                modifier = Modifier.padding(
                                    horizontal = NextcloudSpacing.Small,
                                    vertical = NextcloudSpacing.XSmall,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    if (ingredient) {
                        Text(
                            scaleRecipeIngredient(item, ingredientMultiplier),
                            modifier = Modifier.weight(1f).padding(top = NextcloudSpacing.XSmall),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        val instruction = remember(item) { nativeRecipeInstructionPresentation(item) }
                        Column(
                            modifier = Modifier.weight(1f).padding(top = NextcloudSpacing.XSmall),
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                        ) {
                            instruction.heading?.let { heading ->
                                Text(
                                    heading,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            instruction.body?.let { body ->
                                Text(body, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeImage(
    path: String?,
    title: String,
    imageLoader: NativeImageLoader?,
    modifier: Modifier,
) {
    var image by remember(path, imageLoader) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, imageLoader) {
        image = path?.let { relativePath ->
            imageLoader?.let { loader -> runCatching { loader.load(relativePath) }.getOrNull() }
        }
    }
    Surface(
        modifier = modifier,
        color = NextcloudTheme.colors.appIconContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            image?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } ?: Icon(
                NextcloudIcons.app("cookbook"),
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = NextcloudTheme.colors.appIcon,
            )
        }
    }
}
