package com.example.opendash.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single store the Garage + saved-location ViewModels talk to, backed by local SQLite.
 *
 * Cloud sync was removed with the (never-shipped) login: there is no account, so there
 * is no uid to sync under. Each row still carries a stable [sid] so a future sync layer
 * can map records 1:1 across devices without a schema change — see git history for the
 * old Firestore mirror if that day comes.
 */
class SyncRepository private constructor(context: Context) {
    companion object {
        @Volatile private var instance: SyncRepository? = null
        fun get(context: Context): SyncRepository =
            instance ?: synchronized(this) { instance ?: SyncRepository(context.applicationContext).also { instance = it } }
    }

    private val db = OpenDashDb.get(context)

    /** Bumped on every data change so ViewModels reload. */
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private fun bump() { _revision.value++ }

    // ── Reads ────────────────────────────────────────────────────────────
    fun odometer(vehicleId: String = VehicleStore.activeVehicleId.value) = db.odometer(vehicleId)
    fun fuelFills(vehicleId: String = VehicleStore.activeVehicleId.value) = db.fuelFills(vehicleId)
    fun expenses(vehicleId: String = VehicleStore.activeVehicleId.value) = db.expenses(vehicleId)
    fun maintenanceItems(vehicleId: String = VehicleStore.activeVehicleId.value) = db.maintenanceItems(vehicleId)
    fun ensureMaintenance(vehicleId: String = VehicleStore.activeVehicleId.value) {
        db.ensureMaintenanceForVehicle(vehicleId); bump()
    }
    fun savedLocations() = db.savedLocations()
    fun rides() = db.rides()

    // ── Mutations ────────────────────────────────────────────────────────
    fun setOdometer(km: Int, vehicleId: String = VehicleStore.activeVehicleId.value) {
        db.setOdometer(km, vehicleId); bump()
    }

    fun addFuel(
        litres: Double,
        cost: Double,
        odoKm: Int,
        location: String,
        vehicleId: String = VehicleStore.activeVehicleId.value,
    ) {
        val prevOdo = db.odometer(vehicleId)
        val f = FuelFillup(sid = OpenDashDb.newSid(), dateMs = System.currentTimeMillis(),
            litres = litres, cost = cost, odometerKm = odoKm, location = location, vehicleId = vehicleId)
        db.upsertFuel(f)
        if (odoKm > prevOdo) db.setOdometer(odoKm, vehicleId)
        bump()
    }
    fun deleteFuel(f: FuelFillup) { db.deleteFuelBySid(f.sid); bump() }

    fun addExpense(
        category: String,
        amount: Double,
        note: String,
        dateMs: Long = System.currentTimeMillis(),
        vehicleId: String = VehicleStore.activeVehicleId.value,
    ) {
        val e = Expense(
            sid = OpenDashDb.newSid(),
            dateMs = dateMs,
            category = category,
            amount = amount,
            note = note,
            vehicleId = vehicleId,
        )
        db.upsertExpense(e); bump()
    }
    fun deleteExpense(e: Expense) { db.deleteExpenseBySid(e.sid); bump() }

    fun addMaintenance(
        name: String,
        icon: String,
        intervalKm: Int,
        lastDoneOdoKm: Int,
        vehicleId: String = VehicleStore.activeVehicleId.value,
    ) {
        val m = MaintenanceItem(sid = OpenDashDb.newSid(), name = name, iconKey = icon,
            intervalKm = intervalKm, lastDoneOdoKm = lastDoneOdoKm,
            lastDoneDateMs = System.currentTimeMillis(), vehicleId = vehicleId)
        db.upsertMaintenance(m); bump()
    }
    fun markServiceDone(m: MaintenanceItem, odoKm: Int) {
        logService(m, odoKm, m.intervalKm)
    }
    fun logService(m: MaintenanceItem, odoKm: Int, intervalKm: Int) {
        val u = m.copy(
            intervalKm = intervalKm,
            lastDoneOdoKm = odoKm,
            lastDoneDateMs = System.currentTimeMillis(),
        )
        db.upsertMaintenance(u); bump()
    }
    fun deleteMaintenance(m: MaintenanceItem) { db.deleteMaintenanceBySid(m.sid); bump() }

    fun addSaved(name: String, lat: Double, lng: Double, note: String): String {
        val sid = OpenDashDb.newSid()
        val s = SavedLocation(sid = sid, name = name, lat = lat, lng = lng, note = note)
        db.upsertSaved(s); bump()
        return sid
    }
    fun renameSaved(s: SavedLocation, name: String, note: String) {
        val u = s.copy(name = name, note = note); db.upsertSaved(u); bump()
    }
    fun deleteSaved(s: SavedLocation) { db.deleteSavedBySid(s.sid); bump() }

    /** Persist a finished ride. */
    fun addRide(r: Ride) { db.upsertRide(r); bump() }
    fun deleteRide(r: Ride) { db.deleteRideBySid(r.sid); bump() }
}
