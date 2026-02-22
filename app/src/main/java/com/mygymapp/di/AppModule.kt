package com.mygymapp.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Top-level Hilt module for the singleton component.
 *
 * Currently empty because all repository bindings use constructor injection
 * (@Inject constructor + @Singleton), which Hilt discovers automatically via KSP.
 * This module exists as the designated location for any future manual bindings
 * (e.g., providing third-party objects that cannot use @Inject).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
