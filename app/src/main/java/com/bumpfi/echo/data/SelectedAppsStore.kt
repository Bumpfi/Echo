package com.bumpfi.echo.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simple in-memory store for selected apps. Replace with DataStore for persistence if needed.
 */
class SelectedAppsStore() {
    // In-memory for now; replace with DataStore for persistence
    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackages: Flow<Set<String>> = _selectedPackages.asStateFlow()

    val current: Set<String> get() = _selectedPackages.value

    fun setSelectedPackages(packages: Set<String>) {
        _selectedPackages.value = packages
    }

}
