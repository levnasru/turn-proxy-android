package com.freeturn.app.domain.proxy

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class UnixSocketProtector(private val context: Context) {
    private var serverSocket: LocalServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(socketPath: String) {
        scope.launch {
            try {
                val sockFile = File(socketPath)
                if (sockFile.exists()) {
                    sockFile.delete()
                }

                // Android LocalServerSocket(String) creates an abstract socket.
                // Go uses "@abstractName" for abstract sockets.
                // We will create a normal Unix domain socket using reflection or bind directly?
                // Wait! To use filesystem path with LocalServerSocket, we must use LocalServerSocket(FileDescriptor).
                // Or we can just use an abstract socket! If we name it "freeturn_protect", Go can connect to "@freeturn_protect".
                
                // Let's use the abstract namespace:
                serverSocket = LocalServerSocket("freeturn_protect")
                Log.i("UnixSocketProtector", "Listening on abstract socket freeturn_protect")

                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch(Dispatchers.IO) {
                        handleSocket(socket)
                    }
                }
            } catch (e: Exception) {
                Log.e("UnixSocketProtector", "Server error", e)
            }
        }
    }

    fun stop() {
        scope.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun handleSocket(socket: LocalSocket) {
        try {
            val input = socket.inputStream
            val buffer = ByteArray(1)
            val bytesRead = input.read(buffer)
            if (bytesRead > 0) {
                val fds = socket.ancillaryFileDescriptors
                if (fds != null && fds.isNotEmpty()) {
                    val fd = fds[0]
                    val vpn = getActiveVpnService()
                    if (vpn != null) {
                        val pfd = ParcelFileDescriptor.dup(fd)
                        val success = vpn.protect(pfd.fd)
                        Log.i("UnixSocketProtector", "Protected fd ${pfd.fd}: $success")
                        pfd.close()
                    } else {
                        Log.w("UnixSocketProtector", "No active VpnService found")
                    }
                } else {
                    Log.w("UnixSocketProtector", "No ancillary FDs received")
                }
            }
        } catch (e: Exception) {
            Log.e("UnixSocketProtector", "Error handling socket", e)
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun getActiveVpnService(): VpnService? {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
            currentActivityThreadMethod.isAccessible = true
            val activityThread = currentActivityThreadMethod.invoke(null)
            
            val mServicesField = activityThreadClass.getDeclaredField("mServices")
            mServicesField.isAccessible = true
            val mServices = mServicesField.get(activityThread) as Map<*, *>
            
            for (service in mServices.values) {
                if (service is VpnService) {
                    return service
                }
            }
        } catch (e: Exception) {
            Log.e("UnixSocketProtector", "Failed to get VpnService", e)
        }
        return null
    }
}
