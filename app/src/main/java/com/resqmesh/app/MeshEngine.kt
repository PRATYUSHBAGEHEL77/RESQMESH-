package com.resqmesh.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
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
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

data class PendingPeer(val endpointId: String, val name: String, val digits: String)

data class UiEvent(
    val id: String,
    val kind: String,
    val text: String,
    val detail: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val time: Long = System.currentTimeMillis()
)

/** Delivery record for an SOS that THIS phone originated. */
data class SosRecord(
    val id: String,
    val text: String,
    val createdAt: Long,
    val status: String, // QUEUED, SENT, ACKNOWLEDGED, EXPIRED
    val attempts: Int,
    val ackedBy: List<String>,
    val locationSource: String // LIVE, CACHED, NONE
)

/**
 * All mesh logic lives here (application scope), not in the Activity, so that a foreground
 * service can keep the mesh alive with the screen off or the Activity destroyed.
 */
object MeshEngine {
    private const val SERVICE_ID = "com.resqmesh.app.SOS"
    private const val MAX_HOPS = 5
    private const val OUTBOX_MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val RETRY_INTERVAL_MS = 20_000L
    private const val MAX_SEEN = 500
    private const val MAX_EVENTS = 150
    private const val FRESH_CACHED_MAX_AGE_MS = 60_000L
    private const val LAST_KNOWN_MAX_AGE_MS = 30L * 60 * 1000
    private const val LIVE_FIX_TIMEOUT_MS = 15_000L
    private const val KEY_ALIAS = "resqmesh_identity"
    private const val SEP = "\u001f"

    const val CH_MESH = "resqmesh_mesh"
    const val CH_SOS = "resqmesh_incoming_sos"

    private var initialized = false
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(app) }
    private val prefs by lazy { app.getSharedPreferences("resqmesh_durable", Context.MODE_PRIVATE) }

    // ---------- UI-observable state ----------
    val events = mutableStateListOf<UiEvent>()
    val pendingPeers = mutableStateListOf<PendingPeer>()
    val peers = mutableStateListOf<String>()
    val sosRecords = mutableStateListOf<SosRecord>()
    var localName by mutableStateOf("RESQ-Phone")
        private set
    var status by mutableStateOf("Offline. Start mesh on each test phone.")
        private set
    var running by mutableStateOf(false)
        private set
    var gpsStatus by mutableStateOf("GPS not requested yet.")
        private set

    // ---------- internal state ----------
    private val connected = ConcurrentHashMap<String, String>() // endpointId -> peer name
    private val pendingNames = ConcurrentHashMap<String, String>() // endpointId -> name (before accept)
    private val requesting = ConcurrentHashMap.newKeySet<String>()
    private val lastAttempt = ConcurrentHashMap<String, Long>()
    private val seenIds = LinkedHashSet<String>()
    private var tick = 0
    private var sosNotifId = 2000

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        initialized = true
        createChannels(app)
        initLocalName()
        ensureIdentity()
        loadEvents()
        loadSeen()
        loadRecords()
    }

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_MESH, "Mesh running", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SOS, "Incoming SOS", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    // =====================================================================
    // Identity, signing, verification
    // =====================================================================

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureIdentity() {
        runCatching {
            val ks = keyStore()
            if (!ks.containsAlias(KEY_ALIAS)) {
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                kpg.initialize(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                kpg.generateKeyPair()
            }
        }.onFailure {
            addEvent("WARN", "Identity key unavailable", "Messages will be sent unsigned: ${it.localizedMessage}")
        }
    }

    private fun myPublicKeyB64(): String? = runCatching {
        Base64.encodeToString(keyStore().getCertificate(KEY_ALIAS).publicKey.encoded, Base64.NO_WRAP)
    }.getOrNull()

    private fun sign(data: String): String? = runCatching {
        val pk = keyStore().getKey(KEY_ALIAS, null) as PrivateKey
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(pk)
        s.update(data.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }.getOrNull()

    private fun verifySignature(data: String, sigB64: String, pubB64: String): Boolean = runCatching {
        val pub = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(pub)
        s.update(data.toByteArray(Charsets.UTF_8))
        s.verify(Base64.decode(sigB64, Base64.NO_WRAP))
    }.getOrDefault(false)

    private fun fingerprint(pubB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Base64.decode(pubB64, Base64.NO_WRAP))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun coord(o: JSONObject, key: String): String =
        if (o.isNull(key)) "-" else String.format(Locale.US, "%.6f", o.getDouble(key))

    private fun longField(o: JSONObject, key: String): String =
        if (o.isNull(key)) "-" else o.getLong(key).toString()

    /** Only immutable fields are signed: relays change ttl/hops/path, so those are excluded. */
    private fun sosCanonical(o: JSONObject): String = listOf(
        "SOS", o.optString("id"), o.optString("origin"), o.optString("text"),
        longField(o, "createdAt"), coord(o, "latitude"), coord(o, "longitude"),
        o.optString("locationSource", "-"), longField(o, "locationTimestamp")
    ).joinToString(SEP)

    private fun ackCanonical(o: JSONObject): String = listOf(
        "ACK", o.optString("id"), o.optString("originalId"), o.optString("ackBy"),
        longField(o, "createdAt")
    ).joinToString(SEP)

    /**
     * Returns VERIFIED (valid signature, key matches the one first seen for this name),
     * NEW_KEY (valid, first time this name is seen), KEY_CHANGED (valid signature but a
     * different key than before for this name: possible impersonation), INVALID or UNSIGNED.
     */
    private fun verifyMessage(o: JSONObject, canonical: String, nameField: String): Pair<String, String?> {
        val sig = o.optString("sig", "")
        val pub = o.optString("pub", "")
        if (sig.isBlank() || pub.isBlank()) return "UNSIGNED" to null
        if (!verifySignature(canonical, sig, pub)) return "INVALID" to null
        val fp = fingerprint(pub)
        val name = o.optString(nameField)
        val known = prefs.getString("key_$name", null)
        val result = when {
            known == null -> {
                prefs.edit().putString("key_$name", fp).apply()
                "NEW_KEY"
            }
            known == fp -> "VERIFIED"
            else -> "KEY_CHANGED"
        }
        return result to fp
    }

    // =====================================================================
    // Mesh lifecycle
    // =====================================================================

    private fun initLocalName() {
        val saved = prefs.getString("local_name", null)
        if (!saved.isNullOrBlank()) {
            localName = saved
            return
        }
        val androidId = android.provider.Settings.Secure.getString(
            app.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
        val model = Build.MODEL.orEmpty().replace(Regex("[^A-Za-z0-9]"), "").take(8).ifBlank { "Phone" }
        localName = "RESQ-$model-${androidId.takeLast(4)}"
        prefs.edit().putString("local_name", localName).apply()
    }

    fun setName(name: String) {
        localName = name.take(24)
        prefs.edit().putString("local_name", localName).apply()
    }

    fun start() {
        if (running) return
        running = true
        status = "Starting advertising and discovery…"
        startAdvertisingAndDiscovery()
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
    }

    fun stop() {
        main.removeCallbacks(retryRunnable)
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
        connected.clear()
        pendingNames.clear()
        requesting.clear()
        post {
            peers.clear()
            pendingPeers.clear()
        }
        running = false
        status = "Stopped. No messages can be relayed while stopped."
    }

    private fun startAdvertisingAndDiscovery() {
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(
            localName.ifBlank { "RESQ-Phone" },
            SERVICE_ID,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener {
            status = "Advertising. Searching for nearby RESQMesh devices…"
        }.addOnFailureListener { e ->
            status = "Advertising failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
        client.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener { e ->
            status = "Discovery failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    /** Discovery only reports an endpoint once; after a drop we must restart it to find peers again. */
    private fun restartDiscovery() {
        if (!running) return
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
        }
        requesting.clear()
        startAdvertisingAndDiscovery()
    }

    private val retryRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            tick++
            val now = System.currentTimeMillis()
            outboxEntries().forEach { (id, wrapper) ->
                val created = wrapper.getJSONObject("env").optLong("createdAt", now)
                if (now - created > OUTBOX_MAX_AGE_MS) {
                    expire(id)
                    return@forEach
                }
                if (connected.isNotEmpty() && now - (lastAttempt[id] ?: 0L) >= RETRY_INTERVAL_MS) {
                    transmit(id, wrapper, false)
                }
            }
            if (connected.isEmpty() && tick % 3 == 0) restartDiscovery()
            main.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (connected.containsKey(endpointId)) return
            val remoteName = info.endpointName
            if (remoteName == localName) return
            // The lexicographically smaller name initiates, to avoid crossed requests.
            if (localName.lowercase() < remoteName.lowercase() && requesting.add(endpointId)) {
                client.requestConnection(localName, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener { e ->
                        requesting.remove(endpointId)
                        addEvent("SYSTEM", "Connection request failed", "$remoteName: ${e.localizedMessage}")
                    }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            requesting.remove(endpointId)
            addEvent("SYSTEM", "Peer no longer visible", connected[endpointId] ?: endpointId)
        }
    }

    private fun trustedPeers(): Set<String> = prefs.getStringSet("trusted_peers", emptySet()) ?: emptySet()

    private fun trustPeer(name: String) {
        val updated = HashSet(trustedPeers())
        updated.add(name)
        prefs.edit().putStringSet("trusted_peers", updated).apply()
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingNames[endpointId] = info.endpointName
            if (trustedPeers().contains(info.endpointName)) {
                // Previously verified by the user. Message signatures still catch impersonation.
                addEvent("SECURITY", "Known peer reconnecting", "Auto-accepting ${info.endpointName} (verified earlier).")
                client.acceptConnection(endpointId, payloadCallback)
                return
            }
            post {
                pendingPeers.removeAll { it.endpointId == endpointId }
                pendingPeers.add(PendingPeer(endpointId, info.endpointName, info.authenticationDigits))
            }
            addEvent(
                "SECURITY", "Peer verification required",
                "Compare code ${info.authenticationDigits} on both phones before accepting ${info.endpointName}."
            )
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            requesting.remove(endpointId)
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    connected[endpointId] = pendingNames[endpointId] ?: endpointId
                    refreshPeers()
                    status = "Connected to ${connected.size} peer(s). Internet is not required for this link."
                    addEvent("SYSTEM", "Connected", "${connected[endpointId]} (endpoint $endpointId)")
                    retryPendingOutbox()
                }
                else -> addEvent("SYSTEM", "Connection failed", "endpoint=$endpointId status=${result.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            val name = connected.remove(endpointId) ?: endpointId
            requesting.remove(endpointId)
            refreshPeers()
            status = "Disconnected from $name. Active peers: ${connected.size}. Reconnecting…"
            addEvent("SYSTEM", "Disconnected", "$name. Mesh will search again.")
            if (running) main.postDelayed({ restartDiscovery() }, 3_000L)
        }
    }

    private fun refreshPeers() = post {
        peers.clear()
        peers.addAll(connected.values)
    }

    fun acceptPeer(peer: PendingPeer) {
        post { pendingPeers.removeAll { it.endpointId == peer.endpointId } }
        trustPeer(peer.name)
        client.acceptConnection(peer.endpointId, payloadCallback)
            .addOnFailureListener { e ->
                addEvent("SECURITY", "Connection acceptance failed", e.localizedMessage ?: e.javaClass.simpleName)
            }
    }

    fun rejectPeer(peer: PendingPeer) {
        post { pendingPeers.removeAll { it.endpointId == peer.endpointId } }
        client.rejectConnection(peer.endpointId)
        addEvent("SECURITY", "Peer rejected", "Rejected ${peer.name} after verification prompt.")
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes()
            if (bytes == null) {
                addEvent("SYSTEM", "Received empty payload", "Endpoint=$endpointId")
                return
            }
            val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse {
                addEvent("WARN", "Received unreadable message", "Endpoint=$endpointId · ${it.localizedMessage ?: "invalid JSON"}")
                return
            }
            runCatching { handleIncoming(endpointId, json) }.onFailure {
                addEvent("WARN", "Message handling error", it.localizedMessage ?: it.javaClass.simpleName)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    // =====================================================================
    // Incoming messages
    // =====================================================================

    private fun handleIncoming(fromEndpoint: String, env: JSONObject) {
        val kind = env.optString("kind", "")
        val id = env.optString("id", "")
        if (id.isBlank() || (kind != "SOS" && kind != "ACK")) return
        val fromName = connected[fromEndpoint] ?: fromEndpoint

        if (isSeen(id)) {
            // Duplicate (retry, or replay of an old message). Never display twice, but a repeated
            // SOS must be re-acknowledged, otherwise a lost ACK looks like a lost SOS.
            if (kind == "SOS") sendAck(fromEndpoint, id)
            return
        }

        val ttl = env.optInt("ttl", 0)
        val hops = env.optInt("hops", 0)

        if (kind == "SOS") {
            val (verdict, fp) = verifyMessage(env, sosCanonical(env), "origin")
            if (verdict == "INVALID") {
                // Not marked as seen, so a genuine copy with the same ID can still arrive.
                addEvent("WARN", "REJECTED tampered SOS", "Signature check failed for id=$id from $fromName. Message discarded.")
                return
            }
            markSeen(id)
            val origin = env.optString("origin", "Unknown")
            val text = env.optString("text", "(empty SOS)")
            val lat = if (env.isNull("latitude")) null else env.optDouble("latitude")
            val lon = if (env.isNull("longitude")) null else env.optDouble("longitude")
            val trust = when (verdict) {
                "VERIFIED" -> "signature OK (key $fp)"
                "NEW_KEY" -> "signature OK, first time seen (key $fp)"
                "KEY_CHANGED" -> "WARNING: signature OK but this name used a different key before (possible impersonation)"
                else -> "WARNING: UNSIGNED message, sender not verified"
            }
            val loc = describeLocation(env)
            val kindTag = if (verdict == "KEY_CHANGED" || verdict == "UNSIGNED") "WARN" else "SOS"
            addEvent(kindTag, "SOS RECEIVED from $origin", "$text · id=$id · hops=$hops · $loc · $trust", lat, lon)
            notifySos(origin, text)
            sendAck(fromEndpoint, id)

            if (verdict == "UNSIGNED") {
                addEvent("WARN", "Not relaying unsigned SOS", "Unsigned messages are shown but never forwarded.")
                return
            }
            if (ttl > 0) {
                val forwarded = JSONObject(env.toString()).put("ttl", ttl - 1).put("hops", hops + 1)
                val path = forwarded.optJSONArray("path") ?: JSONArray()
                path.put(localName)
                forwarded.put("path", path)
                // Custody: keep the relay copy until a peer other than the source acknowledges it.
                saveOutbox(id, forwarded, "RELAY", fromName)
                val wrapper = readOutbox(id)
                if (wrapper == null || !transmit(id, wrapper, true)) {
                    addEvent("SYSTEM", "SOS held for relay", "No other peer yet. Stored on this phone and sent when one connects (expires in 24 h).")
                }
            }
        } else {
            val (verdict, _) = verifyMessage(env, ackCanonical(env), "ackBy")
            if (verdict == "INVALID" || verdict == "UNSIGNED") {
                addEvent("WARN", "REJECTED unverifiable ACK", "Receipt confirmations must be signed. Ignored.")
                return
            }
            markSeen(id)
            val originalId = env.optString("originalId")
            val ackBy = env.optString("ackBy")
            val own = sosRecords.firstOrNull { it.id == originalId }
            if (own != null) {
                if (ackBy != localName) {
                    removeOutbox(originalId)
                    updateRecord(originalId) { r ->
                        r.copy(
                            status = "ACKNOWLEDGED",
                            ackedBy = if (r.ackedBy.contains(ackBy)) r.ackedBy else r.ackedBy + ackBy
                        )
                    }
                    val via = if (hops == 0) "directly" else "via $hops relay hop(s)"
                    addEvent(
                        "ACK", "SOS RECEIPT CONFIRMED",
                        "SOS=$originalId · received by $ackBy ($via). This confirms app-to-app receipt only."
                    )
                }
            } else {
                val wrapper = readOutbox(originalId)
                if (wrapper != null) {
                    val source = wrapper.optString("from", "")
                    if (ackBy != source) {
                        removeOutbox(originalId)
                        addEvent("SYSTEM", "Relay copy delivered onward", "SOS=$originalId acknowledged by $ackBy. Custody released.")
                    }
                }
            }
            if (ttl > 0) {
                val forwarded = JSONObject(env.toString()).put("ttl", ttl - 1).put("hops", hops + 1)
                val targets = connected.keys.filter { it != fromEndpoint }
                if (targets.isNotEmpty()) {
                    client.sendPayload(targets, Payload.fromBytes(forwarded.toString().toByteArray(Charsets.UTF_8)))
                }
            }
        }
    }

    private fun describeLocation(env: JSONObject): String {
        if (env.isNull("latitude") || env.isNull("longitude")) return "no GPS coordinates"
        val acc = if (env.isNull("accuracyMeters")) null else env.optInt("accuracyMeters")
        val src = env.optString("locationSource", "UNKNOWN")
        val age = if (env.isNull("locationAgeSec")) null else env.optLong("locationAgeSec")
        val provider = env.optString("locationProvider", "")
        val sb = StringBuilder("GPS=${coord(env, "latitude")},${coord(env, "longitude")}")
        if (acc != null) sb.append(" ±$acc m")
        sb.append(" · $src")
        if (age != null) sb.append(" (fix was ${age}s old when sent)")
        if (provider.isNotBlank() && provider != "null") sb.append(" · $provider")
        val outdated = src == "CACHED" || (age != null && age > 300)
        val poor = acc != null && acc > 100
        if (outdated || poor) sb.append(" · ⚠ LOCATION MAY BE OUTDATED OR IMPRECISE")
        return sb.toString()
    }

    private fun sendAck(endpointId: String, originalId: String) {
        val ack = JSONObject()
            .put("kind", "ACK")
            .put("id", UUID.randomUUID().toString())
            .put("originalId", originalId)
            .put("ackBy", localName)
            .put("createdAt", System.currentTimeMillis())
            .put("ttl", MAX_HOPS)
            .put("hops", 0)
        signInto(ack, ackCanonical(ack))
        markSeen(ack.getString("id"))
        if (!connected.containsKey(endpointId)) {
            addEvent("WARN", "ACK NOT SENT", "The sender disconnected before the receipt acknowledgement could be delivered.")
            return
        }
        client.sendPayload(endpointId, Payload.fromBytes(ack.toString().toByteArray(Charsets.UTF_8)))
            .addOnSuccessListener {
                addEvent("SYSTEM", "Receipt ACK handed to transport", "Sent to ${connected[endpointId] ?: endpointId}.")
            }
            .addOnFailureListener { e ->
                addEvent("WARN", "ACK SEND FAILED", e.localizedMessage ?: e.javaClass.simpleName)
            }
    }

    private fun signInto(o: JSONObject, canonical: String) {
        val pub = myPublicKeyB64()
        val sig = sign(canonical)
        if (pub != null && sig != null) {
            o.put("pub", pub)
            o.put("sig", sig)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifySos(origin: String, text: String) {
        runCatching {
            val open = PendingIntent.getActivity(
                app, 0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(app, CH_SOS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("SOS from $origin")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            NotificationManagerCompat.from(app).notify(sosNotifId++, n)
        }
    }

    // =====================================================================
    // Outbox (durable queue) and delivery tracking
    // =====================================================================

    private fun saveOutbox(id: String, env: JSONObject, role: String, from: String) {
        val wrapper = JSONObject().put("env", env).put("role", role).put("from", from)
        // commit() is synchronous: the SOS is on disk before any transmission is attempted.
        prefs.edit().putString("outbox_$id", wrapper.toString()).commit()
    }

    private fun readOutbox(id: String): JSONObject? {
        val raw = prefs.getString("outbox_$id", null) ?: return null
        return runCatching { normalise(JSONObject(raw)) }.getOrNull()
    }

    private fun normalise(o: JSONObject): JSONObject =
        if (o.has("env")) o else JSONObject().put("env", o).put("role", "ORIGIN").put("from", "")

    private fun removeOutbox(id: String) {
        if (id.isNotBlank()) prefs.edit().remove("outbox_$id").commit()
        lastAttempt.remove(id)
    }

    private fun outboxEntries(): List<Pair<String, JSONObject>> =
        prefs.all.entries
            .filter { it.key.startsWith("outbox_") && it.value is String }
            .mapNotNull { e ->
                runCatching { e.key.removePrefix("outbox_") to normalise(JSONObject(e.value as String)) }.getOrNull()
            }

    /** Returns true if a transmission was attempted (at least one eligible peer). */
    private fun transmit(id: String, wrapper: JSONObject, announce: Boolean): Boolean {
        val env = wrapper.getJSONObject("env")
        val from = wrapper.optString("from", "")
        val targets = connected.filter { from.isBlank() || it.value != from }.keys.toList()
        if (targets.isEmpty()) return false
        lastAttempt[id] = System.currentTimeMillis()
        updateRecord(id) { it.copy(attempts = it.attempts + 1) }
        client.sendPayload(targets, Payload.fromBytes(env.toString().toByteArray(Charsets.UTF_8)))
            .addOnSuccessListener {
                updateRecord(id) { r -> if (r.status == "QUEUED") r.copy(status = "SENT") else r }
                if (announce) {
                    addEvent("SYSTEM", "SOS handed to nearby transport", "$id · ${targets.size} peer(s). Not proof of receipt: wait for RECEIPT CONFIRMED.")
                }
            }
            .addOnFailureListener { e ->
                addEvent("WARN", "SEND FAILED", "$id to ${targets.size} peer(s): ${e.localizedMessage ?: e.javaClass.simpleName}. Will retry.")
            }
        return true
    }

    private fun retryPendingOutbox() {
        val entries = outboxEntries()
        if (entries.isEmpty()) return
        addEvent("SYSTEM", "Retrying saved SOS messages", "${entries.size} saved message(s) will be retransmitted.")
        entries.forEach { (id, wrapper) -> transmit(id, wrapper, false) }
    }

    private fun expire(id: String) {
        removeOutbox(id)
        updateRecord(id) { r -> if (r.status != "ACKNOWLEDGED") r.copy(status = "EXPIRED") else r }
        addEvent("WARN", "Saved SOS expired", "$id was not acknowledged within 24 hours and was removed from the queue.")
    }

    private fun updateRecord(id: String, f: (SosRecord) -> SosRecord) = post {
        val i = sosRecords.indexOfFirst { it.id == id }
        if (i >= 0) {
            sosRecords[i] = f(sosRecords[i])
            persistRecords()
        }
    }

    // =====================================================================
    // Sending
    // =====================================================================

    fun sendSos(message: String, location: Location?, source: String) {
        val clean = message.trim().take(500)
        if (clean.isEmpty()) {
            addEvent("SYSTEM", "SOS not sent", "Enter a short message first.")
            return
        }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val env = JSONObject()
            .put("kind", "SOS")
            .put("id", id)
            .put("origin", localName.ifBlank { "RESQ-Phone" })
            .put("text", clean)
            .put("createdAt", now)
            .put("latitude", location?.latitude ?: JSONObject.NULL)
            .put("longitude", location?.longitude ?: JSONObject.NULL)
            .put("accuracyMeters", location?.accuracy?.roundToInt() ?: JSONObject.NULL)
            .put("locationTimestamp", location?.time ?: JSONObject.NULL)
            .put("locationProvider", location?.provider ?: JSONObject.NULL)
            .put("locationSource", if (location == null) "NONE" else source)
            .put("locationAgeSec", if (location == null) JSONObject.NULL else ((now - location.time).coerceAtLeast(0L) / 1000))
            .put("ttl", MAX_HOPS)
            .put("hops", 0)
            .put("path", JSONArray().put(localName))
        signInto(env, sosCanonical(env))
        if (!env.has("sig")) {
            addEvent("WARN", "SOS is unsigned", "The identity key could not be used, so receivers cannot verify this message.")
        }

        markSeen(id)
        val record = SosRecord(id, clean, now, "QUEUED", 0, emptyList(), if (location == null) "NONE" else source)
        post {
            sosRecords.add(0, record)
            persistRecords()
        }
        saveOutbox(id, env, "ORIGIN", "")

        val detail = if (location != null) {
            "$source GPS ${coord(env, "latitude")}, ${coord(env, "longitude")} · ±${location.accuracy.roundToInt()} m · age=${(now - location.time).coerceAtLeast(0L) / 1000}s"
        } else {
            "No GPS coordinates attached."
        }
        addEvent("SOS", "SOS QUEUED", "$id · $detail · saved on this phone until a peer acknowledges receipt", location?.latitude, location?.longitude)

        val wrapper = readOutbox(id)
        if (wrapper == null || !transmit(id, wrapper, true)) {
            val why = if (running) "No connected peers." else "The mesh is not running. Tap Start mesh."
            addEvent("WARN", "SOS QUEUED — NOT DELIVERED", "$why The message is saved and will be sent automatically when a peer connects.", location?.latitude, location?.longitude)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    fun noLocationPermission() {
        gpsStatus = "Location permission denied. SOS sent without coordinates."
        addEvent("WARN", "Location permission denied", "SOS is sent without coordinates so it is not blocked.")
    }

    /**
     * Location policy: use a fix younger than 60 s immediately (labelled CACHED with its age);
     * otherwise try a live fix for 15 s; if that fails fall back to the newest last-known fix up
     * to 30 min old (labelled CACHED); if there is none, send without coordinates and say so.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun sendWithGps(message: String) {
        val clean = message.trim()
        if (clean.isEmpty()) {
            addEvent("SYSTEM", "SOS not sent", "Enter a short message first.")
            return
        }
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            noLocationPermission()
            sendSos(clean, null, "NONE")
            return
        }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = if (fine) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        val enabled = providers.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val now = System.currentTimeMillis()
        val lastKnown = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time in 0..LAST_KNOWN_MAX_AGE_MS }
            .maxByOrNull { it.time }

        fun useCachedOrNone(reason: String) {
            if (lastKnown != null) {
                val age = (System.currentTimeMillis() - lastKnown.time) / 1000
                gpsStatus = "$reason Using CACHED location (${age}s old, ±${lastKnown.accuracy.roundToInt()} m)."
                addEvent("WARN", "Using cached location", "$reason The attached position is ${age}s old and may not be where you are now.")
                sendSos(clean, lastKnown, "CACHED")
            } else {
                gpsStatus = "$reason No usable location. SOS sent WITHOUT coordinates."
                addEvent("WARN", "Location failed", "$reason No cached position exists. SOS sent without coordinates; no location was invented.")
                sendSos(clean, null, "NONE")
            }
        }

        if (enabled.isEmpty()) {
            useCachedOrNone("Device Location is switched off.")
            return
        }
        if (lastKnown != null && now - lastKnown.time <= FRESH_CACHED_MAX_AGE_MS) {
            val age = (now - lastKnown.time) / 1000
            gpsStatus = "Using CACHED location from ${lastKnown.provider}: ${age}s old, ±${lastKnown.accuracy.roundToInt()} m."
            sendSos(clean, lastKnown, "CACHED")
            return
        }

        gpsStatus = "Getting live location… SOS will still be sent if no fix arrives."
        addEvent("SYSTEM", "Live location requested", "Waiting up to ${LIVE_FIX_TIMEOUT_MS / 1000}s for a GPS fix.")
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancellation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) CancellationSignal() else null
        var legacyListener: LocationListener? = null

        val timeout = Runnable {
            if (done.compareAndSet(false, true)) {
                cancellation?.cancel()
                legacyListener?.let { runCatching { lm.removeUpdates(it) } }
                useCachedOrNone("Live location timed out.")
            }
        }
        main.postDelayed(timeout, LIVE_FIX_TIMEOUT_MS)

        fun onLive(location: Location?) {
            if (!done.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            if (location != null) {
                gpsStatus = "LIVE fix from ${location.provider}: ±${location.accuracy.roundToInt()} m."
                sendSos(clean, location, "LIVE")
            } else {
                useCachedOrNone("No live fix was returned.")
            }
        }

        val provider = when {
            fine && enabled.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            enabled.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> enabled.first()
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, cancellation, ContextCompat.getMainExecutor(app)) { loc -> onLive(loc) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { lm.removeUpdates(this) }
                        onLive(location)
                    }

                    @Deprecated("Deprecated by Android")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                legacyListener = listener
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            if (done.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                useCachedOrNone("Location permission error.")
            }
        }
    }

    /** Offline aid: distance and compass bearing from this phone's last known position. No map needed. */
    @SuppressLint("MissingPermission")
    fun directionTo(lat: Double, lon: Double) {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            addEvent("WARN", "Direction unavailable", "Location permission is needed to compute your distance to the SOS.")
            return
        }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val me = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (me == null) {
            addEvent("WARN", "Direction unavailable", "This phone has no recent position. Go outside and try again.")
            return
        }
        val target = Location("sos").apply {
            latitude = lat
            longitude = lon
        }
        val metres = me.distanceTo(target)
        var bearing = me.bearingTo(target)
        if (bearing < 0) bearing += 360f
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val dir = names[((bearing + 22.5f) / 45f).toInt() % 8]
        val dist = if (metres >= 1000f) String.format(Locale.US, "%.1f km", metres / 1000f) else "${metres.roundToInt()} m"
        val age = (System.currentTimeMillis() - me.time).coerceAtLeast(0L) / 1000
        addEvent(
            "DIRECTION", "Direction to SOS",
            "About $dist toward $dir (${bearing.roundToInt()}°), from your last known position (${age}s old). Works with no map and no internet."
        )
    }

    // =====================================================================
    // Persistence
    // =====================================================================

    private fun isSeen(id: String): Boolean = synchronized(seenIds) { seenIds.contains(id) }

    private fun markSeen(id: String) {
        synchronized(seenIds) {
            seenIds.add(id)
            while (seenIds.size > MAX_SEEN) {
                val it = seenIds.iterator()
                it.next()
                it.remove()
            }
            prefs.edit().putString("seen_ids", JSONArray(seenIds.toList()).toString()).apply()
        }
    }

    private fun loadSeen() {
        runCatching {
            val a = JSONArray(prefs.getString("seen_ids", "[]") ?: "[]")
            for (i in 0 until a.length()) seenIds.add(a.getString(i))
        }
    }

    fun addEvent(kind: String, text: String, detail: String, latitude: Double? = null, longitude: Double? = null) {
        post {
            events.add(0, UiEvent(UUID.randomUUID().toString(), kind, text, detail, latitude, longitude))
            while (events.size > MAX_EVENTS) events.removeAt(events.lastIndex)
            persistEvents()
        }
    }

    private fun persistEvents() {
        val array = JSONArray()
        events.take(MAX_EVENTS).forEach { e ->
            array.put(
                JSONObject().put("id", e.id).put("kind", e.kind).put("text", e.text).put("detail", e.detail)
                    .put("lat", e.latitude ?: JSONObject.NULL).put("lon", e.longitude ?: JSONObject.NULL)
                    .put("t", e.time)
            )
        }
        prefs.edit().putString("events_json", array.toString()).apply()
    }

    private fun loadEvents() {
        runCatching {
            val array = JSONArray(prefs.getString("events_json", "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                events.add(
                    UiEvent(
                        o.optString("id"), o.optString("kind"), o.optString("text"), o.optString("detail"),
                        if (o.isNull("lat")) null else o.optDouble("lat"),
                        if (o.isNull("lon")) null else o.optDouble("lon"),
                        o.optLong("t", 0L)
                    )
                )
            }
        }
    }

    private fun persistRecords() {
        val array = JSONArray()
        sosRecords.take(50).forEach { r ->
            array.put(
                JSONObject().put("id", r.id).put("text", r.text).put("createdAt", r.createdAt)
                    .put("status", r.status).put("attempts", r.attempts)
                    .put("ackedBy", JSONArray(r.ackedBy)).put("src", r.locationSource)
            )
        }
        prefs.edit().putString("sos_records_json", array.toString()).apply()
    }

    private fun loadRecords() {
        runCatching {
            val array = JSONArray(prefs.getString("sos_records_json", "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val acked = mutableListOf<String>()
                val a = o.optJSONArray("ackedBy")
                if (a != null) for (j in 0 until a.length()) acked.add(a.getString(j))
                sosRecords.add(
                    SosRecord(
                        o.optString("id"), o.optString("text"), o.optLong("createdAt"),
                        o.optString("status", "QUEUED"), o.optInt("attempts"), acked, o.optString("src", "NONE")
                    )
                )
            }
        }
    }

    /** Privacy control: removes saved SOS messages, event history, trusted peers and learned keys. */
    fun clearData() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("outbox_") || it.startsWith("key_") }
            .forEach { editor.remove(it) }
        editor.remove("events_json").remove("sos_records_json").remove("seen_ids").remove("trusted_peers")
        editor.commit()
        synchronized(seenIds) { seenIds.clear() }
        lastAttempt.clear()
        post {
            events.clear()
            sosRecords.clear()
        }
        addEvent("SYSTEM", "Saved data cleared", "Saved SOS messages, history, trusted peers and learned keys were removed.")
    }
}
