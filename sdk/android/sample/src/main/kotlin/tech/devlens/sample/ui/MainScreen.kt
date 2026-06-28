@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tech.devlens.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.devlens.sample.data.Film
import tech.devlens.sample.data.Person
import tech.devlens.sample.data.Planet
import tech.devlens.sample.ui.theme.ProbeSampleTheme

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ProbeSampleTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Probe Sample", fontWeight = FontWeight.Bold)
                            Text("Star Wars API", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        TextButton(onClick = { viewModel.refresh() }) {
                            Text("Refresh")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = uiState.selectedTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            label = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            icon = {}
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when {
                    uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    uiState.error != null -> ErrorView(uiState.error!!, onRetry = { viewModel.refresh() })
                    else -> when (uiState.selectedTab) {
                        Tab.PEOPLE -> PeopleList(uiState.people)
                        Tab.FILMS -> FilmsList(uiState.films)
                        Tab.PLANETS -> PlanetsList(uiState.planets)
                        Tab.PREFS -> PrefsPanel(
                            onWritePref = { viewModel.writePref() },
                            onRemovePref = { viewModel.removePref() },
                            onClearPrefs = { viewModel.clearPrefs() },
                            onRegisterLatePrefs = { viewModel.registerLatePrefs() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorView(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Error: $error", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun PeopleList(people: List<Person>) {
    LazyColumn {
        items(people) { person ->
            ListItem(
                headlineContent = { Text(person.name, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Text("${person.birthYear} • ${person.gender} • Height: ${person.height}cm")
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun FilmsList(films: List<Film>) {
    LazyColumn {
        items(films) { film ->
            ListItem(
                headlineContent = {
                    Text(
                        "Episode ${film.episodeId}: ${film.title}",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                supportingContent = {
                    Column {
                        Text("Director: ${film.director}")
                        Text("Released: ${film.releaseDate}")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            film.openingCrawl.take(150) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun PlanetsList(planets: List<Planet>) {
    LazyColumn {
        items(planets) { planet ->
            ListItem(
                headlineContent = { Text(planet.name, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Text("Climate: ${planet.climate} • Terrain: ${planet.terrain} • Pop: ${planet.population}")
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun PrefsPanel(
    onWritePref: () -> Unit,
    onRemovePref: () -> Unit,
    onClearPrefs: () -> Unit,
    onRegisterLatePrefs: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Preferences", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Write / remove / clear values in 'probe_demo', or register a prefs " +
                "file created at runtime. Each action streams an event to the CLI.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onWritePref, modifier = Modifier.fillMaxWidth()) {
            Text("Write prefs (all 6 types)")
        }
        Button(onClick = onRemovePref, modifier = Modifier.fillMaxWidth()) {
            Text("Remove 'sample_string'")
        }
        Button(onClick = onClearPrefs, modifier = Modifier.fillMaxWidth()) {
            Text("Clear 'probe_demo'")
        }
        Button(onClick = onRegisterLatePrefs, modifier = Modifier.fillMaxWidth()) {
            Text("Register 'late_created_prefs'")
        }
    }
}
