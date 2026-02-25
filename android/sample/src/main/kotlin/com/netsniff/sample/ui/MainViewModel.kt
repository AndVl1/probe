package com.netsniff.sample.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netsniff.sample.data.Film
import com.netsniff.sample.data.Person
import com.netsniff.sample.data.Planet
import com.netsniff.sample.data.StarWarsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val selectedTab: Tab = Tab.PEOPLE,
    val people: List<Person> = emptyList(),
    val films: List<Film> = emptyList(),
    val planets: List<Planet> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

enum class Tab { PEOPLE, FILMS, PLANETS }

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadPeople()
    }

    fun selectTab(tab: Tab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab, error = null)
        when (tab) {
            Tab.PEOPLE -> if (_uiState.value.people.isEmpty()) loadPeople()
            Tab.FILMS -> if (_uiState.value.films.isEmpty()) loadFilms()
            Tab.PLANETS -> if (_uiState.value.planets.isEmpty()) loadPlanets()
        }
    }

    fun refresh() {
        when (_uiState.value.selectedTab) {
            Tab.PEOPLE -> loadPeople()
            Tab.FILMS -> loadFilms()
            Tab.PLANETS -> loadPlanets()
        }
    }

    private fun loadPeople() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        StarWarsRepository.getPeople()
            .onSuccess { _uiState.value = _uiState.value.copy(people = it, isLoading = false) }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message, isLoading = false) }
    }

    private fun loadFilms() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        StarWarsRepository.getFilms()
            .onSuccess { films ->
                _uiState.value = _uiState.value.copy(
                    films = films.sortedBy { it.episodeId },
                    isLoading = false
                )
            }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message, isLoading = false) }
    }

    private fun loadPlanets() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        StarWarsRepository.getPlanets()
            .onSuccess { _uiState.value = _uiState.value.copy(planets = it, isLoading = false) }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message, isLoading = false) }
    }
}
