package com.airpulse.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val Accent = Color(0xFF3DDC84)
private val Muted = Color(0xFFB0B0B0)
private val Panel = Color(0xFF161616)

var appEnglish by mutableStateOf(Locale.getDefault().language.startsWith("en"))

fun Context.t(id: Int, vararg formatArgs: Any): String {
    val config = Configuration(resources.configuration)
    config.setLocale(if (appEnglish) Locale.ENGLISH else Locale("bg"))
    return createConfigurationContext(config).getString(id, *formatArgs)
}

@Composable
fun ProvideLanguage(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(appEnglish, base) {
        val config = Configuration(base.resources.configuration)
        config.setLocale(if (appEnglish) Locale.ENGLISH else Locale("bg"))
        base.createConfigurationContext(config)
    }
    CompositionLocalProvider(LocalContext provides localized, content = content)
}

@Composable
fun AppTitleBar() {
    var about by remember { mutableStateOf(false) }
    if (about) AboutDialog { about = false }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("AirPulse", color = Accent, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            LanguageFlag()
            Text(
                "by Dr. Baysinger",
                color = Muted,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clickable { about = true },
            )
        }
    }
}

@Composable
private fun LanguageFlag() {
    Canvas(
        modifier = Modifier
            .clickable { appEnglish = !appEnglish }
            .padding(vertical = 8.dp)
            .size(width = 30.dp, height = 18.dp),
    ) {
        if (appEnglish) {
            drawUnionJack()
        } else {
            val band = size.height / 3f
            drawRect(Color.White, size = Size(size.width, band))
            drawRect(Color(0xFF00966E), topLeft = Offset(0f, band), size = Size(size.width, band))
            drawRect(Color(0xFFD62612), topLeft = Offset(0f, band * 2f), size = Size(size.width, band))
        }
        drawRect(Color(0xFF3A4250), style = Stroke(width = 1.dp.toPx()))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUnionJack() {
    val width = size.width
    val height = size.height
    drawRect(Color(0xFF012169))
    val white = Color.White
    val red = Color(0xFFC8102E)
    val whiteSaltire = height * 0.22f
    val redSaltire = height * 0.09f
    drawLine(white, Offset(0f, 0f), Offset(width, height), whiteSaltire, StrokeCap.Butt)
    drawLine(white, Offset(width, 0f), Offset(0f, height), whiteSaltire, StrokeCap.Butt)
    drawLine(red, Offset(0f, 0f), Offset(width, height), redSaltire, StrokeCap.Butt)
    drawLine(red, Offset(width, 0f), Offset(0f, height), redSaltire, StrokeCap.Butt)
    val whiteBar = height * 0.34f
    val redBar = height * 0.18f
    drawRect(white, topLeft = Offset((width - whiteBar) / 2f, 0f), size = Size(whiteBar, height))
    drawRect(white, topLeft = Offset(0f, (height - whiteBar) / 2f), size = Size(width, whiteBar))
    drawRect(red, topLeft = Offset((width - redBar) / 2f, 0f), size = Size(redBar, height))
    drawRect(red, topLeft = Offset(0f, (height - redBar) / 2f), size = Size(width, redBar))
}

private const val ABOUT_EN = "Donate to Dr. Baysinger. He promises to spend it on hookers and beer."
private const val ABOUT_BG = "Дарете на Dr. Baysinger. Той обещава да ги изхарчи за курви и бело."
private const val REVOLUT = "revolut.me/georgi09qc"
private val BTC = listOf(
    "bc1qr84akpfry7sjs6759wwq722fyert9apcarpst6",
    "bc1p6hp5q20u9t9upsjt7425wgqdrs0cr8wnnlf9atu24z92pzsnq85q62uc8e",
)

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Panel,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        confirmButton = {
            TextButton(onClick = onClose) { Text("OK", color = Accent) }
        },
        title = { Text("About", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(ABOUT_EN)
                Text(ABOUT_BG, modifier = Modifier.padding(top = 12.dp))
                Text("Revolut", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
                CopyLine(REVOLUT, context)
                Text("BTC address", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                BTC.forEach { CopyLine(it, context) }
            }
        },
    )
}

@Composable
private fun CopyLine(value: String, context: Context) {
    Text(
        value,
        color = Accent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("address", value))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 6.dp),
    )
}
