package dev.davidz.curfew.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.davidz.curfew.core.CurfewSnapshot
import dev.davidz.curfew.core.Pairing
import dev.davidz.curfew.core.TotpVerifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PAIRED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * The half of v0.2 that is not cryptography: making the shared secret exist, and making it
 * recoverable when the approver's phone forgets it.
 */
@Composable
fun PairingCard(
    snap: CurfewSnapshot,
    pairedAt: Long,
    locked: Boolean,
    onPair: () -> Unit,
    onShowRecovery: () -> Unit,
    onUnpair: () -> Unit,
    onLocked: () -> Unit,
) {
    PanelCard {
        PanelTitle("Approver")

        if (!snap.paired) {
            Text(
                text = "Nobody holds the other half of the secret yet, so there are no unlock " +
                    "codes — only the panic override.",
                color = TextMuted,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { if (locked) onLocked() else onPair() }) { Text("Pair an approver") }
            return@PanelCard
        }

        Text(
            text = "Paired" + if (pairedAt > 0L) {
                " on " + Instant.ofEpochMilli(pairedAt)
                    .atZone(ZoneId.systemDefault()).format(PAIRED_FORMAT)
            } else {
                ""
            },
            color = TextHigh,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Codes are generated on their phone and verified here. Nothing is sent " +
                "anywhere — the two devices only ever share the secret you paired.",
            color = TextMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (locked) onLocked() else onShowRecovery() }) {
                Text("Show recovery string", color = Accent)
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { if (locked) onLocked() else onUnpair() }) {
                Text("Re-pair", color = TextFaint)
            }
        }
        if (locked) {
            Text(
                text = "Locked while the curfew is running. Re-pairing mid-curfew would mean " +
                    "approving your own unlocks.",
                color = TextFaint,
                fontSize = 12.sp,
            )
        }
    }
}

/** Six digits, in the app rather than on the shield. Same verifier, same replay window. */
@Composable
fun UnlockCard(
    snap: CurfewSnapshot,
    message: Pair<String, Boolean>?,
    onSubmit: (String) -> Unit,
) {
    var entry by remember { mutableStateOf("") }

    PanelCard {
        PanelTitle("Unlock with a code")

        when {
            snap.clockTampered -> {
                Text(
                    text = "The phone clock has been changed by " +
                        "${snap.clockSkewSeconds / 60} minutes. Codes are refused until the " +
                        "curfew ends and the clock can be trusted again.",
                    color = Danger,
                    fontSize = 14.sp,
                )
                return@PanelCard
            }
            snap.unlockLockedSeconds > 0 -> {
                Text(
                    text = "Too many wrong codes. Try again in " +
                        "%d:%02d.".format(snap.unlockLockedSeconds / 60, snap.unlockLockedSeconds % 60),
                    color = Warn,
                    fontSize = 14.sp,
                )
                return@PanelCard
            }
        }

        Text(
            text = "Ask for 15, 30 or 60 minutes. The length is part of the code, so it cannot " +
                "be stretched on this end.",
            color = TextMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = entry,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(TotpVerifier.DIGITS)
                    entry = digits
                    if (digits.length == TotpVerifier.DIGITS) {
                        onSubmit(digits)
                        entry = ""
                    }
                },
                singleLine = true,
                label = { Text("6-digit code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = {
                    onSubmit(entry)
                    entry = ""
                },
                enabled = entry.length == TotpVerifier.DIGITS,
            ) { Text("Unlock") }
        }
        message?.let { (text, isError) ->
            Spacer(Modifier.height(8.dp))
            Text(text, color = if (isError) Danger else Good, fontSize = 13.sp)
        }
    }
}

/** Shown once, right after generating a secret: the QR the approver scans, and the fallback. */
@Composable
fun PairDialog(secret: ByteArray, onDone: () -> Unit) {
    val uri = remember(secret) { Pairing.pairingUri(secret) }
    val qr = remember(uri) { QrCode.bitmap(uri, QR_PIXELS) }

    Dialog(onDismissRequest = onDone) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Scan this on their phone", color = TextHigh, fontSize = 17.sp)
                Spacer(Modifier.height(14.dp))
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "Pairing QR code",
                        modifier = Modifier
                            .size(220.dp)
                            .background(TextHigh, RoundedCornerShape(8.dp))
                            .padding(6.dp),
                    )
                } else {
                    Text(
                        "The QR could not be drawn. Type the string below instead.",
                        color = Warn,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("or type it in", color = TextFaint, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                RecoveryText(Pairing.recoveryString(secret))
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "You can bring this back later from Show recovery string, so a wiped " +
                        "browser on their side is not the end of it.",
                    color = TextFaint,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDone) { Text("Done") }
                }
            }
        }
    }
}

@Composable
fun RecoveryDialog(recovery: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Recovery string", color = TextHigh, fontSize = 17.sp)
                Spacer(Modifier.height(10.dp))
                RecoveryText(recovery)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "This is the shared secret itself. Anyone who has it can approve " +
                        "their own unlocks — read it out only to the person holding the other half.",
                    color = Warn,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, color = TextHigh, fontSize = 17.sp)
                Spacer(Modifier.height(8.dp))
                Text(body, color = TextMuted, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Warn,
                            contentColor = AccentInk,
                        ),
                    ) { Text(confirmLabel) }
                }
            }
        }
    }
}

@Composable
private fun RecoveryText(recovery: String) {
    Text(
        text = recovery,
        color = TextHigh,
        fontSize = 16.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(Surface2, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

private const val QR_PIXELS = 512
