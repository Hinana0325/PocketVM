package dev.pocketvm

import android.app.Application
import dev.pocketvm.core.config.ConfigValidator
import dev.pocketvm.core.config.VmConfigStore
import dev.pocketvm.diagnostics.DiagnosticsExporter
import dev.pocketvm.diagnostics.RunManifestStore
import dev.pocketvm.runtime.QemuEnvironment
import dev.pocketvm.runtime.VmSession
import dev.pocketvm.storage.DiskProvisioner
import dev.pocketvm.storage.FirmwareManager
import dev.pocketvm.storage.IsoImporter
import dev.pocketvm.storage.VmStorageLayout

class PocketVmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Manual dependency graph. One VM at a time means one [VmSession], owned above
 * the UI so rotations and process-level UI recreation do not touch the VM.
 */
class AppContainer(app: Application) {

    val layout = VmStorageLayout(app)
    val configStore = VmConfigStore(layout)
    val firmwareManager = FirmwareManager(app, layout)
    val isoImporter = IsoImporter(app, layout)
    val manifests = RunManifestStore(layout)
    val diagnostics = DiagnosticsExporter(app, layout)
    val environment = QemuEnvironment(app)
    val validator = ConfigValidator()
    val diskProvisioner = DiskProvisioner(environment, layout)

    val session = VmSession(
        context = app,
        layout = layout,
        environment = environment,
        firmwareManager = firmwareManager,
        diskProvisioner = diskProvisioner,
        manifests = manifests,
        diagnostics = diagnostics,
        validator = validator,
    )
}
