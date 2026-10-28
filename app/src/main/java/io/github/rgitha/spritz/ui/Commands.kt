package io.github.rgitha.spritz.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Outcome { RUNNING, OK, FAILED }

/**
 * Runs device commands and tracks each one's outcome by key, so the control that
 * started it can show a spinner, then a tick or cross for [RESULT_MS].
 * Errors go to a toast. Commands may overlap; the device client runs them in turn.
 */
class Commands(private val scope: CoroutineScope, private val context: Context) {
    val outcomes = MutableStateFlow<Map<String, Outcome>>(emptyMap())

    /** Starts [block] unless the command [key] is already running. */
    fun run(key: String, block: suspend () -> Unit) {
        if (outcomes.value[key] == Outcome.RUNNING) return
        set(key, Outcome.RUNNING)
        scope.launch {
            val result = runCatching { block() }
            if (result.isSuccess) {
                set(key, Outcome.OK)
            } else {
                set(key, Outcome.FAILED)
                toast(result.exceptionOrNull()!!.userMessage())
            }
            delay(RESULT_MS)
            outcomes.update { if (it[key] == Outcome.RUNNING) it else it - key }
        }
    }

    /** Runs [block] without a visible outcome; failures still show a toast. */
    fun quiet(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { toast(it.userMessage()) } }
    }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private fun set(key: String, outcome: Outcome) = outcomes.update { it + (key to outcome) }

    companion object {
        const val RESULT_MS = 3_000L
    }
}

/** A small spinner, tick or cross; nothing when [outcome] is null. */
@Composable
fun OutcomeMark(outcome: Outcome?, modifier: Modifier = Modifier) {
    when (outcome) {
        null -> Unit
        Outcome.RUNNING -> CircularProgressIndicator(
            modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = LocalContentColor.current,
        )
        Outcome.OK -> Icon(Icons.Default.Check, contentDescription = "Done", modifier = modifier.size(18.dp))
        Outcome.FAILED -> Icon(
            Icons.Default.Close,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier.size(18.dp),
        )
    }
}
