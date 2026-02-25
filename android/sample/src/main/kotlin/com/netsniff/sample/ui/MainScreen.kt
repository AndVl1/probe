@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.netsniff.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.netsniff.sample.data.Film
import com.netsniff.sample.data.Person
import com.netsniff.sample.data.Planet
import com.netsniff.sample.ui.theme.NetSniffSampleTheme

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    NetSniffSampleTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("NetSniff Sample", fontWeight = FontWeight.Bold)
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
