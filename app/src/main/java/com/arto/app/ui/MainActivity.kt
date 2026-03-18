package com.arto.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.arto.app.ui.theme.ARTOTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ARTOTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ArtoHome(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun ArtoHome(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "Arto — Spam Shield Active")
    }
}

@Preview(showBackground = true)
@Composable
fun ArtoHomePreview() {
    ARTOTheme {
        ArtoHome()
    }
}