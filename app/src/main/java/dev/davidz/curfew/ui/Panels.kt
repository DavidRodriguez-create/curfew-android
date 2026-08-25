package dev.davidz.curfew.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The card the whole app screen is made of. It lived inside `CurfewScreen.kt` as a private
 * `Section` until the pairing screen needed the same chrome; it is here now so there is one
 * card, not two that drift apart.
 */
@Composable
fun PanelCard(border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
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
fun PanelTitle(text: String, bottomSpace: Dp = 10.dp) {
    Text(text, color = TextMuted, fontSize = 12.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(bottomSpace))
}
