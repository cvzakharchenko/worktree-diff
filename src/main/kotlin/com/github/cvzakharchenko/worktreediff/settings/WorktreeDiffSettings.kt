package com.github.cvzakharchenko.worktreediff.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@Service(Service.Level.PROJECT)
@State(
    name = "WorktreeDiffSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class WorktreeDiffSettings : SimplePersistentStateComponent<WorktreeDiffSettings.State>(State()) {

    var includeLocalChanges: Boolean
        get() = state.includeLocalChanges
        set(value) {
            state.includeLocalChanges = value
        }

    var ignoreLineEndings: Boolean
        get() = state.ignoreLineEndings
        set(value) {
            state.ignoreLineEndings = value
        }

    var ignoreStagedChanges: Boolean
        get() = state.ignoreStagedChanges
        set(value) {
            state.ignoreStagedChanges = value
        }

    var ignoreHeadChanges: Boolean
        get() = state.ignoreHeadChanges
        set(value) {
            state.ignoreHeadChanges = value
        }

    class State : BaseState() {
        var includeLocalChanges by property(false)
        var ignoreLineEndings by property(true)
        var ignoreStagedChanges by property(false)
        var ignoreHeadChanges by property(false)
    }
}
