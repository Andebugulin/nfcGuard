package com.example.nfcguard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: GuardianViewModel,
    onNavigate: (Screen) -> Unit
) {
    val appState by viewModel.appState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Column {
            Text(
                "GUARDIAN",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 2.sp
            )
            if (appState.activeModes.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${appState.activeModes.size} MODE${if (appState.activeModes.size > 1) "S" else ""} ACTIVE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF808080),
                    letterSpacing = 1.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Nfc,
                        contentDescription = null,
                        tint = Color(0xFF808080),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "TAP NFC TO UNLOCK",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF808080),
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Navigation Cards
        NavigationCard(
            title = "MODES",
            subtitle = "${appState.modes.size} CREATED",
            icon = Icons.Default.Block,
            onClick = { onNavigate(Screen.MODES) }
        )

        NavigationCard(
            title = "SCHEDULES",
            subtitle = "${appState.schedules.size} CONFIGURED",
            icon = Icons.Default.Schedule,
            onClick = { onNavigate(Screen.SCHEDULES) }
        )

        NavigationCard(
            title = "NFC TAGS",
            subtitle = "${appState.nfcTags.size} REGISTERED",
            icon = Icons.Default.Nfc,
            onClick = { onNavigate(Screen.NFC_TAGS) }
        )

        Spacer(Modifier.weight(1f))

        // Active Modes Summary
        if (appState.activeModes.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(0.dp),
                color = Color.White
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "ACTIVE NOW",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    appState.modes.filter { appState.activeModes.contains(it.id) }.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                mode.name.uppercase(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NavigationCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = Color(0xFF0A0A0A),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = Color(0xFF808080),
                    letterSpacing = 1.sp
                )
            }
            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                tint = Color(0xFF808080)
            )
        }
    }
}