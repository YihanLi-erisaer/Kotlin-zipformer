package com.example.kotlin_asr_with_ncnn.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ASRScreen(
    viewModel: ASRViewModel,
    onSettingsClick: () -> Unit = {}
) {
    val transcription by viewModel.transcriptionState.collectAsState()
    val isListening by viewModel.isListening.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isListening) "Result text" else "Press Start bottom to start",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = when {
                            isListening && (transcription?.text.isNullOrEmpty()) -> "Recording..."
                            else -> transcription?.text ?: ""
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.toggleListening() }
            ) {
                Text(text = if (isListening) "Stop" else "Start")
            }

            Spacer(modifier = Modifier.height(36.dp))

            Text(text = "Any AI model may make mistakes!", style = MaterialTheme.typography.labelSmall)
        }

        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
        ) {
            Text(text = "⚙", style = MaterialTheme.typography.titleLarge)
        }
    }
}
