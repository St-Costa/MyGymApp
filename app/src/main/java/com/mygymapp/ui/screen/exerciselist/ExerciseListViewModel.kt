package com.mygymapp.ui.screen.exerciselist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.DataChangedSignal
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.repository.ExerciseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExerciseListUiState(
    // Cardio exercises have no meaningful bodypart (see ExerciseEditScreen — the field is
    // hidden for them) — they'd otherwise land in a stray "Other"/blank group mixed
    // alphabetically among real muscle groups and be easy to miss. Kept as its own list,
    // always rendered as a fixed "Cardio" section at the top, ahead of the bodypart groups.
    val cardioExercises: List<Exercise> = emptyList(),
    val exercisesByBodypart: Map<String, List<Exercise>> = emptyMap(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    // User-selected type filter chip, below the search field — null means "all types".
    // Single-select: tapping the already-selected chip clears it back to null.
    val selectedType: ExerciseType? = null,
    // Every distinct bodypart currently present (post type-filter/search would churn this
    // list as you type, so it's derived from allExercises alone, not the filtered result) —
    // populates the bodypart dropdown's options. Sorted alphabetically.
    val availableBodyparts: List<String> = emptyList(),
    // User-selected bodypart filter (dropdown, below the type chips) — null means "all
    // bodyparts". Combines with selectedType/searchQuery (AND).
    val selectedBodypart: String? = null,
)

@HiltViewModel
class ExerciseListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val dataChangedSignal: DataChangedSignal,
) : ViewModel() {

    // "Switch exercise" filter (docs/CONVENTIONS.md#switch-exercise): when this screen is
    // reached as the filtered picker, these restrict the list to same-bodypart/same-type
    // candidates and hide exercises already occupying a slot in the current session. All
    // empty/blank for the unfiltered RoutineEdit picker — a strict no-op filter.
    private val bodypartFilter: String = savedStateHandle["bodypart"] ?: ""
    private val typeFilter: String = savedStateHandle["type"] ?: ""
    private val excludeIds: Set<String> =
        (savedStateHandle.get<String>("excludeIds") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private val _uiState = MutableStateFlow(ExerciseListUiState())
    val uiState: StateFlow<ExerciseListUiState> = _uiState

    private var allExercises: List<Exercise> = emptyList()

    init {
        loadExercises()
        viewModelScope.launch {
            dataChangedSignal.exercisesChanged.collect { loadExercises() }
        }
    }

    fun loadExercises() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            allExercises = exerciseRepository.getAll().filter { ex ->
                (bodypartFilter.isBlank() || ex.bodypart == bodypartFilter) &&
                    (typeFilter.isBlank() || ex.type == ExerciseType.fromString(typeFilter)) &&
                    ex.id !in excludeIds
            }
            _uiState.value = _uiState.value.copy(isLoading = false, availableBodyparts = computeAvailableBodyparts())
            applyFilter()
        }
    }

    // Cardio exercises carry no meaningful bodypart (see ExerciseListUiState doc on
    // cardioExercises) — excluded here so the dropdown only lists real muscle groups.
    private fun computeAvailableBodyparts(): List<String> = allExercises
        .filter { it.type != ExerciseType.CARDIO }
        .map { it.bodypart.ifBlank { "Other" } }
        .distinct()
        .sorted()

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyFilter()
    }

    /** Tapping the already-selected chip clears the filter back to "all types". */
    fun onTypeFilterChange(type: ExerciseType) {
        val current = _uiState.value.selectedType
        _uiState.value = _uiState.value.copy(selectedType = if (current == type) null else type)
        applyFilter()
    }

    /** Selecting the already-selected bodypart in the dropdown clears it back to "all". */
    fun onBodypartFilterChange(bodypart: String?) {
        val current = _uiState.value.selectedBodypart
        _uiState.value = _uiState.value.copy(selectedBodypart = if (current == bodypart) null else bodypart)
        applyFilter()
    }

    private fun applyFilter() {
        val query = _uiState.value.searchQuery.trim()
        val selectedType = _uiState.value.selectedType
        val selectedBodypart = _uiState.value.selectedBodypart
        val filtered = allExercises
            .filter { query.isBlank() || fuzzyMatch(query, it.name) }
            .filter { selectedType == null || it.type == selectedType }
            .filter { selectedBodypart == null || it.bodypart.ifBlank { "Other" } == selectedBodypart }
        val (cardio, rest) = filtered.partition { it.type == ExerciseType.CARDIO }
        // Within each bodypart group, stretch exercises come before strength ones.
        val grouped = rest
            .groupBy { it.bodypart.ifBlank { "Other" } }
            .mapValues { (_, exercises) -> exercises.sortedBy { it.type != ExerciseType.STRETCH } }
        _uiState.value = _uiState.value.copy(
            cardioExercises = cardio.sortedBy { it.name },
            exercisesByBodypart = grouped,
        )
    }

    fun deleteExercise(id: String) {
        viewModelScope.launch {
            exerciseRepository.delete(id)
            allExercises = allExercises.filter { it.id != id }
            _uiState.value = _uiState.value.copy(availableBodyparts = computeAvailableBodyparts())
            applyFilter()
        }
    }
}

/**
 * Permissive matching: case/accent-insensitive substring, or subsequence
 * (all query chars appear in order), so "panc" matches "Panca piana" and
 * "distpet" matches "Distensioni pettorali".
 */
private fun fuzzyMatch(query: String, name: String): Boolean {
    val q = query.normalizeForSearch()
    val n = name.normalizeForSearch()
    if (q.isEmpty()) return true
    if (n.contains(q)) return true
    // subsequence match
    var i = 0
    for (c in n) {
        if (i < q.length && c == q[i]) i++
    }
    return i == q.length
}

private fun String.normalizeForSearch(): String {
    val decomposed = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
    return decomposed
        .replace("\\p{Mn}+".toRegex(), "") // strip accents
        .lowercase()
        .filter { it.isLetterOrDigit() }
}
