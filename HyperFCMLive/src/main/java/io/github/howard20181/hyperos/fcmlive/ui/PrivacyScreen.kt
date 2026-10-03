package io.github.howard20181.hyperos.fcmlive.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.howard20181.hyperos.fcmlive.R
import io.github.howard20181.hyperos.fcmlive.theme.HyperFCMLiveTheme

/**
 * Privacy & permissions page — the first screen in this app that is Compose
 * end to end: top bar, scrolling body and safe-area handling all live here,
 * with no XML around them.
 *
 * The body is the settings page's card language: section headings over a
 * connected group of rows ([GroupRow]), icons and titles first, prose as the
 * subtitle. The rows are static — [GroupRow] takes a null [GroupRow.onClick]
 * there, so nothing pretends to be a button.
 */
@Composable
fun PrivacyScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        // `surface` is what the bridge maps the page background onto, which is
        // the View-layer `md_page_bg` — one step below the cards.
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(titleRes = R.string.about_section_privacy, onBack = onBack) }
    ) { innerPadding ->
        PrivacyBody(
            bottomPadding = innerPadding.calculateBottomPadding(),
            modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
        )
    }
}

@Composable
private fun PrivacyBody(
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp),
        // The gesture-hint strip is scroll-through, not a hard stop.
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        item {
            SectionTitle(R.string.privacy_section_perms, first = true)
            PermRow(
                iconRes = R.drawable.ic_search,
                titleRes = R.string.privacy_perm_query_all,
                descRes = R.string.privacy_perm_query_all_desc,
                first = true,
                last = false
            )
            PermRow(
                iconRes = R.drawable.ic_apps,
                titleRes = R.string.privacy_perm_get_installed,
                descRes = R.string.privacy_perm_get_installed_desc,
                first = false,
                last = false
            )
            PermRow(
                iconRes = R.drawable.ic_wifi,
                titleRes = R.string.privacy_perm_internet,
                descRes = R.string.privacy_perm_internet_desc,
                first = false,
                last = true
            )
        }
        item {
            SectionTitle(R.string.privacy_section_commitment)
            GroupRow(first = true, last = true, onClick = null) {
                Text(
                    text = stringResource(R.string.privacy_commitment_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * One permission as a settings-style card row: official glyph, name, and the
 * permission string with its scope as the subtitle. Static on purpose —
 * a permission is stated, not toggled.
 */
@Composable
private fun PermRow(
    iconRes: Int,
    titleRes: Int,
    descRes: Int,
    first: Boolean,
    last: Boolean
) {
    GroupRow(first = first, last = last, onClick = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(painter = painterResource(iconRes))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(descRes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Preview(name = "Privacy — light", showBackground = true)
@Preview(
    name = "Privacy — dark intent",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun PrivacyScreenPreview() {
    HyperFCMLiveTheme {
        Surface {
            PrivacyScreen(onBack = {})
        }
    }
}

