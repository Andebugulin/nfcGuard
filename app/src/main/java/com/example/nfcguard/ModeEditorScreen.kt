package com.example.nfcguard

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeEditorScreen(
    mode: Mode,
    availableNfcTags: List<NfcTag>,
    onBack: () -> Unit,
    onSave: (List<String>, BlockMode, String?) -> Unit
) {
    val context = LocalContext.current
    var selectedApps by remember { mutableStateOf(mode.blockedApps.toSet()) }
    var blockMode by remember { mutableStateOf(mode.blockMode) }
    var selectedNfcTagId by remember { mutableStateOf(mode.nfcTagId) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val apps = loadInstalledApps(context).sortedBy { it.appName }
            withContext(Dispatchers.Main) {
                installedApps = apps
                isLoading = false
            }
        }
    }

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isEmpty()) installedApps
        else installedApps.filter { it.appName.contains(searchQuery, ignoreCase = true) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                }
                Text(
                    mode.name.uppercase(),
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        onSave(selectedApps.toList(), blockMode, selectedNfcTagId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Text("SAVE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }

            // Block mode selector
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { blockMode = BlockMode.BLOCK_SELECTED },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (blockMode == BlockMode.BLOCK_SELECTED) Color.White else Color(0xFF0A0A0A),
                        contentColor = if (blockMode == BlockMode.BLOCK_SELECTED) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("BLOCK", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Button(
                    onClick = { blockMode = BlockMode.ALLOW_SELECTED },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (blockMode == BlockMode.ALLOW_SELECTED) Color.White else Color(0xFF0A0A0A),
                        contentColor = if (blockMode == BlockMode.ALLOW_SELECTED) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ALLOW ONLY", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // NFC Tag Selector
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(0.dp),
                color = Color(0xFF0A0A0A)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Nfc,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "NFC TAG LOCK",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Optional: Require specific NFC tag to unlock",
                        fontSize = 10.sp,
                        color = Color(0xFF808080),
                        letterSpacing = 0.5.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    if (availableNfcTags.isEmpty()) {
                        Text(
                            "No NFC tags registered yet",
                            fontSize = 10.sp,
                            color = Color(0xFF606060),
                            letterSpacing = 1.sp
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // "No specific tag" option
                            Surface(
                                onClick = { selectedNfcTagId = null },
                                shape = RoundedCornerShape(0.dp),
                                color = if (selectedNfcTagId == null) Color.White else Color.Black
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "ANY TAG CAN UNLOCK",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedNfcTagId == null) Color.Black else Color.White,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (selectedNfcTagId == null) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.Black
                                        )
                                    }
                                }
                            }

                            // Available tags
                            availableNfcTags.forEach { tag ->
                                Surface(
                                    onClick = { selectedNfcTagId = tag.id },
                                    shape = RoundedCornerShape(0.dp),
                                    color = if (selectedNfcTagId == tag.id) Color.White else Color.Black
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            tag.name.uppercase(),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selectedNfcTagId == tag.id) Color.Black else Color.White,
                                            letterSpacing = 1.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (selectedNfcTagId == tag.id) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.Black
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // App search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("SEARCH APPS...", fontSize = 12.sp, letterSpacing = 1.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF0A0A0A),
                    unfocusedContainerColor = Color(0xFF0A0A0A),
                    focusedIndicatorColor = Color.White,
                    unfocusedIndicatorColor = Color(0xFF404040),
                    cursorColor = Color.White,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(16.dp))

            // Apps list
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("LOADING...", color = Color(0xFF404040), letterSpacing = 2.sp)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredApps.size, key = { filteredApps[it].packageName }) { index ->
                        AppItem(
                            app = filteredApps[index],
                            isSelected = selectedApps.contains(filteredApps[index].packageName),
                            onToggle = {
                                selectedApps = if (selectedApps.contains(filteredApps[index].packageName)) {
                                    selectedApps - filteredApps[index].packageName
                                } else {
                                    selectedApps + filteredApps[index].packageName
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppItem(app: AppInfo, isSelected: Boolean, onToggle: () -> Unit) {
    val imageBitmap = remember(app.packageName) { app.icon.asImageBitmap() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = if (isSelected) Color.White else Color(0xFF0A0A0A),
        onClick = onToggle
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(0.dp))
            )
            Spacer(Modifier.width(16.dp))
            Text(
                app.appName.uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.Black else Color.White,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black
                )
            }
        }
    }
}

data class AppInfo(
    val appName: String,
    val packageName: String,
    val icon: Bitmap
)

fun loadInstalledApps(context: Context): List<AppInfo> {
    return try {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.content.pm.PackageManager.MATCH_ALL
        } else {
            android.content.pm.PackageManager.GET_META_DATA
        }

        val resolveInfos = pm.queryIntentActivities(intent, flags)

        val apps = resolveInfos.mapNotNull { resolveInfo ->
            try {
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                val appName = resolveInfo.loadLabel(pm)?.toString() ?: packageName
                val drawable = resolveInfo.loadIcon(pm)
                val bitmap = drawable.toBitmap(96, 96)
                AppInfo(appName, packageName, bitmap)
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.packageName }

        apps
    } catch (e: Exception) {
        emptyList()
    }
}

fun Drawable.toBitmap(width: Int = intrinsicWidth, height: Int = intrinsicHeight): Bitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
    val w = if (width > 0) width else 1
    val h = if (height > 0) height else 1
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bitmap
}