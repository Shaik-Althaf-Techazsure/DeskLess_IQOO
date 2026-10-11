package com.techazsure.deskless.link

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
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

/** How far a report sent from this phone has got on its way to one phone. */
enum class Stage(val words: String) {
    WAITING("Waiting for a phone in range"),
    RELAYED("With a relay phone, sealed"),
    HANDED("Handed over"),
    DELIVERED("Delivered"),
}

/** One report sent from this phone to one linked phone. [id] is the name of its sealed copy. */
data class Sent(val id: String, val report: String, val title: String, val to: String, val stage: Stage, val at: Long)

/**
 * The link between phones running DeskLess, with no network. Bluetooth finds the other phone and makes the
 * first connection; the two then move up to a direct Wi-Fi link when both have Wi-Fi switched on. Phones
 * are linked once, by one scanning the other's QR code; after that they connect by themselves whenever
 * both have DeskLess open, with nobody tapping Accept.
 *
 * A report is sent to chosen phones, and travels sealed ([Seal]): if the phone it is for is not in range,
 * any linked phone that is takes the sealed copy and carries it, handing it on to the phones it meets
 * until one of them is the phone it is for. A phone that carries a report cannot open it. The phone that
 * opens it sends back a signed receipt, which travels the same way and lets every carrier drop its copy.
 */
class Link(
    private val context: Context,
    private val voice: Voice,
    /**
     * Takes in a report pack that has arrived and been opened. [via] is the name of the phone that handed
     * it over when that was not the sender. Returns the report's title, or null if it is not a report.
     */
    private val takeIn: (pack: File, from: Peer, via: String?) -> String?,
    private val notify: (String) -> Unit,
) {
    private val file = File(context.filesDir, "link.json")
    private val carry = File(context.filesDir, "carry").apply { mkdirs() }
    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val main = Handler(Looper.getMainLooper())

    /** This phone: its code, its name, the secret its QR code carries, and its public key. */
    var me by mutableStateOf(Peer("", "", ""))
        private set
    private lateinit var keys: Seal.Keys

    /** The phones this one has been linked with. */
    val peers = mutableStateListOf<Peer>()

    /** Codes of the linked phones that are in range right now and have proved who they are. */
    val connected = mutableStateListOf<String>()

    /** Reports sent from this phone, newest last, each with how far it has got. */
    val sent = mutableStateListOf<Sent>()

    /** The labels of sealed reports this phone is carrying for other phones. It cannot open them. */
    val carrying = mutableStateListOf<Seal.Label>()

    /** How far each sealed report on its way out of this phone has got, 0 to 1, by its id. */
    val progress = mutableStateMapOf<String, Float>()

    /**
     * For each connected phone, whether the link has moved up to Wi-Fi. Until it has, a report crosses on
     * Bluetooth, which takes minutes rather than seconds.
     */
    val fast = mutableStateMapOf<String, Boolean>()

    /** True while the phone is announcing itself and looking for linked phones. */
    var running by mutableStateOf(false)
        private set

    /** The last thing that went wrong starting the link, for the side bar; null when nothing did. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** A sealed report this phone holds: one of its own on the way out, or one it carries for others. */
    private class Hold(val label: Seal.Label, val file: File)

    /** A receipt: the phone a report was for says, with its signature, that it has opened it. */
    private class Receipt(val id: String, val by: String, val signature: String, val at: Long)

    private val holds = LinkedHashMap<String, Hold>()
    private val receipts = LinkedHashMap<String, Receipt>()

    /** endpoint → the code the phone at the other end claims. */
    private val claimed = HashMap<String, String>()
    private val seen = HashMap<String, String>()
    private val proven = HashSet<String>()

    /** endpoint → the sealed reports the phone at the other end already holds or knows to be delivered. */
    private val theirs = HashMap<String, MutableSet<String>>()

    /** payload → the endpoint it is going to and the sealed report it carries. */
    private val sending = HashMap<Long, Pair<String, String>>()
    private var discovering = false
    private var announcing = false

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

    /**
     * Whether Wi-Fi is switched on; null on a phone without it. With it off on either phone the link
     * stays on Bluetooth, and a report takes minutes where it could take seconds.
     */
    val wifiOn: Boolean?
        get() = runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled }.getOrNull()

    /** Called when a linked phone has come into reach and proved itself: what waited for one can go. */
    var met: () -> Unit = {}

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
        announce()
        look()
        main.removeCallbacks(watch)
        main.postDelayed(watch, WATCH_MILLIS)
    }

    /**
     * Announces this phone to others. Asked again every so often: the part of Android that runs the link
     * can be restarted under the app, and when it has been, it has forgotten that this phone was
     * announcing and every connection it had. The app then starts again from a clean slate.
     */
    private fun announce() {
        val name = Pairing.endpointName(me)
        client.startAdvertising(name, SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnSuccessListener {
                if (announcing) {
                    // It took the request though the app believed it was already announcing: it was restarted.
                    forgetConnections()
                    look()
                }
                announcing = true
            }
            .addOnFailureListener { if (!already(it)) failedToStart(it) }
    }

    private val watch = object : Runnable {
        override fun run() {
            if (!running) return
            announce()
            main.postDelayed(this, WATCH_MILLIS)
        }
    }

    /** True for the answer "you are already doing that", which is no failure. */
    private fun already(error: Exception): Boolean = (error as? ApiException)?.statusCode.let {
        it == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || it == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING
    }

    /** Looks for linked phones. It is paused while one is connected: scanning slows the link it would share. */
    private fun look() {
        if (discovering || !running) return
        discovering = true
        client.startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { if (!already(it)) failedToStart(it) }
    }

    /** Drops what is known about who is connected. The sealed reports themselves are kept. */
    private fun forgetConnections() {
        discovering = false
        claimed.clear()
        seen.clear()
        proven.clear()
        theirs.clear()
        connected.clear()
        fast.clear()
        // Whatever was half-way goes again the next time the phones meet.
        sending.clear()
        progress.clear()
    }

    fun stop() {
        if (!running) return
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
        running = false
        announcing = false
        main.removeCallbacks(watch)
        forgetConnections()
    }

    /** Links with the phone whose QR code was just scanned, and connects to it as soon as it is in range. */
    fun pair(scanned: Peer): Boolean {
        if (scanned.id == me.id) return false
        val known = peers.firstOrNull { it.id == scanned.id }
        peers.removeAll { it.id == scanned.id }
        peers.add(scanned.copy(public = known?.public.orEmpty()))
        save()
        justScanned = scanned.id
        // It may already be in sight; if so, call it now.
        seen.entries.firstOrNull { it.value == scanned.id }?.let { consider(it.key, it.value) }
        return true
    }

    fun forget(id: String) {
        peers.removeAll { it.id == id }
        claimed.entries.firstOrNull { it.value == id }?.let { runCatching { client.disconnectFromEndpoint(it.key) } }
        connected.remove(id)
        save()
    }

    /**
     * Sends a report pack to the chosen linked phones. A sealed copy is made for each, and it goes at once
     * to that phone if it is in range, or to whichever linked phones are, to be carried. Returns how many
     * of the chosen phones it could be sealed for; a phone not yet met since linking has no key to seal for.
     */
    fun send(pack: File, report: String, title: String, to: List<String>): Int {
        var sealed = 0
        for (peer in peers.filter { it.id in to }) {
            if (peer.public.isBlank()) continue
            // A report still on its way to this phone is not sent a second time.
            if (sent.any { it.report == report && it.to == peer.id && it.stage != Stage.DELIVERED && it.id in holds }) {
                sealed++
                continue
            }
            val id = Seal.newId()
            val out = File(carry, "$id.seal")
            val made = System.currentTimeMillis()
            val label = runCatching {
                Seal.seal(pack, out, id, me.id, me.name, peer.id, made, keys, peer.public)
                Seal.label(out)
            }.getOrNull()
            if (label == null) {
                out.delete()
                continue
            }
            holds[id] = Hold(label, out)
            sent.add(Sent(id, report, title, peer.id, Stage.WAITING, made))
            sealed++
        }
        if (sealed > 0) {
            save()
            proven.toList().forEach(::offer)
        }
        return sealed
    }

    /** Where the latest copy of [report] sent to phone [to] has got; null if it was never sent there. */
    fun stage(report: String, to: String): Sent? = sent.lastOrNull { it.report == report && it.to == to }

    private fun endpointOf(id: String): String? =
        claimed.entries.firstOrNull { it.value == id && it.key in proven }?.key

    /**
     * Hands the phone at [endpoint] every sealed report it should have and does not: those addressed to
     * it, and those for phones out of reach, for it to carry. A report whose own phone is connected right
     * now goes to that phone only.
     */
    private fun offer(endpoint: String) {
        val peer = claimed[endpoint]?.takeIf { endpoint in proven } ?: return
        val has = theirs.getOrPut(endpoint) { HashSet() }
        for (hold in holds.values.toList()) {
            val id = hold.label.id
            if (id in has || id in receipts || hold.label.from == peer) continue
            if (sending.values.any { it.first == endpoint && it.second == id }) continue
            if (hold.label.to != peer && endpointOf(hold.label.to) != null) continue
            push(endpoint, hold)
        }
    }

    private fun push(endpoint: String, hold: Hold) {
        val payload = try {
            Payload.fromStream(FileInputStream(hold.file))
        } catch (e: Exception) {
            return
        }
        val id = hold.label.id
        sending[payload.id] = endpoint to id
        if (hold.label.from == me.id) progress[id] = 0f
        client.sendPayload(endpoint, payload).addOnFailureListener { ended(payload.id) }
    }

    /** A sealed report has stopped travelling to one phone, whether or not it got there. */
    private fun ended(payload: Long): Pair<String, String>? {
        val was = sending.remove(payload) ?: return null
        if (sending.values.none { it.second == was.second }) progress.remove(was.second)
        return was
    }

    private fun failedToStart(error: Exception) {
        Log.w(TAG, "Nearby did not start", error)
        problem = error.message ?: "The link could not start."
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
        }
        running = false
        discovering = false
        announcing = false
        main.removeCallbacks(watch)
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
            // Nobody is asked to accept. The proof that follows decides whether this phone is let in.
            client.acceptConnection(endpoint, payloads)
        }

        override fun onConnectionResult(endpoint: String, result: ConnectionResolution) {
            val id = claimed[endpoint]
            if (!result.status.isSuccess || id == null) {
                claimed.remove(endpoint)
                return
            }
            // Each phone shows the other it knows the secret they share, and hands over its public key
            // under that proof. A phone this one has not met must have scanned this phone's QR code, so it
            // is this phone's own secret that it has to know.
            val key = peers.firstOrNull { it.id == id }?.key ?: me.key
            say(
                endpoint,
                JSONObject().put("t", "hello").put("id", me.id).put("name", me.name).put("public", me.public)
                    .put("proof", Pairing.proof(key, me.id, id, me.public)),
            )
            main.postDelayed({
                if (endpoint in claimed && endpoint !in proven) runCatching { client.disconnectFromEndpoint(endpoint) }.also { dropped(endpoint) }
            }, 8_000L)
        }

        override fun onDisconnected(endpoint: String) = dropped(endpoint)

        override fun onBandwidthChanged(endpoint: String, info: BandwidthInfo) {
            claimed[endpoint]?.let { fast[it] = info.quality == BandwidthInfo.Quality.HIGH }
        }
    }

    private fun dropped(endpoint: String) {
        val id = claimed.remove(endpoint)
        proven.remove(endpoint)
        theirs.remove(endpoint)
        // Whatever was on its way to that phone stops; the sealed copies stay for when it returns.
        sending.filterValues { it.first == endpoint }.keys.toList().forEach(::ended)
        if (id != null && claimed.none { it.value == id && it.key in proven }) {
            connected.remove(id)
            fast.remove(id)
        }
        if (proven.isEmpty()) look()
    }

    private fun say(endpoint: String, message: JSONObject) {
        client.sendPayload(endpoint, Payload.fromBytes(message.toString().toByteArray()))
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpoint: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { heard(endpoint, String(it)) }
                // Nothing is taken from a phone that has not proved itself.
                Payload.Type.STREAM -> if (endpoint in proven) receive(endpoint, payload)
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpoint: String, update: PayloadTransferUpdate) {
            val (_, id) = sending[update.payloadId] ?: return
            val hold = holds[id]
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // How far it has got, for the screen. A whole per cent at a time is often enough.
                    val size = hold?.file?.length() ?: 0L
                    if (size > 0 && id in progress) {
                        val share = (update.bytesTransferred.toFloat() / size).coerceIn(0f, 1f)
                        if (share - (progress[id] ?: 0f) >= 0.01f) progress[id] = share
                    }
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    ended(update.payloadId)
                    theirs.getOrPut(endpoint) { HashSet() }.add(id)
                    if (hold != null && hold.label.from == me.id) handed(id, to = claimed[endpoint].orEmpty())
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    ended(update.payloadId)
                    // Still connected: try again in a moment rather than leave it sitting.
                    main.postDelayed({ if (endpoint in proven) offer(endpoint) }, 5_000L)
                }
                else -> Unit
            }
        }
    }

    /** One of this phone's own reports has reached another phone: the one it is for, or one that will carry it. */
    private fun handed(id: String, to: String) {
        val at = sent.indexOfFirst { it.id == id }
        if (at < 0) return
        val mine = sent[at]
        // The receipt can overtake this news; a report already known to be opened needs no more said.
        if (mine.stage == Stage.DELIVERED) return
        val name = peers.firstOrNull { it.id == to }?.name ?: "another phone"
        // Only a report is announced as one. What a user hands over to be sent on is told to them by
        // their own screen, in its own words.
        val report = mine.title != VOICE_TITLE && mine.title != ENTRY_TITLE
        if (to == mine.to) {
            sent[at] = mine.copy(stage = Stage.HANDED)
            if (report) voice.say("Report sent")
            if (mine.title != ENTRY_TITLE) notify("${mine.title} sent to $name.")
        } else if (mine.stage == Stage.WAITING) {
            sent[at] = mine.copy(stage = Stage.RELAYED)
            val goal = peers.firstOrNull { it.id == mine.to }?.name ?: "its phone"
            if (report) voice.say("Report relayed")
            if (mine.title != ENTRY_TITLE) notify("${mine.title} is with $name, sealed, to carry to $goal.")
        }
        save()
    }

    /** What the phone at the other end said: its proof, what it holds, or a receipt. */
    private fun heard(endpoint: String, text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("t")) {
            "hello" -> greeted(endpoint, json)
            "sync" -> if (endpoint in proven) {
                val has = theirs.getOrPut(endpoint) { HashSet() }
                json.optJSONArray("have")?.let { list -> for (i in 0 until list.length()) has.add(list.getString(i)) }
                settle(receiptsIn(json.optJSONArray("done")), from = endpoint)
                offer(endpoint)
            }
            "receipt" -> if (endpoint in proven) settle(receiptsIn(JSONArray().put(json)), from = endpoint)
        }
    }

    /** Checks the other phone's proof. A phone that proves itself for the first time becomes a linked phone. */
    private fun greeted(endpoint: String, json: JSONObject) {
        val id = json.optString("id")
        val public = json.optString("public")
        val known = peers.firstOrNull { it.id == id }
        val key = known?.key ?: me.key
        if (id != claimed[endpoint] || public.isBlank() || json.optString("proof") != Pairing.proof(key, id, me.id, public)) {
            runCatching { client.disconnectFromEndpoint(endpoint) }
            dropped(endpoint)
            return
        }
        val name = json.optString("name").ifBlank { id }
        if (known == null) {
            peers.add(Peer(id, name, key, public))
            save()
            voice.say("Phone linked")
            notify("Linked with $name.")
        } else if (known.name != name || known.public != public) {
            peers[peers.indexOf(known)] = known.copy(name = name, public = public)
            save()
        }
        if (id == justScanned) {
            justScanned = null
            voice.say("Phone linked")
            notify("Linked with $name.")
        }
        proven.add(endpoint)
        if (id !in connected) connected.add(id)
        met()
        // With the phone found, the search stops: scanning for others would slow the link to this one.
        if (discovering) {
            runCatching { client.stopDiscovery() }
            discovering = false
        }
        // Each tells the other what it holds and what it knows to be delivered; what is missing then flows.
        say(
            endpoint,
            JSONObject().put("t", "sync").put("have", JSONArray(holds.keys.toList()))
                .put("done", JSONArray().apply { receipts.values.toList().takeLast(MAX_RECEIPTS_SAID).forEach { put(toJson(it)) } }),
        )
    }

    /** Reads an arriving sealed report off the link, then opens it if it is for this phone or keeps it to carry. */
    private fun receive(endpoint: String, payload: Payload) {
        val input = payload.asStream()?.asInputStream() ?: return
        val handedBy = peers.firstOrNull { it.id == claimed[endpoint] }
        thread(name = "link-receive") {
            val arrived = File(carry, "arriving-${payload.id}.tmp")
            val whole = runCatching {
                input.use { stream ->
                    arrived.outputStream().use { out ->
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
            val label = if (whole) Seal.label(arrived) else null
            if (label == null) {
                arrived.delete()
                return@thread
            }
            main.post {
                theirs.getOrPut(endpoint) { HashSet() }.add(label.id)
                when {
                    label.to != me.id -> carried(endpoint, arrived, label)
                    // The same report can reach this phone along two ways; the second copy is only answered.
                    label.id in receipts -> {
                        arrived.delete()
                        receipts[label.id]?.let { say(endpoint, toJson(it).put("t", "receipt")) }
                    }
                    else -> open(arrived, label, via = handedBy?.takeIf { it.id != label.from }?.name)
                }
            }
        }
    }

    /** Opens a sealed report that is for this phone, takes the report in, and answers with a receipt. */
    private fun open(arrived: File, label: Seal.Label, via: String?) {
        // Only a phone this one is linked with can have sealed it.
        val sender = peers.firstOrNull { it.id == label.from && it.public.isNotBlank() }
        thread(name = "link-open") {
            val pack = File(context.cacheDir, "opened-${label.id}.zip")
            val opened = sender != null && Seal.open(arrived, pack, keys, sender.public)
            val title = if (opened && sender != null) runCatching { takeIn(pack, sender, via) }.getOrNull() else null
            pack.delete()
            arrived.delete()
            main.post {
                if (title == null || sender == null) {
                    notify("A sealed report arrived that this phone could not open.")
                    return@post
                }
                // A spoken message is played by the app as it arrives, and an entry to send on is said
                // by the app in its own words; only a report is announced.
                if (title != VOICE_TITLE && title != ENTRY_TITLE) {
                    voice.say("Report received")
                    notify("$title received from ${sender.name}" + if (via != null) ", carried by $via." else ".")
                }
                // The receipt tells the sender it arrived and lets every phone carrying a copy drop it.
                if (label.id !in receipts) {
                    val receipt = Receipt(label.id, me.id, Seal.sign(keys, receiptText(label.id)), System.currentTimeMillis())
                    receipts[label.id] = receipt
                    save()
                    proven.toList().forEach { say(it, toJson(receipt).put("t", "receipt")) }
                }
            }
        }
    }

    /** A sealed report for another phone has arrived: keep it, unopened, and pass it to the phones in range. */
    private fun carried(endpoint: String, arrived: File, label: Seal.Label) {
        theirs.getOrPut(endpoint) { HashSet() }.add(label.id)
        if (label.id in receipts || label.id in holds) {
            arrived.delete()
            return
        }
        val kept = File(carry, "${label.id}.seal")
        if (!arrived.renameTo(kept)) {
            arrived.delete()
            return
        }
        holds[label.id] = Hold(label, kept)
        carrying.add(label)
        val goal = peers.firstOrNull { it.id == label.to }?.name ?: label.to
        notify("Carrying a sealed report from ${label.fromName} for $goal. This phone cannot open it.")
        proven.filter { it != endpoint }.forEach(::offer)
    }

    /**
     * Takes note of receipts. A receipt from a phone this one knows must carry that phone's signature; for
     * a report this phone only carries for a phone it does not know, the receipt is taken on trust, which
     * can do no more than make this phone drop a copy early.
     */
    private fun settle(arrived: List<Receipt>, from: String) {
        val fresh = arrived.filter { receipt ->
            if (receipt.id in receipts) return@filter false
            val signer = peers.firstOrNull { it.id == receipt.by }
            if (signer != null && signer.public.isNotBlank()) {
                Seal.signedBy(signer.public, receiptText(receipt.id), receipt.signature)
            } else {
                holds[receipt.id]?.label?.to == receipt.by
            }
        }
        if (fresh.isEmpty()) return
        for (receipt in fresh) {
            receipts[receipt.id] = receipt
            holds.remove(receipt.id)?.file?.delete()
            carrying.removeAll { it.id == receipt.id }
            val at = sent.indexOfFirst { it.id == receipt.id }
            if (at >= 0 && sent[at].stage != Stage.DELIVERED) {
                val mine = sent[at]
                sent[at] = mine.copy(stage = Stage.DELIVERED)
                val name = peers.firstOrNull { it.id == mine.to }?.name ?: "its phone"
                // A report handed straight over has already been announced; one that was carried has not.
                if (mine.title != ENTRY_TITLE) {
                    if (mine.stage != Stage.HANDED && mine.title != VOICE_TITLE) voice.say("Report delivered")
                    notify("${mine.title} was opened on $name.")
                }
            }
        }
        save()
        // Pass the news on, so phones further back along the way can drop their copies too.
        proven.filter { it != from }.forEach { other -> fresh.forEach { say(other, toJson(it).put("t", "receipt")) } }
    }

    private fun receiptText(id: String) = "deskless-receipt:$id"

    private fun toJson(receipt: Receipt) =
        JSONObject().put("id", receipt.id).put("by", receipt.by).put("sig", receipt.signature).put("at", receipt.at)

    private fun receiptsIn(list: JSONArray?): List<Receipt> = (0 until (list?.length() ?: 0)).mapNotNull { at ->
        val json = list!!.optJSONObject(at) ?: return@mapNotNull null
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Receipt(id, json.optString("by"), json.optString("sig"), json.optLong("at", System.currentTimeMillis()))
    }

    private fun load() {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        val private = json?.optString("private").orEmpty()
        val public = json?.optString("public").orEmpty()
        keys = if (private.isNotBlank() && public.isNotBlank()) Seal.Keys(private, public) else Seal.newKeys()
        me = Peer(
            id = json?.optString("id").orEmpty().ifBlank { Pairing.newId() },
            name = json?.optString("name").orEmpty().ifBlank { Build.MODEL ?: "Phone" },
            key = json?.optString("key").orEmpty().ifBlank { Pairing.newKey() },
            public = keys.public,
        )
        val now = System.currentTimeMillis()
        json?.optJSONArray("peers")?.let { saved ->
            for (i in 0 until saved.length()) {
                val peer = saved.getJSONObject(i)
                peers.add(Peer(peer.getString("id"), peer.optString("name"), peer.getString("key"), peer.optString("public")))
            }
        }
        receiptsIn(json?.optJSONArray("receipts")).filter { now - it.at < KEEP_RECEIPTS_MILLIS }.forEach { receipts[it.id] = it }
        json?.optJSONArray("sent")?.let { saved ->
            for (i in 0 until saved.length()) {
                val one = saved.getJSONObject(i)
                val stage = Stage.entries.firstOrNull { it.name == one.optString("stage") } ?: Stage.WAITING
                sent.add(Sent(one.getString("id"), one.optString("report"), one.optString("title"), one.optString("to"), stage, one.optLong("at")))
            }
        }
        // The sealed copies on the phone: its own still on the way out, and those it carries for others.
        carry.listFiles().orEmpty().forEach { held ->
            val label = if (held.name.endsWith(".seal")) Seal.label(held) else null
            if (label == null || label.id in receipts || now - label.made > KEEP_SEALED_MILLIS) {
                held.delete()
            } else {
                holds[label.id] = Hold(label, held)
                if (label.from != me.id) carrying.add(label)
            }
        }
        if (private.isBlank()) save()
    }

    private fun save() {
        val json = JSONObject()
            .put("id", me.id).put("name", me.name).put("key", me.key)
            .put("private", keys.private).put("public", keys.public)
            .put(
                "peers",
                JSONArray().apply {
                    peers.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("key", it.key).put("public", it.public)) }
                },
            )
            .put(
                "sent",
                JSONArray().apply {
                    sent.toList().takeLast(MAX_SENT_KEPT).forEach {
                        put(
                            JSONObject().put("id", it.id).put("report", it.report).put("title", it.title).put("to", it.to)
                                .put("stage", it.stage.name).put("at", it.at)
                        )
                    }
                },
            )
            .put("receipts", JSONArray().apply { receipts.values.toList().takeLast(MAX_RECEIPTS_KEPT).forEach { put(toJson(it)) } })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    private companion object {
        const val TAG = "DeskLess"
        const val SERVICE = "com.techazsure.deskless.link"
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        // Every phone both announces itself and looks for the others, so it does not matter who opens the
        // app first. The star arrangement is the one that lets two phones move up from Bluetooth to a
        // direct Wi-Fi link; the looser cluster arrangement stays on Bluetooth unless both phones are
        // already on the same Wi-Fi network, and on real phones that meant about 10 KB a second.
        val STRATEGY: Strategy = Strategy.P2P_STAR
        const val MAX_PACK_BYTES = 64L * 1024 * 1024
        const val WATCH_MILLIS = 20_000L

        /** A sealed report nobody has collected is dropped after three days; a receipt is remembered for a week. */
        const val KEEP_SEALED_MILLIS = 3L * 24 * 60 * 60 * 1000
        const val KEEP_RECEIPTS_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val MAX_RECEIPTS_SAID = 60
        const val MAX_RECEIPTS_KEPT = 200
        const val MAX_SENT_KEPT = 100
    }
}
