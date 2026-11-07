package com.novashare.app

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.FileInputStream
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val serviceId = "com.novashare.app"
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val myName: String = Build.MODEL ?: "NovaShare"

    private lateinit var client: ConnectionsClient
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var devices: LinearLayout

    private var picked: List<Uri> = emptyList()
    private var receiving = false
    private var pendingAction: (() -> Unit)? = null
    private val names = HashMap<Long, String>()
    private val incoming = HashMap<Long, Payload>()
    private val sentIds = HashSet<Long>()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r.values.all { it }) pendingAction?.invoke() else say("Saari permissions allow karein")
            pendingAction = null
        }

    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list ->
            if (list.isNotEmpty()) {
                picked = list
                say("${list.size} file chuni. Dusre phone par Receive dabayein.")
                withPerms { startDiscovery() }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = Nearby.getConnectionsClient(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }
        val title = TextView(this).apply {
            text = "NovaShare"
            textSize = 30f
            setTextColor(Color.parseColor("#0F3D3E"))
        }
        status = TextView(this).apply {
            text = "Send ya Receive chunein"
            textSize = 15f
            setPadding(0, 24, 0, 24)
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val sendBtn = Button(this).apply {
            text = "Send files"
            setOnClickListener {
                resetAll()
                receiving = false
                pickLauncher.launch(arrayOf("*/*"))
            }
        }
        val recvBtn = Button(this).apply {
            text = "Receive"
            setOnClickListener {
                resetAll()
                receiving = true
                withPerms { startAdvertising() }
            }
        }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(title)
        root.addView(status)
        root.addView(bar)
        root.addView(sendBtn)
        root.addView(recvBtn)
        root.addView(devices)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        client.stopAllEndpoints()
        client.stopAdvertising()
        client.stopDiscovery()
        super.onDestroy()
    }

    /* ---------- helpers ---------- */

    private fun say(s: String) = runOnUiThread { status.text = s }

    private fun resetAll() {
        client.stopAllEndpoints()
        client.stopAdvertising()
        client.stopDiscovery()
        devices.removeAllViews()
        names.clear()
        incoming.clear()
        sentIds.clear()
        bar.progress = 0
    }

    private fun need(): Array<String> {
        val l = ArrayList<String>()
        val s = Build.VERSION.SDK_INT
        if (s >= 33) l.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (s >= 31) {
            l.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
            l.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (s <= 32) l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return l.toTypedArray()
    }

    private fun withPerms(action: () -> Unit) {
        val missing = need().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            pendingAction = action
            permLauncher.launch(missing.toTypedArray())
        }
    }

    /* ---------- connect ---------- */

    private fun startAdvertising() {
        say("Receive mode: dusre phone se Send dabwayein...")
        client.startAdvertising(
            myName, serviceId, connCb,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener { say("Error: ${it.message}") }
    }

    private fun startDiscovery() {
        say("Phone dhoondh raha hai... (Location/GPS on rakhein)")
        client.startDiscovery(
            serviceId, discCb,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnFailureListener { say("Error: ${it.message}") }
    }

    private val discCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            runOnUiThread {
                if (devices.findViewWithTag<Button>(id) != null) return@runOnUiThread
                val b = Button(this@MainActivity).apply {
                    tag = id
                    text = info.endpointName
                    setOnClickListener {
                        say("Connect ho raha hai: ${info.endpointName}")
                        client.requestConnection(myName, id, connCb)
                            .addOnFailureListener { e -> say("Error: ${e.message}") }
                    }
                }
                devices.addView(b)
            }
        }

        override fun onEndpointLost(id: String) {
            runOnUiThread { devices.findViewWithTag<Button>(id)?.let { devices.removeView(it) } }
        }
    }

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            if (receiving) {
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Connection")
                        .setMessage("${info.endpointName} se files lena manzoor hai?")
                        .setPositiveButton("Haan") { _, _ -> client.acceptConnection(id, payloadCb) }
                        .setNegativeButton("Nahi") { _, _ -> client.rejectConnection(id) }
                        .setCancelable(false)
                        .show()
                }
            } else {
                client.acceptConnection(id, payloadCb)
            }
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                client.stopDiscovery()
                client.stopAdvertising()
                runOnUiThread { devices.removeAllViews() }
                if (receiving) say("Connected. Files aa rahi hain...") else sendAll(id)
            } else {
                say("Connection nahi hua. Dobara try karein.")
            }
        }

        override fun onDisconnected(id: String) {
            say("Connection khatam")
        }
    }

    /* ---------- send ---------- */

    private fun nameOf(u: Uri): String {
        var n: String? = null
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) n = c.getString(i)
        }
        return n ?: "file_${System.currentTimeMillis()}"
    }

    private fun sendAll(id: String) {
        say("Files bhej raha hai...")
        for (u in picked) {
            val pfd = contentResolver.openFileDescriptor(u, "r") ?: continue
            val filePayload = Payload.fromFile(pfd)
            sentIds.add(filePayload.id)
            client.sendPayload(id, Payload.fromBytes("${filePayload.id}:${nameOf(u)}".toByteArray()))
            client.sendPayload(id, filePayload)
        }
    }

    /* ---------- receive ---------- */

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            if (p.type == Payload.Type.BYTES) {
                val s = String(p.asBytes() ?: return)
                val i = s.indexOf(':')
                if (i > 0) s.substring(0, i).toLongOrNull()?.let { names[it] = s.substring(i + 1) }
            } else if (p.type == Payload.Type.FILE) {
                incoming[p.id] = p
            }
        }

        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {
            if (u.totalBytes > 0) {
                val pct = (100 * u.bytesTransferred / u.totalBytes).toInt()
                runOnUiThread { bar.progress = pct }
            }
            if (u.status == PayloadTransferUpdate.Status.SUCCESS) {
                val inc = incoming.remove(u.payloadId)
                if (inc != null) saveFile(inc)
                if (sentIds.remove(u.payloadId) && sentIds.isEmpty()) say("Sab files bhej di gayin")
            }
        }
    }

    private fun saveFile(p: Payload) {
        val name = names.remove(p.id) ?: "file_${p.id}"
        thread {
            try {
                val f = p.asFile() ?: return@thread
                val uri = f.asUri()
                val input = if (uri != null) contentResolver.openInputStream(uri)
                else FileInputStream(f.asJavaFile()!!)
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/NovaShare")
                }
                val out = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openOutputStream(out)!!.use { o -> input!!.use { it.copyTo(o) } }
                try {
                    if (uri != null) contentResolver.delete(uri, null, null) else f.asJavaFile()?.delete()
                } catch (e: Exception) { /* ignore */ }
                say("Save ho gayi: $name (Download/NovaShare)")
            } catch (e: Exception) {
                say("Save error: ${e.message}")
            }
        }
    }
}
