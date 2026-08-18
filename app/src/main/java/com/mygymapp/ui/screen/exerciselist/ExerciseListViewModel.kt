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
            _uiState.value = _uiState.value.copy(isLoading = false)
            applyFilter()
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyFilter()
    }

    private fun applyFilter() {
        val query = _uiState.value.searchQuery.trim()
        val filtered = if (query.isBlank()) {
            allExercises
        } else {
            allExercises.filter { fuzzyMatch(query, it.name) }
        }
        val (cardio, rest) = filtered.partition { it.type == ExerciseType.CARDIO }
        val grouped = rest.groupBy { it.bodypart.ifBlank { "Other" } }
        _uiState.value = _uiState.value.copy(
            cardioExercises = cardio.sortedBy { it.name },
            exercisesByBodypart = grouped,
        )
    }

    fun deleteExercise(id: String) {
        viewModelScope.launch {
            exerciseRepository.delete(id)
            allExercises = allExercises.filter { it.id != id }
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
