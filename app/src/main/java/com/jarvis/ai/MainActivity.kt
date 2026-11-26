package com.jarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                JarvisDashboard()
            }
        }
    }
}

@Composable
private fun JarvisDashboard() {
    val background = Color(0xFF050B16)
    val cyan = Color(0xFF45E6FF)

    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF10243A), background, Color(0xFF02050B))
                    )
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("J.A.R.V.I.S", color = cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("SYSTEM ONLINE", color = Color(0xFF8EF7C5), fontSize = 11.sp)
            }

            Spacer(Modifier.height(52.dp))

            Text("GOOD EVENING", color = Color.LightGray, fontSize = 13.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Text("How can I help?", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)

            Spacer(Modifier.height(36.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0x6645E6FF), Color(0x222A6D8F), Color.Transparent)
                        ),
                        RoundedCornerShape(120.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("◉", color = cyan, fontSize = 112.sp)
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "Your personal AI assistant is ready.",
                color = Color(0xFFB8C7D9),
                fontSize = 14.sp
            )

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = { },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087E9B)),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("START ASSISTANT", color = Color.White, modifier = Modifier.padding(8.dp))
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "VOICE • CHAT • IMAGE STUDIO",
                color = Color(0xFF6E8DA8),
                fontSize = 11.sp,
                letterSpacing = 2.sp
            )
        }
    }
}
