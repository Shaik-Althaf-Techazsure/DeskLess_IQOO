package com.techazsure.deskless.link

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.thread

/** A report on its way to a linked phone. */
data class Outgoing(val pack: File, val title: String, val to: String)

/**
 * The link between phones running DeskLess, with no network: Bluetooth finds the other phone and carries
 * the report, and Wi-Fi is used directly between the two when both have it on. Phones are linked once, by
 * one scanning the other's QR code. After that they connect by themselves whenever both have DeskLess
 * open, and a report sent from one arrives on the other with nobody tapping Accept.
 */
class Link(
    private val context: Context,
    private val voice: Voice,
    /** Takes in a report pack that has arrived. Returns the report's title, or null if it is not a report. */
    private val takeIn: (pack: File, from: Peer) -> String?,
    private val notify: (String) -> Unit,
) {
    private val file = File(context.filesDir, "link.json")
    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val main = Handler(Looper.getMainLooper())

    /** This phone: its code, its name and the secret its QR code carries. */
    var me by mutableStateOf(Peer("", "", ""))
        private set

    /** The phones this one has been linked with. */
    val peers = mutableStateListOf<Peer>()

    /** Codes of the linked phones that are in range right now and have proved who they are. */
    val connected = mutableStateListOf<String>()

    /** Reports waiting for a linked phone to come in range. */
    val waiting = mutableStateListOf<Outgoing>()

    /** How many reports are on their way across the link at this moment. */
    var inFlight by mutableIntStateOf(0)
        private set

    /** True while the phone is announcing itself and looking for linked phones. */
    var running by mutableStateOf(false)
        private set

    /** The last thing that went wrong starting the link, for the side bar; null when nothing did. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** endpoint → the code the phone at the other end claims. */
    private val claimed = HashMap<String, String>()
    private val names = HashMap<String, String>()
    private val seen = HashMap<String, String>()
    private val proven = HashSet<String>()
    private val sending = HashMap<Long, Outgoing>()

    /** A phone just scanned: this phone calls it first, since the other does not know this one yet. */
    private var justScanned: String? = null

    init {
        load()
    }

    /**
     * The permissions Android wants before phones may find each other; they differ by Android version.
     * Location is among them on every version: finding a phone by Bluetooth counts, to Android, as a way
     * of telling where you are. DeskLess does not use the position for the link.
     */
    val permissions: Array<String>
        get() = LOCATION + when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES,
            ) + localNetwork
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
            else -> emptyArray()
        }

    /**
     * The newest Android versions add a permission for talking to devices on the local network, and the
     * link will not start without it. It is asked for only on a phone whose Android knows it; on an older
     * one, asking for a permission that does not exist would be refused for ever.
     */
    private val localNetwork: Array<String> by lazy {
        val known = runCatching { context.packageManager.getPermissionInfo(LOCAL_NETWORK, 0) }.isSuccess
        if (known) arrayOf(LOCAL_NETWORK) else emptyArray()
    }

    /** True when everything is granted; for location, approximate is enough. */
    val allowed: Boolean
        get() {
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            return LOCATION.any(::granted) && permissions.filter { it !in LOCATION }.all(::granted)
        }

    /** Whether Bluetooth is switched on; null on a phone without it. */
    val bluetoothOn: Boolean?
        get() = runCatching { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled }.getOrNull()

    /** The name other phones see. It takes effect the next time the link starts. */
    fun rename(name: String) {
        val clean = name.trim().ifBlank { Build.MODEL ?: "Phone" }
        if (clean == me.name) return
        me = me.copy(name = clean)
        save()
    }

    /** Starts announcing this phone and looking for linked ones. Does nothing without the permissions. */
    fun start() {
        if (running || !allowed) return
        running = true
        problem = null
        val name = Pairing.endpointName(me)
        client.startAdvertising(name, SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { failedToStart(it) }
        client.startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { failedToStart(it) }
    }

    fun stop() {
        if (!running) return
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
        running = false
        claimed.clear()
        names.clear()
        seen.clear()
        proven.clear()
        connected.clear()
        // A report cut off mid-way goes back in the queue for the next time the phones meet.
        sending.values.forEach { if (it !in waiting) waiting.add(it) }
        sending.clear()
        inFlight = 0
    }

    /** Links with the phone whose QR code was just scanned, and connects to it as soon as it is in range. */
    fun pair(scanned: Peer): Boolean {
        if (scanned.id == me.id) return false
        peers.removeAll { it.id == scanned.id }
        peers.add(scanned)
        save()
        justScanned = scanned.id
        // It may already be in sight; if so, call it now.
        seen.entries.firstOrNull { it.value == scanned.id }?.let { consider(it.key, it.value) }
        return true
    }

    fun forget(id: String) {
        peers.removeAll { it.id == id }
        waiting.removeAll { it.to == id }
        claimed.entries.firstOrNull { it.value == id }?.let { runCatching { client.disconnectFromEndpoint(it.key) } }
        connected.remove(id)
        save()
    }

    /**
     * Sends a report pack to every linked phone: at once to those in range, and as soon as they come in
     * range to the others. Returns how many it went to at once, or -1 when no phone is linked.
     */
    fun send(pack: File, title: String): Int {
        if (peers.isEmpty()) return -1
        var now = 0
        for (peer in peers.toList()) {
            val out = Outgoing(pack, title, peer.id)
            val endpoint = endpointOf(peer.id)
            if (endpoint != null) {
                push(endpoint, out)
                now++
            } else if (out !in waiting) {
                waiting.add(out)
            }
        }
        return now
    }

    private fun endpointOf(id: String): String? =
        claimed.entries.firstOrNull { it.value == id && it.key in proven }?.key

    private fun push(endpoint: String, out: Outgoing) {
        val payload = try {
            Payload.fromStream(FileInputStream(out.pack))
        } catch (e: Exception) {
            notify("Could not read ${out.title} to send it.")
            return
        }
        sending[payload.id] = out
        inFlight = sending.size
        waiting.remove(out)
        client.sendPayload(endpoint, payload).addOnFailureListener {
            sending.remove(payload.id)
            inFlight = sending.size
            if (out !in waiting) waiting.add(out)
        }
    }

    private fun failedToStart(error: Exception) {
        Log.w(TAG, "Nearby did not start", error)
        problem = error.message ?: "The link could not start."
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
        }
        running = false
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpoint: String, info: DiscoveredEndpointInfo) {
            val (id, _) = Pairing.fromEndpointName(info.endpointName) ?: return
            seen[endpoint] = id
            consider(endpoint, id)
        }

        override fun onEndpointLost(endpoint: String) {
            seen.remove(endpoint)
        }
    }

    /**
     * Decides whether to call a phone that has come into sight. Only linked phones are called. Two linked
     * phones see each other at about the same moment, so the one with the smaller code calls and the other
     * waits, calling only if nothing has come.
     */
    private fun consider(endpoint: String, id: String) {
        if (peers.none { it.id == id } || claimed.containsValue(id)) return
        val wait = if (me.id < id || id == justScanned) 0L else 4_000L
        main.postDelayed({
            if (running && seen[endpoint] == id && !claimed.containsValue(id) && peers.any { it.id == id }) {
                client.requestConnection(Pairing.endpointName(me), endpoint, lifecycle).addOnFailureListener {
                    // Busy, or the other phone called at the same moment: look again shortly.
                    main.postDelayed({ if (seen[endpoint] == id) consider(endpoint, id) }, 5_000L)
                }
            }
        }, wait)
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpoint: String, info: ConnectionInfo) {
            val who = Pairing.fromEndpointName(info.endpointName)
            if (who == null) {
                client.rejectConnection(endpoint)
                return
            }
            claimed[endpoint] = who.first
            names[endpoint] = who.second
            // Nobody is asked to accept. The proof that follows decides whether this phone is let in.
            client.acceptConnection(endpoint, payloads)
        }

        override fun onConnectionResult(endpoint: String, result: ConnectionResolution) {
            val id = claimed[endpoint]
            if (!result.status.isSuccess || id == null) {
                claimed.remove(endpoint)
                names.remove(endpoint)
                return
            }
            // Each phone shows the other it knows the secret they share. A phone this one has not met must
            // have scanned this phone's QR code, so it is this phone's own secret that it has to know.
            val key = peers.firstOrNull { it.id == id }?.key ?: me.key
            val hello = JSONObject().put("t", "hello").put("id", me.id).put("name", me.name).put("proof", Pairing.proof(key, me.id, id))
            client.sendPayload(endpoint, Payload.fromBytes(hello.toString().toByteArray()))
            main.postDelayed({
                if (endpoint in claimed && endpoint !in proven) runCatching { client.disconnectFromEndpoint(endpoint) }.also { dropped(endpoint) }
            }, 8_000L)
        }

        override fun onDisconnected(endpoint: String) = dropped(endpoint)
    }

    private fun dropped(endpoint: String) {
        val id = claimed.remove(endpoint)
        names.remove(endpoint)
        proven.remove(endpoint)
        if (id != null && claimed.none { it.value == id && it.key in proven }) connected.remove(id)
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpoint: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { greeted(endpoint, String(it)) }
                // A report from a phone that has not proved itself is not read at all.
                Payload.Type.STREAM -> if (endpoint in proven) receive(endpoint, payload)
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpoint: String, update: PayloadTransferUpdate) {
            val out = sending[update.payloadId] ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    sending.remove(update.payloadId)
                    inFlight = sending.size
                    val name = peers.firstOrNull { it.id == out.to }?.name ?: "the other phone"
                    voice.say("Report sent")
                    notify("${out.title} sent to $name.")
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    sending.remove(update.payloadId)
                    inFlight = sending.size
                    if (out !in waiting) waiting.add(out)
                    voice.say("Sending failed")
                    notify("${out.title} did not get through. It will be sent again when the phones meet.")
                }
                else -> Unit
            }
        }
    }

    /** Checks the other phone's proof. A phone that proves itself for the first time becomes a linked phone. */
    private fun greeted(endpoint: String, text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (json.optString("t") != "hello") return
        val id = json.optString("id")
        val known = peers.firstOrNull { it.id == id }
        val key = known?.key ?: me.key
        if (id != claimed[endpoint] || json.optString("proof") != Pairing.proof(key, id, me.id)) {
            runCatching { client.disconnectFromEndpoint(endpoint) }
            dropped(endpoint)
            return
        }
        val name = json.optString("name").ifBlank { id }
        if (known == null) {
            peers.add(Peer(id, name, key))
            save()
            voice.say("Phone linked")
            notify("Linked with $name.")
        } else if (known.name != name) {
            peers[peers.indexOf(known)] = known.copy(name = name)
            save()
        }
        if (id == justScanned) {
            justScanned = null
            voice.say("Phone linked")
            notify("Linked with $name.")
        }
        proven.add(endpoint)
        if (id !in connected) connected.add(id)
        // Anything that was waiting for this phone goes now.
        waiting.filter { it.to == id }.forEach { push(endpoint, it) }
    }

    /** Reads an arriving report off the link into a file, then hands it to the app. */
    private fun receive(endpoint: String, payload: Payload) {
        val from = peers.firstOrNull { it.id == claimed[endpoint] } ?: return
        val input = payload.asStream()?.asInputStream() ?: return
        thread(name = "link-receive") {
            val pack = File(context.cacheDir, "received-${payload.id}.zip")
            val whole = runCatching {
                input.use { stream ->
                    pack.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            total += read
                            // Nothing DeskLess sends is anywhere near this size.
                            require(total <= MAX_PACK_BYTES) { "Too large" }
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }.isSuccess
            val title = if (whole) runCatching { takeIn(pack, from) }.getOrNull() else null
            pack.delete()
            main.post {
                if (title != null) {
                    voice.say("Report received")
                    notify("$title received from ${from.name}.")
                } else {
                    notify("Something arrived from ${from.name} that could not be read.")
                }
            }
        }
    }

    private fun load() {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        me = Peer(
            id = json?.optString("id").orEmpty().ifBlank { Pairing.newId() },
            name = json?.optString("name").orEmpty().ifBlank { Build.MODEL ?: "Phone" },
            key = json?.optString("key").orEmpty().ifBlank { Pairing.newKey() },
        )
        val saved = json?.optJSONArray("peers") ?: JSONArray()
        for (i in 0 until saved.length()) {
            val peer = saved.getJSONObject(i)
            peers.add(Peer(peer.getString("id"), peer.optString("name"), peer.getString("key")))
        }
        if (json == null) save()
    }

    private fun save() {
        val json = JSONObject().put("id", me.id).put("name", me.name).put("key", me.key).put(
            "peers",
            JSONArray().apply { peers.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("key", it.key)) } },
        )
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    private companion object {
        const val TAG = "DeskLess"
        const val SERVICE = "com.techazsure.deskless.link"
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        // Every phone both announces itself and looks for the others, so it does not matter who opens the app first.
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        const val MAX_PACK_BYTES = 64L * 1024 * 1024
    }
}
