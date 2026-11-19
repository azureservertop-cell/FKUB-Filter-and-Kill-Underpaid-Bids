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
)

/** 服务 → UI 的状态通道（进程内单例）。 */
object MonitorState {
    private val _status = MutableStateFlow(MonitorStatus())
    val status: StateFlow<MonitorStatus> = _status.asStateFlow()

    fun update(f: (MonitorStatus) -> MonitorStatus) = _status.update(f)
}
