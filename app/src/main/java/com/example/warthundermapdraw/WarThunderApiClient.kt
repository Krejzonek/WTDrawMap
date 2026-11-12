package com.example.warthundermapdraw

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WarThunderApiClient(private val context: Context) {

    companion object {
        private const val TAG = "WarThunderApi"
        private const val PORT = 8111
        private const val TIMEOUT = 1500
        private const val IMAGE_TIMEOUT = 4000
        private const val MAX_ERRORS = 5
        private const val SCAN_TIMEOUT = 12000L
        private const val MAX_SCAN_ADDRESSES = 4096
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val scanExecutor = Executors.newFixedThreadPool(64)

    private val discoveryRunning = AtomicBoolean(false)
    private val loadingRunning = AtomicBoolean(false)

    @Volatile
    private var serverAddress: String? = null

    @Volatile
    private var connectionErrors = 0

    @Volatile
    private var cachedObjects = JSONObject()

    @Volatile
    private var cachedImage: ByteArray? = null

    @Volatile
    private var cachedImageKey = ""

    data class ServerDevice(
        val name: String,
        val address: String
    )

    data class MapData(
        val address: String,
        val info: JSONObject,
        val objects: JSONObject,
        val image: ByteArray?
    )

    private data class LocalNetwork(
        val address: String,
        val networkAddress: Long,
        val broadcastAddress: Long
    )

    fun discover(onResult: (List<ServerDevice>) -> Unit) {
        if (!discoveryRunning.compareAndSet(false, true)) return

        executor.execute {
            try {
                val localNetwork = findLocalNetwork()

                if (localNetwork == null) {
                    Log.w(TAG, "Wi-Fi network not available")
                    onResult(emptyList())
                    return@execute
                }

                val addresses = mutableListOf<Long>()
                var current = localNetwork.networkAddress + 1
                val ownAddress = ipToLong(localNetwork.address)

                while (
                    current < localNetwork.broadcastAddress &&
                    addresses.size < MAX_SCAN_ADDRESSES
                ) {
                    if (current != ownAddress) {
                        addresses.add(current)
                    }
                    current++
                }

                if (addresses.isEmpty()) {
                    onResult(emptyList())
                    return@execute
                }

                val found = ConcurrentLinkedQueue<ServerDevice>()
                val scanLatch = CountDownLatch(addresses.size)
                val tasks = mutableListOf<java.util.concurrent.Future<*>>()

                for (ip in addresses) {
                    val task = scanExecutor.submit {
                        try {
                            val ipAddress = longToIp(ip)
                            val candidate = "http://$ipAddress:$PORT"
                            val response = request("$candidate/map_info.json", 700)

                            if (response != null) {
                                val json = JSONObject(response)

                                if (json.has("valid")) {
                                    val deviceName = getDeviceName(ipAddress)
                                    found.add(
                                        ServerDevice(
                                            name = deviceName,
                                            address = candidate
                                        )
                                    )
                                    Log.i(TAG, "War Thunder found: $deviceName ($candidate)")
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Scan request failed", e)
                        } finally {
                            scanLatch.countDown()
                        }
                    }

                    tasks.add(task)
                }

                val completed = scanLatch.await(
                    SCAN_TIMEOUT,
                    TimeUnit.MILLISECONDS
                )

                if (!completed) {
                    tasks.forEach {
                        if (!it.isDone) it.cancel(true)
                    }
                }

                val result = found
                    .distinctBy { it.address }
                    .sortedBy { it.name.lowercase() }

                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
                onResult(emptyList())
            } finally {
                discoveryRunning.set(false)
            }
        }
    }

    private fun getDeviceName(ip: String): String {
        return try {
            val hostName = InetAddress.getByName(ip).canonicalHostName
                .trim()
                .removeSuffix(".")

            if (
                hostName.isNotBlank() &&
                !hostName.equals(ip, ignoreCase = true)
            ) {
                hostName
            } else {
                ip
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cannot resolve device name for $ip", e)
            ip
        }
    }

    fun selectServer(address: String) {
        serverAddress = address
        connectionErrors = 0
        cachedObjects = JSONObject()
        cachedImage = null
        cachedImageKey = ""

        Log.i(TAG, "Selected server: $address")
    }

    fun loadMap(onResult: (MapData?) -> Unit) {
        if (!loadingRunning.compareAndSet(false, true)) return

        executor.execute {
            try {
                val address = serverAddress

                if (address == null) {
                    onResult(null)
                    return@execute
                }

                val infoText = request(
                    "$address/map_info.json",
                    TIMEOUT
                )

                if (infoText == null) {
                    handleConnectionError()
                    onResult(null)
                    return@execute
                }

                val info = JSONObject(infoText)
                connectionErrors = 0

                if (!info.optBoolean("valid", false)) {
                    onResult(
                        MapData(
                            address,
                            info,
                            JSONObject(),
                            cachedImage
                        )
                    )
                    return@execute
                }

                val objectsText = request(
                    "$address/map_obj.json",
                    TIMEOUT
                )

                val objects = if (objectsText != null) {
                    try {
                        val parsed = parseObjectsJson(objectsText)
                        cachedObjects = parsed
                        parsed
                    } catch (e: Exception) {
                        Log.e(TAG, "Map objects parsing error", e)
                        cachedObjects
                    }
                } else {
                    Log.w(TAG, "Map objects temporarily unavailable")
                    cachedObjects
                }

                val generation = info.optInt("map_generation", -1)
                val hudType = info.optInt("hud_type", -1)
                val imageKey = "$generation:$hudType"

                var imageBytes = cachedImage

                if (cachedImage == null || cachedImageKey != imageKey) {
                    val imageUrl = if (generation >= 0) {
                        "$address/map.img?gen=$generation"
                    } else {
                        "$address/map.img"
                    }

                    val newImage = requestBytes(imageUrl)

                    if (newImage != null) {
                        imageBytes = newImage
                        cachedImage = newImage
                        cachedImageKey = imageKey

                        Log.i(TAG, "Map image updated: $imageKey")
                    } else {
                        Log.w(TAG, "Map image temporarily unavailable")
                    }
                }

                onResult(
                    MapData(
                        address,
                        info,
                        objects,
                        imageBytes
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Map loading error", e)
                handleConnectionError()
                onResult(null)
            } finally {
                loadingRunning.set(false)
            }
        }
    }

    private fun parseObjectsJson(text: String): JSONObject {
        val trimmed = text.trim()

        return if (trimmed.startsWith("[")) {
            JSONObject().put("items", JSONArray(trimmed))
        } else {
            JSONObject(trimmed)
        }
    }

    private fun handleConnectionError() {
        connectionErrors++

        Log.w(
            TAG,
            "Connection error: $connectionErrors/$MAX_ERRORS"
        )

        if (connectionErrors >= MAX_ERRORS) {
            Log.w(TAG, "Connection lost. Searching again.")

            serverAddress = null
            connectionErrors = 0
            cachedObjects = JSONObject()
            cachedImage = null
            cachedImageKey = ""
        }
    }

    fun getServerAddress(): String? = serverAddress

    fun disconnect() {
        serverAddress = null
        connectionErrors = 0
        cachedObjects = JSONObject()
        cachedImage = null
        cachedImageKey = ""

        Log.i(TAG, "Disconnected")
    }

    fun shutdown() {
        disconnect()
        executor.shutdownNow()
        scanExecutor.shutdownNow()
    }

    private fun findLocalNetwork(): LocalNetwork? {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as ConnectivityManager

            val network = connectivityManager.activeNetwork ?: return null

            val capabilities =
                connectivityManager.getNetworkCapabilities(network) ?: return null

            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null
            }

            val properties =
                connectivityManager.getLinkProperties(network) ?: return null

            val linkAddress = properties.linkAddresses.firstOrNull {
                it.address is Inet4Address &&
                        !it.address.isLoopbackAddress
            } ?: return null

            val address = linkAddress.address.hostAddress ?: return null
            val prefix = linkAddress.prefixLength

            if (prefix !in 1..30) return null

            val ip = ipToLong(address)
            val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

            val networkAddress = ip and mask
            val broadcastAddress =
                networkAddress or (mask xor 0xFFFFFFFFL)

            LocalNetwork(
                address,
                networkAddress,
                broadcastAddress
            )
        } catch (e: Exception) {
            Log.e(TAG, "Local network error", e)
            null
        }
    }

    private fun request(
        url: String,
        timeout: Int = TIMEOUT
    ): String? {
        var connection: HttpURLConnection? = null

        return try {
            connection = URL(url).openConnection() as HttpURLConnection

            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.useCaches = false

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use {
                    it.readText()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Request failed: $url", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun requestBytes(url: String): ByteArray? {
        var connection: HttpURLConnection? = null

        return try {
            connection = URL(url).openConnection() as HttpURLConnection

            connection.connectTimeout = IMAGE_TIMEOUT
            connection.readTimeout = IMAGE_TIMEOUT
            connection.requestMethod = "GET"
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.useCaches = false

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use {
                    it.readBytes()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Image request failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun ipToLong(ip: String): Long {
        return ip.split(".").fold(0L) { result, part ->
            (result shl 8) or (part.toLong() and 0xFF)
        }
    }

    private fun longToIp(ip: Long): String {
        return "${ip shr 24 and 0xFF}." +
                "${ip shr 16 and 0xFF}." +
                "${ip shr 8 and 0xFF}." +
                "${ip and 0xFF}"
    }
}