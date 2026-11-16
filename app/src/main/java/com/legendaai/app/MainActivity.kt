package com.legendaai.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(this, ScreenTranslatorService::class.java).apply {
                action = ScreenTranslatorService.ACTION_START
                putExtra(ScreenTranslatorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenTranslatorService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, service)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LegendaAiScreen(
                onStartOverlay = {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } else {
                        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        captureLauncher.launch(manager.createScreenCaptureIntent())
                    }
                },
                onStopOverlay = {
                    startService(Intent(this, ScreenTranslatorService::class.java).setAction(ScreenTranslatorService.ACTION_STOP))
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegendaAiScreen(onStartOverlay: () -> Unit, onStopOverlay: () -> Unit) {
    val context = LocalContext.current
    var youtubeUrl by remember { mutableStateOf("") }
    val bg = Color(0xFF101116)
    val panel = Color(0xFF1B1D26)
    val accent = Color(0xFF9B8CFF)

    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = panel, onBackground = Color.White, onSurface = Color.White)) {
        Scaffold(containerColor = bg) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Translate, contentDescription = null, tint = accent, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("LegendaAI", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("Tradução visual de legendas", color = Color.LightGray, fontSize = 13.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1. Abra seu Gacha no YouTube", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        OutlinedTextField(value = youtubeUrl, onValueChange = { youtubeUrl = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Link do vídeo") }, placeholder = { Text("https://youtube.com/watch?v=...") })
                        Button(onClick = {
                            val raw = youtubeUrl.trim()
                            val uri = Uri.parse(if (raw.startsWith("http")) raw else "https://www.youtube.com/results?search_query=${Uri.encode(raw)}")
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }, modifier = Modifier.fillMaxWidth(), enabled = youtubeUrl.isNotBlank()) { Text("Abrir vídeo no YouTube") }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("2. Ative a tradução visual", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text("O Android pedirá autorização para compartilhar a tela. O LegendaAI vai procurar texto escrito dentro da imagem — não vai ouvir nem transcrever o áudio.", color = Color.LightGray, fontSize = 13.sp)
                        Button(onClick = onStartOverlay, modifier = Modifier.fillMaxWidth()) { Text("Ativar OCR + legenda em português") }
                        OutlinedButton(onClick = onStopOverlay, modifier = Modifier.fillMaxWidth()) { Text("Parar tradução flutuante") }
                        Text("Se for a primeira vez, permita 'Exibir sobre outros apps'. Depois, aceite a captura de tela. Volte ao YouTube e reproduza o Gacha.", color = Color(0xFFFFD58A), fontSize = 12.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("O que esta versão reconhece", fontWeight = FontWeight.SemiBold)
                        Text("• Legendas em inglês gravadas no vídeo\n• Balões de fala e textos grandes na imagem\n• Texto detectado na parte inferior da tela", color = Color.LightGray, fontSize = 13.sp)
                        Text("A primeira versão foca em inglês → português. Texto muito estilizado, pequeno, em movimento ou coberto por efeitos pode não ser reconhecido corretamente.", color = Color.LightGray, fontSize = 12.sp)
                    }
                }
                Text("Versão 0.2.0 • OCR visual experimental", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}
