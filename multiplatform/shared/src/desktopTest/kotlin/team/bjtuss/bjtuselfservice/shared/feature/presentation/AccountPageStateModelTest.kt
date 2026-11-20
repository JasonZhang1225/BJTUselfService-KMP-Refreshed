package team.bjtuss.bjtuselfservice.shared.feature.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeUiState
import team.bjtuss.bjtuselfservice.shared.runtime.AccountWork

class AccountPageStateModelTest {
    @Test fun oneAccountProducerUpdatesWithoutAnyUiCollectorAndMultipleReadersDoNotResubscribeToSources() = runBlocking {
        val grades = MutableStateFlow(GradeUiState())
        val owner = AccountWork()
        val model = AccountPageStateModel(PresentationSources(grades = grades), owner.scope(Dispatchers.Default), owner)
        val readers = mutableListOf<Job>()
        try {
            withTimeout(5000) { grades.subscriptionCount.first { it == 1 } }
            grades.value = grades.value.copy(isRefreshing = true)
            withTimeout(5000) { while (!model.state.value.inputs.gradeState.isRefreshing) delay(1) }
            repeat(2) { readers += launch { model.state.collect() } }
            yield()
            assertEquals(1, grades.subscriptionCount.value)
            readers.forEach { it.cancelAndJoin() }
            grades.value = grades.value.copy(isRefreshing = false)
            withTimeout(5000) { while (model.state.value.inputs.gradeState.isRefreshing) delay(1) }
            assertEquals(1, grades.subscriptionCount.value)
        } finally { readers.forEach { it.cancelAndJoin() }; owner.stop(); owner.join() }
        assertEquals(0, grades.subscriptionCount.value)
        val frozen = model.state.value
        grades.value = grades.value.copy(isRefreshing = true)
        assertSame(frozen, model.state.value)
    }

    @Test fun initialSnapshotIncludesPreloadedSourcesAndLaterAccountHasIndependentState() = runBlocking {
        val grades = MutableStateFlow(GradeUiState(isLoading = false, isRefreshing = true))
        val first = AccountWork()
        val model = AccountPageStateModel(PresentationSources(grades = grades), first.scope(Dispatchers.Default), first)
        assertTrue(model.state.value.inputs.gradeState.isRefreshing)
        first.stop(); first.join()
        val second = AccountWork()
        try {
            val fresh = AccountPageStateModel(PresentationSources(), second.scope(Dispatchers.Default), second)
            assertNotSame(model, fresh)
            assertFalse(fresh.state.value.inputs.gradeState.isRefreshing)
            grades.value = grades.value.copy(isLoading = true)
            assertFalse(fresh.state.value.inputs.gradeState.isRefreshing)
        } finally { second.stop(); second.join() }
    }
}
