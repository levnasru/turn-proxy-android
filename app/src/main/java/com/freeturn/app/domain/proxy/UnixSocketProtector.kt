package com.freeturn.app.domain.proxy

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UnixSocketProtector(private val context: Context) {
    private var serverSocket: LocalServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // socketName - имя в АБСТРАКТНОМ namespace (без ведущего "@"; его добавляет
    // только строка флага -protect-path, Go так отличает абстрактный сокет от
    // файлового). Имя обязано быть уникальным на устройство: абстрактные имена
    // живут в network namespace, то есть общие для всех приложений, и раньше
    // здесь стоял константный "freeturn_protect" - релиз и debug-сборка
    // (applicationId + ".debug") бились за одно имя. Кто забиндил первым, тот
    // владел, а ядро проигравшей сборки коннектилось к сокету ЧУЖОГО uid и
    // получало "dial unix @freeturn_protect: connect: permission denied" на
    // каждый dial TURN. Имя приходит из packageName - сборки больше не пересекаются.
    private var currentSocketName: String? = null

    @Synchronized
    fun start(socketName: String) {
        stop()
        currentSocketName = socketName
        try {
            var server: LocalServerSocket? = null
            var lastErr: Exception? = null
            for (attempt in 1..5) {
                try {
                    server = LocalServerSocket(socketName)
                    break
                } catch (e: Exception) {
                    lastErr = e
                    try {
                        val s = LocalSocket()
                        s.connect(LocalSocketAddress(socketName))
                        s.close()
                    } catch (_: Exception) {}
                    Thread.sleep(80)
                }
            }
            if (server == null) {
                throw lastErr ?: java.io.IOException("Failed to bind $socketName")
            }
            serverSocket = server

            // Устанавливаем FD_CLOEXEC на дескриптор сокета, чтобы дочерний процесс
            // libfreeturn.so при fork'е ProcessBuilder не наследовал слушающий сокет
            // и не удерживал адрес занятым при рестартах.
            try {
                val fd = server.fileDescriptor
                if (fd != null && fd.valid()) {
                    val flags = Os.fcntlInt(fd, OsConstants.F_GETFD, 0)
                    Os.fcntlInt(fd, OsConstants.F_SETFD, flags or OsConstants.FD_CLOEXEC)
                }
            } catch (e: Exception) {
                Log.w("UnixSocketProtector", "Could not set FD_CLOEXEC: ${e.message}")
            }

            Log.i("UnixSocketProtector", "Listening on abstract socket $socketName")

            acceptJob = scope.launch {
                try {
                    while (isActive) {
                        val socket = server.accept() ?: break
                        launch(Dispatchers.IO) {
                            handleSocket(socket)
                        }
                    }
                } catch (e: Exception) {
                    // isActive отсекает штатный stop() (там accept падает после close).
                    if (isActive) {
                        Log.e("UnixSocketProtector", "Server error", e)
                        ProxyServiceState.addLog(
                            "Сокет защиты $socketName ошибка accept (${e.message})"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("UnixSocketProtector", "Server bind error", e)
            ProxyServiceState.addLog(
                "Сокет защиты $socketName не поднялся (${e.message}) - соединения ядра пойдут в тоннель и упадут"
            )
        }
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        val server = serverSocket
        serverSocket = null
        if (server != null) {
            try {
                val fd = server.fileDescriptor
                if (fd != null && fd.valid()) {
                    Os.shutdown(fd, OsConstants.SHUT_RDWR)
                }
            } catch (_: Exception) {}
            try {
                currentSocketName?.let { name ->
                    val s = LocalSocket()
                    s.connect(LocalSocketAddress(name))
                    s.close()
                }
            } catch (_: Exception) {}
            try {
                server.close()
            } catch (_: Exception) {}
        }
    }

    private fun handleSocket(socket: LocalSocket) {
        try {
            val input = socket.inputStream
            val buffer = ByteArray(1)
            val bytesRead = input.read(buffer)
            val fds = socket.ancillaryFileDescriptors
            if (!fds.isNullOrEmpty()) {
                for (fd in fds) {
                    try {
                        val vpn = getActiveVpnService()
                        if (vpn != null) {
                            val pfd = ParcelFileDescriptor.dup(fd)
                            try {
                                val success = vpn.protect(pfd.fd)
                                Log.i("UnixSocketProtector", "Protected fd ${pfd.fd}: $success")
                            } finally {
                                try { pfd.close() } catch (_: Exception) {}
                            }
                        } else {
                            Log.w("UnixSocketProtector", "No active VpnService found")
                        }
                    } finally {
                        try {
                            Os.close(fd)
                        } catch (e: Exception) {
                            Log.w("UnixSocketProtector", "Failed to close ancillary fd", e)
                        }
                    }
                }
            } else {
                Log.w("UnixSocketProtector", "No ancillary FDs received")
            }
            if (bytesRead > 0) {
                val output = socket.outputStream
                output.write(byteArrayOf(1))
                output.flush()
            }
        } catch (e: Exception) {
            Log.e("UnixSocketProtector", "Error handling socket", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
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
