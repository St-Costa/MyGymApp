package com.mygymapp.ui.components

import androidx.lifecycle.ViewModel
import com.mygymapp.data.polar.PolarManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HeartRateBarViewModel @Inject constructor(
    val polarManager: PolarManager,
) : ViewModel()
