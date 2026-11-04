package io.cocode.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.cocode.app.R

@Composable
internal fun CoxSettingsSection(
    enabled: Boolean,
    modelCenterAvailable: Boolean = true,
    onOpen: (CoxSettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.cox_settings_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.cox_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { onOpen(CoxSettingsPage.Models) }, enabled = enabled && modelCenterAvailable, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cox_model_center))
            }
            OutlinedButton(onClick = { onOpen(CoxSettingsPage.Agents) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cox_agent_settings))
            }
            Text(
                stringResource(when {
                    !enabled -> R.string.cox_settings_setup_hint
                    !modelCenterAvailable -> R.string.cox_settings_update_hint
                    else -> R.string.cox_settings_start_hint
                }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
