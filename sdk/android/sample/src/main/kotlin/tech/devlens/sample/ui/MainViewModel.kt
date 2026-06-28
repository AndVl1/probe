package tech.devlens.sample.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import tech.devlens.sample.SampleApplication
import tech.devlens.sample.data.Film
import tech.devlens.sample.data.Person
import tech.devlens.sample.data.Planet
import tech.devlens.sample.data.StarWarsRepository
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

enum class Tab { PEOPLE, FILMS, PLANETS, PREFS }

class MainViewModel(application: Application) : AndroidViewModel(application) {

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
            Tab.PREFS -> { /* no remote load; the panel is interactive */ }
        }
    }

    fun refresh() {
        when (_uiState.value.selectedTab) {
            Tab.PEOPLE -> loadPeople()
            Tab.FILMS -> loadFilms()
            Tab.PLANETS -> loadPlanets()
            Tab.PREFS -> { /* nothing to refresh */ }
        }
    }

    // ── Preferences panel ──────────────────────────────────────────────────────

    /**
     * Writes one of each SharedPreferences value type (String/Int/Long/Float/
     * Boolean/StringSet) into the demo file. Triggers PreferencesPlugin change
     * events that stream to the CLI. Uses `apply()` — the snapshot + listener
     * path stays non-blocking from the UI thread's perspective.
     */
    fun writePref() {
        getApplication<SampleApplication>()
            .getSharedPreferences(DEMO_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("sample_string", "hello-${System.currentTimeMillis()}")
            .putInt("sample_int", 42)
            .putLong("sample_long", 1234567890L)
            .putFloat("sample_float", 3.14f)
            .putBoolean("sample_boolean", true)
            .putStringSet("sample_set", setOf("alpha", "bravo", "charlie"))
            .apply()
    }

    /** Removes a single key so the plugin emits an `op:"remove"` event. */
    fun removePref() {
        getApplication<SampleApplication>()
            .getSharedPreferences(DEMO_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("sample_string")
            .apply()
    }

    /** Clears the whole demo file so the plugin emits an `op:"clear"` event. */
    fun clearPrefs() {
        getApplication<SampleApplication>()
            .getSharedPreferences(DEMO_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /**
     * Creates a prefs file that did NOT exist at Probe.install() time, then
     * registers it with the plugin via its escape hatch. The next snapshot the
     * CLI receives will include `late_created_prefs`.
     */
    fun registerLatePrefs() {
        val app = getApplication<SampleApplication>()
        app.getSharedPreferences(LATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("created_at", "run-${System.currentTimeMillis()}")
            .apply()
        SampleApplication.prefsPlugin.registerPrefs(LATE_PREFS)
    }

    // ── Star Wars data ─────────────────────────────────────────────────────────

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

    private companion object {
        const val DEMO_PREFS = "probe_demo"
        const val LATE_PREFS = "late_created_prefs"
    }
}
