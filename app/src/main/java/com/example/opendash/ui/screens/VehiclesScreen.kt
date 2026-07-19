package com.example.opendash.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.example.opendash.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.BtnSize
import com.example.opendash.ui.components.BtnVariant
import com.example.opendash.ui.components.OpenDashBtn
import com.example.opendash.ui.components.OpenDashCard
import com.example.opendash.ui.components.OpenDashDivider
import com.example.opendash.ui.components.OpenDashIconBtn
import com.example.opendash.ui.components.OpenDashChip
import com.example.opendash.ui.components.ChipTone
import com.example.opendash.ui.components.ScreenHeader
import com.example.opendash.data.VehicleProfile
import com.example.opendash.data.VehicleStore
import com.example.opendash.ui.theme.GeistFamily
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Vehicle info + management, embedded as a section of the Garage screen (the active
 * vehicle leads; others follow with "Set current"). Formerly its own bottom tab.
 */
@Composable
fun VehiclesSection() {
    val context = LocalContext.current
    val vehicles by VehicleStore.vehicles.collectAsState()
    val activeVehicleId by VehicleStore.activeVehicleId.collectAsState()
    var editingVehicleId by remember { mutableStateOf<String?>(null) }
    var addingVehicle by remember { mutableStateOf(false) }

    // Active vehicle first — its info is what Garage is about.
    val ordered = remember(vehicles, activeVehicleId) {
        vehicles.sortedByDescending { it.id == activeVehicleId }
    }

    OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        ordered.forEachIndexed { index, vehicle ->
            if (index > 0) OpenDashDivider(Modifier.padding(vertical = 14.dp))
            VehicleBlock(
                vehicle = vehicle,
                active = vehicle.id == activeVehicleId,
                onSelect = { VehicleStore.select(context, vehicle.id) },
                onEdit = { editingVehicleId = vehicle.id },
            )
        }

        Spacer(Modifier.height(14.dp))
        OpenDashBtn(
            "Add vehicle",
            onClick = { addingVehicle = true },
            icon = OpenDashIcons.Plus,
            variant = BtnVariant.Secondary,
            size = BtnSize.Sm,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    editingVehicleId?.let { vehicleId ->
        val vehicle = vehicles.firstOrNull { it.id == vehicleId } ?: return@let
        EditVehicleDialog(
            dialogTitle = "Edit vehicle",
            vehicle = vehicle,
            onDismiss = { editingVehicleId = null },
            onSave = { updated ->
                VehicleStore.update(context, updated)
                editingVehicleId = null
            },
        )
    }

    if (addingVehicle) {
        EditVehicleDialog(
            dialogTitle = "Add vehicle",
            vehicle = VehicleProfile(
                id = "",
                title = "",
                nickname = "",
                puc = "Not set",
                insurance = "Not set",
                service = "Not set",
            ),
            onDismiss = { addingVehicle = false },
            onSave = { updated ->
                VehicleStore.add(context, updated)
                addingVehicle = false
            },
        )
    }
}

fun getBikeDefaultDrawable(title: String): Int {
    val t = title.lowercase()
    return when {
        t.contains("himalayan 450") -> R.drawable.default_re_himalayan_450
        t.contains("himalayan 411") -> R.drawable.default_re_himalayan_411
        t.contains("bullet 350") -> R.drawable.default_re_bullet_350
        t.contains("classic 350") -> R.drawable.default_re_classic_350
        t.contains("hunter 350") -> R.drawable.default_re_hunter_350
        t.contains("390 adventure") -> R.drawable.default_ktm_390_adv
        t.contains("250 adventure") -> R.drawable.default_ktm_250_adv
        t.contains("v-strom") || t.contains("xstorm") -> R.drawable.default_suzuki_vstrom
        t.contains("xpulse 210") -> R.drawable.default_hero_xpulse_210
        t.contains("xpulse") -> R.drawable.default_hero_xpulse_200
        t.contains("rtx 300") -> R.drawable.default_tvs_rtx300
        t.contains("g 310 gs") -> R.drawable.default_bmw_g310gs
        t.contains("f 450 gs") -> R.drawable.default_bmw_f450gs
        t.contains("cb350rs") -> R.drawable.default_honda_cb350rs
        t.contains("hness") || t.contains("h'ness") || t.contains("hiness") -> R.drawable.default_honda_hness
        t.contains("yezdi") -> R.drawable.default_yezdi_adv
        t.contains("scrambler") -> R.drawable.default_triumph_scrambler
        t.contains("speed 400") -> R.drawable.default_triumph_speed
        else -> R.drawable.default_re_himalayan_450
    }
}

@Composable
private fun VehicleBlock(
    vehicle: VehicleProfile,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    val profileImg = remember(vehicle.profileIcon) {
        if (vehicle.profileIcon != "default") {
            runCatching { BitmapFactory.decodeFile(vehicle.profileIcon)?.asImageBitmap() }.getOrNull()
        } else null
    }

    Row(verticalAlignment = Alignment.Top) {
        val defaultDrawable = remember(vehicle.title) { getBikeDefaultDrawable(vehicle.title) }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 5.dp)
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            if (profileImg != null) {
                Image(
                    bitmap = profileImg,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Image(
                    painter = painterResource(id = defaultDrawable),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(vehicle.title, color = MaterialTheme.colorScheme.primary, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            if (vehicle.nickname.isNotBlank()) Text(vehicle.nickname, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(14.dp))
            VehicleMeta("PUC", vehicle.puc, alert = vehicle.puc.isProblemValue())
            VehicleMeta("Insurance", vehicle.insurance, alert = vehicle.insurance.isProblemValue())
            VehicleMeta("Service", vehicle.service)
            Spacer(Modifier.height(12.dp))
            if (active) {
                OpenDashChip("Current vehicle", ChipTone.Gold, icon = OpenDashIcons.Check)
            } else {
                OpenDashBtn(
                    "Set current",
                    onClick = onSelect,
                    icon = OpenDashIcons.Check,
                    variant = BtnVariant.Secondary,
                    size = BtnSize.Sm,
                )
            }
        }
        OpenDashIconBtn(OpenDashIcons.Edit, onClick = onEdit, size = 34.dp)
    }
}

@Composable
private fun VehicleMeta(label: String, value: String, alert: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Text(":", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(value, color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

private val PREDEFINED_VEHICLES = listOf(
    "Royal Enfield Himalayan 450",
    "Royal Enfield Himalayan 411",
    "Royal Enfield Bullet 350",
    "Royal Enfield Classic 350",
    "Royal Enfield Hunter 350",
    "KTM 390 Adventure",
    "KTM 250 Adventure",
    "Suzuki V-Strom SX 250",
    "Hero Xpulse 200 4V",
    "Hero Xpulse 210",
    "TVS RTX 300",
    "BMW G 310 GS",
    "BMW F 450 GS",
    "Honda CB350RS",
    "Honda Hiness 350",
    "Yezdi Adventure",
    "Triumph Scrambler 400X",
    "Triumph Speed 400",
)

private fun copyUriToInternalStorage(context: android.content.Context, uri: android.net.Uri, destFile: java.io.File): Boolean {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        true
    } catch (e: Exception) {
        false
    }
}

@Composable
private fun EditVehicleDialog(
    dialogTitle: String,
    vehicle: VehicleProfile,
    onSave: (VehicleProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selectedVehicle by remember(vehicle) {
        val mappedTitle = when (vehicle.title) {
            "Himalayan 450" -> "Royal Enfield Himalayan 450"
            "Himalayan 411" -> "Royal Enfield Himalayan 411"
            else -> vehicle.title
        }
        mutableStateOf(
            if (PREDEFINED_VEHICLES.contains(mappedTitle)) mappedTitle else if (vehicle.title.isBlank()) PREDEFINED_VEHICLES.first() else "Custom"
        )
    }
    var title by remember(vehicle) { mutableStateOf(vehicle.title) }
    var nickname by remember(vehicle) { mutableStateOf(vehicle.nickname) }
    var profileIcon by remember(vehicle) { mutableStateOf(vehicle.profileIcon) }
    val initialPuc = remember(vehicle) { vehicle.puc.toVehicleDateParts() }
    val initialInsurance = remember(vehicle) { vehicle.insurance.toVehicleDateParts() }
    var pucDay by remember(vehicle) { mutableStateOf(initialPuc.day) }
    var pucMonth by remember(vehicle) { mutableStateOf(initialPuc.month) }
    var pucYear by remember(vehicle) { mutableStateOf(initialPuc.year) }
    var insuranceDay by remember(vehicle) { mutableStateOf(initialInsurance.day) }
    var insuranceMonth by remember(vehicle) { mutableStateOf(initialInsurance.month) }
    var insuranceYear by remember(vehicle) { mutableStateOf(initialInsurance.year) }
    var service by remember(vehicle) { mutableStateOf(vehicle.service) }
    val valid = title.isNotBlank()

    var dropdownExpanded by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val uniqueId = vehicle.id.ifBlank { java.util.UUID.randomUUID().toString() }
            val destFile = java.io.File(context.filesDir, "profile_${uniqueId}.jpg")
            if (copyUriToInternalStorage(context, uri, destFile)) {
                profileIcon = destFile.absolutePath
            }
        }
    }

    val currentProfileImg = remember(profileIcon) {
        if (profileIcon != "default") {
            runCatching { BitmapFactory.decodeFile(profileIcon)?.asImageBitmap() }.getOrNull()
        } else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(dialogTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val defaultDrawable = remember(title) { getBikeDefaultDrawable(title) }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentProfileImg != null) {
                            Image(
                                bitmap = currentProfileImg,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Image(
                                painter = painterResource(id = defaultDrawable),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .align(Alignment.BottomEnd),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                OpenDashIcons.Plus,
                                contentDescription = "Change photo",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap to change photo",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GeistFamily
                    )
                }

                // Dropdown vehicle selection
                Text("Vehicle", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { dropdownExpanded = true }
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(selectedVehicle, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.5.sp)
                        Icon(OpenDashIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(0.68f).heightIn(max = 280.dp),
                    ) {
                        (PREDEFINED_VEHICLES + "Custom").forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name, color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    selectedVehicle = name
                                    if (name != "Custom") {
                                        title = name
                                    } else {
                                        title = if (PREDEFINED_VEHICLES.contains(vehicle.title)) "" else vehicle.title
                                    }
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                if (selectedVehicle == "Custom") {
                    VehicleTextField(title, { title = it }, "Vehicle name")
                }

                VehicleTextField(nickname, { nickname = it }, "Nickname")
                VehicleDateFields(
                    label = "PUC expiry",
                    day = pucDay,
                    month = pucMonth,
                    year = pucYear,
                    onDay = { pucDay = it.filter { ch -> ch.isDigit() }.take(2) },
                    onMonth = { pucMonth = it.take(3) },
                    onYear = { pucYear = it.filter { ch -> ch.isDigit() }.take(4) },
                )
                VehicleDateFields(
                    label = "Insurance expiry",
                    day = insuranceDay,
                    month = insuranceMonth,
                    year = insuranceYear,
                    onDay = { insuranceDay = it.filter { ch -> ch.isDigit() }.take(2) },
                    onMonth = { insuranceMonth = it.take(3) },
                    onYear = { insuranceYear = it.filter { ch -> ch.isDigit() }.take(4) },
                )
                VehicleTextField(service, { service = it }, "Service")
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        VehicleProfile(
                            id = vehicle.id,
                            title = title.trim(),
                            nickname = nickname.trim(),
                            puc = formatVehicleDate(pucDay, pucMonth, pucYear),
                            insurance = formatVehicleDate(insuranceDay, insuranceMonth, insuranceYear),
                            service = service.trim().ifBlank { "Not set" },
                            profileIcon = profileIcon
                        ),
                    )
                },
            ) {
                Text("Save", color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = {
            val vehicles by VehicleStore.vehicles.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vehicle.id.isNotBlank() && vehicles.size > 1) {
                    TextButton(onClick = {
                        VehicleStore.delete(context, vehicle.id)
                        onDismiss()
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
    )
}

@Composable
private fun VehicleDateFields(
    label: String,
    day: String,
    month: String,
    year: String,
    onDay: (String) -> Unit,
    onMonth: (String) -> Unit,
    onYear: (String) -> Unit,
) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        VehicleTextField(day, onDay, "DD", Modifier.weight(0.8f), KeyboardType.Number)
        VehicleTextField(month, onMonth, "MMM", Modifier.weight(1.1f), KeyboardType.Text)
        VehicleTextField(year, onYear, "YYYY", Modifier.weight(1.1f), KeyboardType.Number)
    }
}

@Composable
private fun VehicleTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.padding(top = 8.dp),
    )
}

private fun String.isProblemValue(): Boolean =
    equals("expired", ignoreCase = true) || equals("na", ignoreCase = true)

private data class VehicleDateParts(val day: String, val month: String, val year: String)

private val vehicleMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun String.toVehicleDateParts(): VehicleDateParts {
    val match = Regex("""(\d{1,2})-([A-Za-z]{3})-(\d{4})""").find(this.trim())
    if (match != null) {
        val (day, month, year) = match.destructured
        return VehicleDateParts(day.padStart(2, '0'), month.replaceFirstChar { it.uppercase() }, year)
    }
    return VehicleDateParts("01", "Jan", "2030")
}

private fun formatVehicleDate(day: String, month: String, year: String): String {
    val cleanDay = day.toIntOrNull()?.coerceIn(1, 31)?.toString()?.padStart(2, '0') ?: "01"
    val cleanMonth = vehicleMonths.firstOrNull { it.equals(month.trim(), ignoreCase = true) }
        ?: vehicleMonths.first()
    val cleanYear = year.toIntOrNull()?.coerceIn(2024, 2099)?.toString() ?: "2030"
    return "$cleanDay-$cleanMonth-$cleanYear"
}
