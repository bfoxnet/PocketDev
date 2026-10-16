package com.bfoxnet.pocketdev

import android.content.Context
import java.io.File

/**
 * Runtime status helper for the Ubuntu 24.04.5 LTS (`noble`) + PRoot environment.
 */
class QemuVmManager(private val context: Context) {

    val vmRootDir: File
        get() = UbuntuProotManager.ubuntuRootDir(context)

    val qcow2DiskFile: File
        get() = UbuntuProotManager.ubuntuRootDir(context)

    val isVmRunning: Boolean
        get() = UbuntuProotManager.isUbuntuInstalled(context)

    fun provisionVmEnvironment() {
        UbuntuProotManager.provisionHostHelpers(context)
    }

    fun readQcow2VirtualSizeBytes(): Long {
        val root = UbuntuProotManager.ubuntuRootDir(context)
        if (!root.exists()) return 0L
        var total = 0L
        runCatching {
            root.walkTopDown().maxDepth(4).forEach { f ->
                if (f.isFile) total += f.length()
            }
        }
        return total
    }

    fun writeQcow2Header(target: File, virtualSizeBytes: Long) {
        // No-op: real Ubuntu rootfs directory is used directly by PRoot.
    }

    companion object {
        const val QEMU_VERSION = UbuntuProotManager.PROOT_VERSION
        const val KERNEL_VERSION = UbuntuProotManager.UBUNTU_VERSION
    }
}
