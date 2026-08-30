package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceField
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceOption
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedControl
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedOption

/** Real shared components with synthetic values, for checking both themes and window sizes. */
@Composable
internal fun MarketingSharedControlsScenario(scenario: MarketingCaptureScenario) {
    require(scenario == MarketingCaptureScenario.SharedControlsDesktop || scenario == MarketingCaptureScenario.SharedControlsMobile)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints {
            val wide = maxWidth >= 700.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 40.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Shared controls", style = MaterialTheme.typography.headlineMedium)
                    Text("The same controls in Calendar and dynamic apps.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    SharedViewExamples(Modifier.weight(1f))
                    SharedFieldExamples(Modifier.weight(1f))
                } else {
                    SharedViewExamples(Modifier.fillMaxWidth(), compact = true)
                    HorizontalDivider()
                    SharedFieldExamples(Modifier.fillMaxWidth(), compact = true)
                }
            }
        }
    }
}

@Composable
private fun SharedViewExamples(modifier: Modifier, compact: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)) {
        Text("Views and filters", style = MaterialTheme.typography.titleLarge)
        ExampleView("Calendar", listOf("Month", "Week", "Agenda"), "Month")
        ExampleView("Chores", listOf("Overview", "Chores", "Assignments", "History"), "Chores")
        if (!compact) {
            ExampleView("Budget", listOf("All 12", "Expenses 8", "Income 4"), "All 12", role = Role.RadioButton)
            ExampleView("Unavailable view", listOf("List", "Board", "Timeline"), "List", disabledId = "Timeline")
            Text("Use arrow keys to move focus, then Enter or Space to switch. On smaller screens, scroll or open the options menu.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExampleView(
    title: String, labels: List<String>, initial: String, role: Role = Role.Tab, disabledId: String? = null,
) {
    var selected by remember { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        NextcloudSegmentedControl(labels.map { NextcloudSegmentedOption(it, it, enabled = it != disabledId) },
            selected, { selected = it }, accessibilityLabel = "$title views", role = role)
    }
}

@Composable
private fun SharedFieldExamples(modifier: Modifier, compact: Boolean = false) {
    val statuses = listOf(NextcloudChoiceOption("planned", "Planned"), NextcloudChoiceOption("in_progress", "In progress"),
        NextcloudChoiceOption("complete", "Complete"))
    var status by remember { mutableStateOf<String?>("in_progress") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
        Text("Form choices", style = MaterialTheme.typography.titleLarge)
        NextcloudChoiceField("Status", statuses, status, { status = it })
        NextcloudChoiceField("Calendar", listOf(NextcloudChoiceOption("personal", "Personal calendar")), "personal", {}, enabled = false)
        NextcloudChoiceField("Project *", (1..12).map { NextcloudChoiceOption("project-$it", "Project $it") }, null, {},
            placeholder = "Choose a project", error = "Choose a project to continue.")
        if (!compact) Text("Long option lists include search. Disabled fields stay readable. Errors appear beside the field that needs attention.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
