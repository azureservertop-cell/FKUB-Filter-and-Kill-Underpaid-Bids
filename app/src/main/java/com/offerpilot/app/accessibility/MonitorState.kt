package com.offerpilot.app.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LastRecognition(
    val timeMs: Long,
    val success: Boolean,
    val summary: String,
    val detail: String,
)

data class MonitorStatus(
    val serviceConnected: Boolean = false,
    val targetInForeground: Boolean = false,
    val lastRecognition: LastRecognition? = null,
    val lastAdapterNote: String? = null,
    val lastSnapshotPath: String? = null,
    val snapshotCountdownUntil: Long? = null,
    /** Continuous capture: the session currently being recorded. */
    val recording: RecordingStatus? = null,
)

data class RecordingStatus(val sessionName: String, val startedAt: Long, val count: Int, val maxCount: Int)

/** Service → UI state channel (in-process singleton). */
object MonitorState {
    private val _status = MutableStateFlow(MonitorStatus())
    val status: StateFlow<MonitorStatus> = _status.asStateFlow()

    fun update(f: (MonitorStatus) -> MonitorStatus) = _status.update(f)
}
