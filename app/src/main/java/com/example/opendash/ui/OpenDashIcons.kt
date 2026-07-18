package com.example.opendash.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.TurnLeft
import androidx.compose.material.icons.outlined.TurnRight
import androidx.compose.material.icons.outlined.TurnSlightLeft
import androidx.compose.material.icons.outlined.TurnSlightRight
import androidx.compose.material.icons.outlined.UTurnLeft

import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object OpenDashIcons {
    // ---- Material icon aliases ----
    val Home          = Icons.Outlined.Home
    val Route         = Icons.Outlined.Route
    val Navi          = Icons.Outlined.Navigation
    val Wrench        = Icons.Outlined.Build
    val Fuel          = Icons.Outlined.LocalGasStation
    val History       = Icons.Outlined.History
    val Gear          = Icons.Outlined.Settings
    val Bt            = Icons.Outlined.Bluetooth
    val Wifi          = Icons.Outlined.Wifi
    val ChevronRight  = Icons.Outlined.ChevronRight
    val ChevronLeft   = Icons.Outlined.ChevronLeft
    val ChevronDown   = Icons.Outlined.ExpandMore
    val Plus          = Icons.Outlined.Add
    val Minus         = Icons.Outlined.Remove
    val Recenter      = Icons.Outlined.MyLocation
    val Cross         = Icons.Outlined.GpsFixed
    val Mic           = Icons.Outlined.Mic
    val Bell          = Icons.Outlined.Notifications
    val Check         = Icons.Outlined.Check
    val X             = Icons.Outlined.Close
    val ArrowUp       = Icons.Outlined.Navigation
    val ArrowUpward   = Icons.Outlined.ArrowUpward
    val ArrowDownward = Icons.Outlined.ArrowDownward
    val Reorder       = Icons.Outlined.Reorder
    val Circle        = Icons.Outlined.RadioButtonUnchecked
    val Gauge         = Icons.Outlined.Speed
    val Cal           = Icons.Outlined.CalendarToday
    val Pin           = Icons.Outlined.Route
    val Save          = Icons.Outlined.BookmarkBorder
    val Share         = Icons.Outlined.Share
    val Power         = Icons.Outlined.PowerSettingsNew
    val Person        = Icons.Outlined.Person
    val GroupRide     = Icons.Outlined.Groups
    val Palette       = Icons.Outlined.Palette
    val Search        = Icons.Outlined.Search
    val Lock          = Icons.Outlined.Lock
    val Mail          = Icons.Outlined.Email
    val Chart         = Icons.Outlined.BarChart
    val Speaker       = Icons.Outlined.VolumeUp
    val SpeakerOff    = Icons.Outlined.VolumeOff
    val Drop          = Icons.Outlined.WaterDrop
    val Trend         = Icons.Outlined.TrendingUp
    val Moon          = Icons.Outlined.DarkMode
    val Sync          = Icons.Outlined.Sync
    val Motor         = Icons.Outlined.TwoWheeler
    val Car           = Icons.Outlined.DirectionsCar
    val Swap          = Icons.Outlined.SwapVert
    val Flag          = Icons.Outlined.Flag
    val Clock         = Icons.Outlined.Schedule
    val Road          = Icons.Outlined.Straighten
    val Target        = Icons.Outlined.GpsFixed
    val Thermo        = Icons.Outlined.Thermostat
    val Edit          = Icons.Outlined.Edit
    val Zap           = Icons.Outlined.Bolt
    val Units         = Icons.Outlined.Straighten
    val Layers        = Icons.Outlined.Layers
    val Directions    = Icons.Outlined.Directions
    val Download      = Icons.Outlined.Download
    val Trash         = Icons.Outlined.Delete
    val Help          = Icons.Outlined.HelpOutline

    // ---- Custom icons (mapped to official high-quality Material icons) ----
    val Dash          = Icons.Outlined.Dashboard
    val Chain         = Icons.Outlined.Link
    val LocationPin   = Icons.Outlined.Place
    val BikeAdventure = Icons.Outlined.Terrain
    val BikeSport     = Icons.Outlined.Speed
    val BikeClassic   = Icons.Outlined.TwoWheeler

    // ---- Directional nav arrows ----
    val TurnLeft      = Icons.Outlined.TurnLeft
    val TurnRight     = Icons.Outlined.TurnRight
    val SlightLeft    = Icons.Outlined.TurnSlightLeft
    val SlightRight   = Icons.Outlined.TurnSlightRight
    val UTurn         = Icons.Outlined.UTurnLeft
}

private fun pathVec(vararg paths: String, strokeWidth: Float = 1.7f): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { pathData ->
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
