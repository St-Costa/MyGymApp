package com.mygymapp.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton bus used by edit ViewModels to notify list ViewModels that
 * exercises or routines have been saved, so they can reload fresh data.
 *
 * emit → after exerciseRepository.save() or routineRepository.save() completes
 * collect → in ExerciseListViewModel, RoutineListViewModel, WeekViewViewModel, MainViewModel
 */
@Singleton
class DataChangedSignal @Inject constructor() {

    private val _exercisesChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exercisesChanged: SharedFlow<Unit> = _exercisesChanged.asSharedFlow()

    private val _routinesChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val routinesChanged: SharedFlow<Unit> = _routinesChanged.asSharedFlow()

    fun notifyExercisesChanged() { _exercisesChanged.tryEmit(Unit) }
    fun notifyRoutinesChanged() { _routinesChanged.tryEmit(Unit) }
}
