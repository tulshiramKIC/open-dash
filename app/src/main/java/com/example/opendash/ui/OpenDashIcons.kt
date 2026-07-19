package com.example.opendash.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object OpenDashIcons {
    // ---- Material icon aliases ----
    val Home          = Icons.Rounded.Home
    val Route         = fillVec("M416 320h-96c-17.6 0-32-14.4-32-32s14.4-32 32-32h96s96-107 96-160-43-96-96-96-96 43-96 96c0 25.5 22.2 63.4 45.3 96H320c-52.9 0-96 43.1-96 96s43.1 96 96 96h96c17.6 0 32 14.4 32 32s-14.4 32-32 32H185.5c-16 24.8-33.8 47.7-47.3 64H416c52.9 0 96-43.1 96-96s-43.1-96-96-96zm0-256c17.7 0 32 14.3 32 32s-14.3 32-32 32-32-14.3-32-32 14.3-32 32-32zM96 256c-53 0-96 43-96 96s96 160 96 160 96-107 96-160-43-96-96-96zm0 128c-17.7 0-32-14.3-32-32s14.3-32 32-32 32 14.3 32 32-14.3 32-32 32z")
    val Navi          = Icons.Outlined.Navigation
    val Wrench        = Icons.Rounded.Build
    val Fuel          = Icons.Rounded.LocalGasStation
    val History       = Icons.Rounded.History
    val Gear          = Icons.Rounded.Settings
    val Bt            = Icons.Rounded.Bluetooth
    val Wifi          = Icons.Rounded.Wifi
    val ChevronRight  = Icons.Rounded.ChevronRight
    val ChevronLeft   = Icons.Rounded.ChevronLeft
    val ChevronDown   = Icons.Rounded.ExpandMore
    val Plus          = Icons.Rounded.Add
    val Minus         = Icons.Rounded.Remove
    val Recenter      = Icons.Rounded.MyLocation
    val Cross         = Icons.Rounded.GpsFixed
    val Mic           = Icons.Rounded.Mic
    val MicOff        = Icons.Rounded.MicOff
    val Bell          = Icons.Rounded.Notifications
    val Check         = Icons.Rounded.Check
    val X             = Icons.Rounded.Close
    val ArrowUp       = Icons.Outlined.Navigation
    val ArrowUpward   = Icons.Outlined.ArrowUpward
    val ArrowDownward = Icons.Outlined.ArrowDownward
    val Reorder       = Icons.Rounded.Reorder
    val Circle        = Icons.Rounded.RadioButtonUnchecked
    val Gauge         = Icons.Rounded.Speed
    val Cal           = Icons.Rounded.CalendarToday
    val Pin           = Icons.Outlined.Route
    val Save          = Icons.Rounded.BookmarkBorder
    val Share         = Icons.Rounded.Share
    val Copy          = Icons.Rounded.ContentCopy
    val Power         = Icons.Rounded.PowerSettingsNew
    val Person        = Icons.Rounded.Person
    val GroupRide     = Icons.Rounded.Groups
    val Palette       = Icons.Rounded.Palette
    val Search        = Icons.Rounded.Search
    val Lock          = Icons.Rounded.Lock
    val Mail          = Icons.Rounded.Email
    val Chart         = Icons.Rounded.BarChart
    val Speaker       = Icons.Rounded.VolumeUp
    val SpeakerOff    = Icons.Rounded.VolumeOff
    val Drop          = Icons.Rounded.WaterDrop
    val Trend         = Icons.Rounded.TrendingUp
    val Moon          = Icons.Rounded.DarkMode
    val Sun           = Icons.Rounded.LightMode
    val ThemeAuto     = Icons.Rounded.BrightnessAuto
    val Sync          = Icons.Rounded.Sync
    val Motor         = Icons.Rounded.TwoWheeler
    val Car           = Icons.Rounded.DirectionsCar
    val Swap          = Icons.Rounded.SwapVert
    val Flag          = Icons.Rounded.Flag
    val Clock         = Icons.Rounded.Schedule
    val Road          = Icons.Rounded.Straighten
    val Target        = Icons.Rounded.GpsFixed
    val Thermo        = Icons.Rounded.Thermostat
    val Edit          = Icons.Rounded.Edit
    val Zap           = Icons.Rounded.Bolt
    val Units         = Icons.Rounded.Straighten
    val Layers        = Icons.Rounded.Layers
    val Directions    = Icons.Rounded.Directions
    val Download      = Icons.Rounded.Download
    val Trash         = Icons.Rounded.Delete
    val Map           = Icons.Rounded.Map
    val Help          = Icons.Rounded.HelpOutline
    val Disc          = Icons.Rounded.Album
    val Tyre          = Icons.Rounded.Cached

    // ---- Custom icons (mapped to official high-quality Material icons) ----
    val Dash          = Icons.Rounded.Dashboard
    val Chain         = Icons.Rounded.Link
    val LocationPin   = Icons.Rounded.Place
    val BikeAdventure = Icons.Rounded.Terrain
    val BikeSport     = Icons.Rounded.Speed
    val BikeClassic   = Icons.Rounded.TwoWheeler

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

private fun fillVec(pathData: String, viewportSize: Float = 512f): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = viewportSize,
        viewportHeight = viewportSize,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
