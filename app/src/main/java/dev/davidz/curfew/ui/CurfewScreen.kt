@file:OptIn(ExperimentalMaterial3Api::class)

package dev.davidz.curfew.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.davidz.curfew.core.CurfewPrefs
import dev.davidz.curfew.core.CurfewSnapshot
import dev.davidz.curfew.core.LogEntry
import dev.davidz.curfew.core.LogType
import dev.davidz.curfew.core.PanicState
import dev.davidz.curfew.core.Phase
import dev.davidz.curfew.core.Policy
import dev.davidz.curfew.core.ScheduleEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class TimeTarget { START, END }

@Composable
fun CurfewScreen() {
    val context = LocalContext.current
    val prefs = remember { CurfewPrefs.get(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var snap by remember { mutableStateOf(Policy.snapshot(context)) }
    var setup by remember { mutableStateOf(Setup.items(context)) }
    var blocked by remember { mutableStateOf(prefs.blockedPackages) }
    var log by remember { mutableStateOf(prefs.log()) }
    var picking by remember { mutableStateOf<TimeTarget?>(null) }
    val apps = remember { Setup.installedCandidates(context) }

    fun refresh() {
        snap = Policy.snapshot(context)
        setup = Setup.items(context)
        blocked = prefs.blockedPackages
        log = prefs.log()
    }

    fun deny() {
        scope.launch {
            snackbar.showSnackbar("Locked while the curfew is running. Use the override.")
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(1000L)
        }
    }

    val locked = snap.phase == Phase.ENFORCING

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Column(
            modifier = Modifier
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header()

            StatusCard(
                snap = snap,
                enforcementLive = Setup.blockingIsLive(context),
                onPanic = {
                    Policy.startPanic(context)
                    refresh()
                },
                onRedeem = {
                    Policy.redeemPanic(context)
                    refresh()
                },
                onCancelPanic = {
                    Policy.cancelPanic(context)
                    refresh()
                },
            )

            val pending = setup.filter { !it.done }
            if (pending.isNotEmpty()) {
                SetupCard(pending) { item -> Setup.launch(context, item.intent(context)) }
            }

            ScheduleCard(
                snap = snap,
                locked = locked,
                onEdit = { target -> if (locked) deny() else picking = target },
            )

            AppsCard(
                apps = apps,
                blocked = blocked,
                onToggle = { pkg, on ->
                    if (!on && locked) {
                        deny()
                    } else {
                        prefs.setBlocked(pkg, on)
                        refresh()
                    }
                },
            )

            ArmCard(
                armed = snap.armed,
                locked = locked,
                onChange = { armed ->
                    if (locked) {
                        deny()
                    } else {
                        prefs.armed = armed
                        prefs.append(if (armed) LogType.ARMED else LogType.DISARMED)
                        refresh()
                    }
                },
            )

            LogCard(
                entries = log,
                canClear = !locked,
                onClear = {
                    if (locked) deny() else {
                        prefs.clearLog()
                        refresh()
                    }
                },
            )

            Text(
                text = "v0.1 MVP - fixed schedule, no unlock codes yet. Phase 2 adds the paired " +
                    "TOTP unlock.",
                color = TextFaint,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    picking?.let { target ->
        TimePickerDialog(
            initialMinuteOfDay = if (target == TimeTarget.START) snap.startMinute else snap.endMinute,
            title = if (target == TimeTarget.START) "Curfew starts" else "Curfew ends",
            onDismiss = { picking = null },
            onConfirm = { minuteOfDay ->
                if (target == TimeTarget.START) prefs.startMinute = minuteOfDay
                else prefs.endMinute = minuteOfDay
                picking = null
                refresh()
            },
        )
    }
}

// ---- sections ----------------------------------------------------------------------------

@Composable
private fun Header() {
    Column {
        Text(
            text = "CURFEW",
            color = Accent,
            fontSize = 13.sp,
            letterSpacing = 4.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "The blocker you cannot talk out of it",
            color = TextFaint,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun StatusCard(
    snap: CurfewSnapshot,
    enforcementLive: Boolean,
    onPanic: () -> Unit,
    onRedeem: () -> Unit,
    onCancelPanic: () -> Unit,
) {
    val (dot, title, detail) = when (snap.phase) {
        Phase.DISARMED -> Triple(
            TextFaint,
            "Disarmed",
            "Nothing is blocked. Arm it below.",
        )
        Phase.IDLE -> Triple(
            Good,
            "Armed",
            "Curfew ${ScheduleEngine.format(snap.startMinute)} - " +
                "${ScheduleEngine.format(snap.endMinute)}, starting in " +
                ScheduleEngine.formatDuration(snap.minutesUntilStart),
        )
        Phase.ENFORCING -> Triple(
            Danger,
            "Curfew active",
            "${snap.blockedCount} apps blocked until " +
                "${ScheduleEngine.format(snap.endMinute)}, " +
                "${ScheduleEngine.formatDuration(snap.minutesUntilEnd)} to go",
        )
        Phase.OVERRIDE -> Triple(
            Warn,
            "Override active",
            "%d:%02d left, then blocking resumes".format(
                snap.overrideSecondsLeft / 60,
                snap.overrideSecondsLeft % 60,
            ),
        )
    }

    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(dot, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(title, color = TextHigh, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text(detail, color = TextMuted, fontSize = 14.sp)

        if (!enforcementLive && snap.armed) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Not enforcing: the accessibility service is switched off.",
                color = Danger,
                fontSize = 13.sp,
            )
        }

        if (snap.phase == Phase.ENFORCING) {
            Spacer(Modifier.height(16.dp))
            when (snap.panic.state) {
                PanicState.NONE -> OutlinedButton(onClick = onPanic) {
                    Text("I really need this")
                }
                PanicState.COOLING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {}, enabled = false) {
                        Text(
                            "Unlock in %d:%02d".format(
                                snap.panic.secondsLeft / 60,
                                snap.panic.secondsLeft % 60,
                            ),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onCancelPanic) { Text("Never mind", color = TextFaint) }
                }
                PanicState.READY -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onRedeem,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Warn,
                            contentColor = AccentInk,
                        ),
                    ) {
                        Text("Unlock ${Policy.PANIC_GRANT_MINUTES} minutes")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onCancelPanic) { Text("Never mind", color = TextFaint) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Overrides cost 5 minutes of waiting and appear in the log.",
                color = TextFaint,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SetupCard(pending: List<SetupItem>, onOpen: (SetupItem) -> Unit) {
    Section(border = Warn) {
        Text("Setup", color = Warn, fontSize = 13.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        pending.forEachIndexed { index, item ->
            if (index > 0) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Outline)
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text = item.title + if (item.required) "" else "  (optional)",
                color = TextHigh,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(item.detail, color = TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onOpen(item) }) { Text(item.actionLabel) }
        }
    }
}

@Composable
private fun ScheduleCard(snap: CurfewSnapshot, locked: Boolean, onEdit: (TimeTarget) -> Unit) {
    Section {
        SectionTitle("Schedule")
        TimeRow("Starts", snap.startMinute) { onEdit(TimeTarget.START) }
        Spacer(Modifier.height(8.dp))
        TimeRow("Ends", snap.endMinute) { onEdit(TimeTarget.END) }
        Spacer(Modifier.height(10.dp))
        val length = ScheduleEngine.windowLength(snap.startMinute, snap.endMinute)
        Text(
            text = if (length == 0) {
                "Start and end are the same, so the window never opens."
            } else {
                ScheduleEngine.formatDuration(length) + " every night" +
                    if (locked) " - locked while the curfew is running" else ""
            },
            color = if (length == 0) Warn else TextFaint,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TimeRow(label: String, minuteOfDay: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(Surface2, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextMuted, fontSize = 14.sp)
        Text(
            text = ScheduleEngine.format(minuteOfDay),
            color = TextHigh,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun AppsCard(
    apps: List<InstalledApp>,
    blocked: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    Section {
        SectionTitle("Blocked apps")
        if (apps.isEmpty()) {
            Text(
                "None of the apps on the built-in list are installed.",
                color = TextMuted,
                fontSize = 14.sp,
            )
            return@Section
        }
        apps.forEach { app ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, color = TextHigh, fontSize = 15.sp)
                    Text(app.pkg, color = TextFaint, fontSize = 11.sp)
                }
                Switch(
                    checked = app.pkg in blocked,
                    onCheckedChange = { onToggle(app.pkg, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentInk,
                        checkedTrackColor = Accent,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ArmCard(armed: Boolean, locked: Boolean, onChange: (Boolean) -> Unit) {
    Section {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Armed", color = TextHigh, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = if (locked) {
                        "Cannot be switched off mid-curfew. That is the point."
                    } else {
                        "Enforce the schedule every night."
                    },
                    color = TextMuted,
                    fontSize = 13.sp,
                )
            }
            Switch(
                checked = armed,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AccentInk,
                    checkedTrackColor = Accent,
                ),
            )
        }
    }
}

private val LOG_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")

@Composable
private fun LogCard(entries: List<LogEntry>, canClear: Boolean, onClear: () -> Unit) {
    Section {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("Activity", bottomSpace = 0.dp)
            if (canClear && entries.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Clear", color = TextFaint) }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (entries.isEmpty()) {
            Text("Nothing yet.", color = TextMuted, fontSize = 14.sp)
            return@Section
        }
        entries.take(15).forEach { entry ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = formatTimestamp(entry.timestamp),
                    color = TextFaint,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(84.dp),
                )
                Text(
                    text = entry.type.label + if (entry.detail.isBlank()) "" else " - " + entry.detail,
                    color = colorForLog(entry.type),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

private fun formatTimestamp(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(LOG_FORMAT)

private fun colorForLog(type: LogType): Color = when (type) {
    LogType.PANIC_GRANTED, LogType.PANIC_STARTED -> Warn
    LogType.BLOCKED -> TextMuted
    LogType.DISARMED -> Danger
    else -> TextHigh
}

// ---- shared bits -------------------------------------------------------------------------

@Composable
private fun Section(border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        border = border?.let { BorderStroke(1.dp, it) },
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String, bottomSpace: Dp = 10.dp) {
    Text(text, color = TextMuted, fontSize = 12.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(bottomSpace))
}

@Composable
private fun TimePickerDialog(
    initialMinuteOfDay: Int,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = true,
    )
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, color = TextHigh, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                TimePicker(state = state)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                        Text("Set")
                    }
                }
            }
        }
    }
}
