package dev.pocketvm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketvm.runtime.AccelerationFacts
import dev.pocketvm.runtime.AccelerationPolicy
import dev.pocketvm.runtime.AccelerationProbe
import dev.pocketvm.runtime.AccelerationVerdict
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 诊断中心「硬件加速」卡：只读探测，不改执行方式（docs/roadmap.md §5）。 */
@HiltViewModel
class AccelerationViewModel @Inject constructor(
    private val probe: AccelerationProbe,
) : ViewModel() {

    data class State(
        val facts: AccelerationFacts? = null,
        val verdict: AccelerationVerdict? = null,
        val summary: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val facts = withContext(Dispatchers.IO) { probe.probe() }
            _state.value = State(
                facts = facts,
                verdict = AccelerationPolicy.verdict(facts),
                summary = AccelerationPolicy.summary(facts),
            )
        }
    }
}
