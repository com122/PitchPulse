package com.pitchpulse.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssMeasurementsEvent
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pitchpulse.app.MainActivity
import com.pitchpulse.app.PitchPulseApp
import com.pitchpulse.app.R
import com.pitchpulse.app.data.model.KnownPoint
import com.pitchpulse.app.data.model.RefPlacement
import com.pitchpulse.app.data.model.ReferenceFix
import com.pitchpulse.app.data.model.ReferenceSessionMeta
import com.pitchpulse.app.domain.algorithm.PitchFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Foreground service (type "location") that turns the phone into a static GNSS reference station:
 * GPS_PROVIDER fixes at 1 Hz (no Google fused location), GnssStatus satellites per epoch and, when
 * the chipset supports them, raw measurements and navigation messages. Works with the screen off.
 */
class ReferenceStationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var locationManager: LocationManager

    // Written on the main thread at start, then only on the GNSS handler thread.
    @Volatile private var meta: ReferenceSessionMeta? = null
    @Volatile private var fixWriter: BufferedWriter? = null
    @Volatile private var rawLogger: GnssRawLogger? = null
    @Volatile private var latestSats: List<List<Int>> = emptyList()
    @Volatile private var satsUsed = 0
    @Volatile private var satsVisible = 0
    @Volatile private var meanCn0 = 0f
    private var fixCount = 0
    @Volatile private var lastFix: ReferenceFix? = null
    private var lastMetaWrite = 0L

    private val locationListener = LocationListener { loc -> onFix(loc) }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val list = ArrayList<List<Int>>(status.satelliteCount)
            var used = 0
            var cnSum = 0f
            for (i in 0 until status.satelliteCount) {
                val u = status.usedInFix(i)
                if (u) { used++; cnSum += status.getCn0DbHz(i) }
                list += listOf(status.getConstellationType(i), status.getSvid(i), Math.round(status.getCn0DbHz(i) * 10), if (u) 1 else 0)
            }
            latestSats = list
            satsUsed = used
            satsVisible = status.satelliteCount
            meanCn0 = if (used == 0) 0f else cnSum / used
            ReferenceStationController.update { it.copy(satsUsed = used, satsVisible = satsVisible, meanCn0 = meanCn0) }
        }
    }

    private val measurementCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val logger = rawLogger ?: openRawLogger() ?: return
            logger.onMeasurements(event)
            if (logger.measurementEpochs == 1) setMeta { it.copy(hasMeasurements = true) }
            ReferenceStationController.update { it.copy(measurementsSupported = true, rawEpochs = logger.measurementEpochs) }
        }

        // Deprecated in API 31 (GnssCapabilities replaces it) but the only signal on Android 8–10.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onStatusChanged(status: Int) {
            if (status == STATUS_NOT_SUPPORTED) ReferenceStationController.update { it.copy(measurementsSupported = false) }
        }
    }

    private val navCallback = object : GnssNavigationMessage.Callback() {
        override fun onGnssNavigationMessageReceived(event: GnssNavigationMessage) {
            val logger = rawLogger ?: openRawLogger() ?: return
            logger.onNavigationMessage(event)
            if (logger.navMessages == 1) setMeta { it.copy(hasNavMessages = true) }
            ReferenceStationController.update { it.copy(navMessagesSupported = true, navMessages = logger.navMessages) }
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onStatusChanged(status: Int) {
            if (status == STATUS_NOT_SUPPORTED) ReferenceStationController.update { it.copy(navMessagesSupported = false) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("pp-reference-gnss").also { it.start() }
        handler = Handler(thread.looper)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stop()
            ACTION_MARK_START -> mark(start = true)
            ACTION_MARK_END -> mark(start = false)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        // Must enter the foreground promptly after startForegroundService().
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("正在准备 GNSS…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        if (meta != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            fail("没有精确定位权限"); return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            fail("系统 GPS 已关闭，请在设置中打开位置信息"); return
        }
        val container = (application as PitchPulseApp).container
        val pitchId = intent.getStringExtra(EXTRA_PITCH_ID)
        val placement = runCatching { RefPlacement.valueOf(intent.getStringExtra(EXTRA_PLACEMENT)!!) }.getOrDefault(RefPlacement.UNKNOWN_POINT)
        val kp = intent.getStringExtra(EXTRA_KNOWN_POINT)?.let { runCatching { KnownPoint.valueOf(it) }.getOrNull() }
        val protocol = intent.getBooleanExtra(EXTRA_PROTOCOL, false)
        val pitch = pitchId?.let { runBlocking(Dispatchers.IO) { container.pitchRepository.getPitch(it) } }
        val knownLl = if (placement == RefPlacement.KNOWN_POINT && pitch != null && kp != null) {
            val f = PitchFrame(pitch)
            val en = f.normalizedToEn(kp.nx, kp.ny)
            f.latLng(en[0], en[1])
        } else null

        val now = System.currentTimeMillis()
        val id = "ref-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val store = container.referenceFileStore
        meta = ReferenceSessionMeta(
            id = id, pitchId = pitch?.id, pitchName = pitch?.name.orEmpty(),
            placement = if (knownLl != null) RefPlacement.KNOWN_POINT else RefPlacement.UNKNOWN_POINT,
            knownPoint = if (knownLl != null) kp else null,
            knownLat = knownLl?.get(0), knownLng = knownLl?.get(1),
            startedAt = now, device = "${Build.MANUFACTURER} ${Build.MODEL}", protocol = protocol,
        )
        fixWriter = store.openFixWriter(id)
        store.writeMetaBlocking(meta!!)
        fixCount = 0; lastFix = null
        ReferenceStationController.update {
            RecorderState(
                recording = true, sessionId = id, pitchName = pitch?.name.orEmpty(),
                placement = meta!!.placement, knownPoint = meta!!.knownPoint, startedAt = now, protocol = protocol,
            )
        }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, thread.looper)
            registerGnssCallbacks()
        } catch (e: SecurityException) {
            fail("定位权限被拒绝"); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val caps = locationManager.gnssCapabilities
            ReferenceStationController.update {
                it.copy(
                    measurementsSupported = it.measurementsSupported ?: caps.hasMeasurements(),
                    navMessagesSupported = it.navMessagesSupported ?: caps.hasNavigationMessages(),
                )
            }
        }
        updateNotification("等待首次定位…")
    }

    @Suppress("DEPRECATION")
    private fun registerGnssCallbacks() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val exec = Executor { handler.post(it) }
                locationManager.registerGnssStatusCallback(exec, statusCallback)
                locationManager.registerGnssMeasurementsCallback(exec, measurementCallback)
                locationManager.registerGnssNavigationMessageCallback(exec, navCallback)
            } else {
                locationManager.registerGnssStatusCallback(statusCallback, handler)
                locationManager.registerGnssMeasurementsCallback(measurementCallback, handler)
                locationManager.registerGnssNavigationMessageCallback(navCallback, handler)
            }
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            // Raw callbacks are optional; fixes still work.
        }
    }

    private fun openRawLogger(): GnssRawLogger? {
        val m = meta ?: return null
        return runCatching {
            GnssRawLogger((application as PitchPulseApp).container.referenceFileStore.rawFile(m.id), GnssRawLogger.header())
        }.getOrNull()?.also {
            rawLogger = it
            setMeta { mm -> mm.copy(rawLogFile = "${mm.id}.gnss.txt") }
        }
    }

    /** Runs on the GNSS handler thread. */
    private fun onFix(loc: Location) {
        val writer = fixWriter ?: return
        val fix = ReferenceFix(
            t = loc.time,
            er = loc.elapsedRealtimeNanos / 1_000_000,
            lat = loc.latitude,
            lng = loc.longitude,
            acc = if (loc.hasAccuracy()) loc.accuracy else 99f,
            alt = if (loc.hasAltitude()) loc.altitude else null,
            nu = satsUsed, nv = satsVisible, cn = meanCn0,
            ts = System.currentTimeMillis(),
            sv = latestSats.ifEmpty { null },
        )
        runCatching {
            writer.write((application as PitchPulseApp).container.referenceFileStore.encodeFix(fix))
            writer.newLine()
        }
        rawLogger?.onLocation(loc)
        fixCount++
        lastFix = fix
        if (fixCount % 10 == 0) { runCatching { writer.flush() }; rawLogger?.flush() }
        setMeta(writeNow = SystemClock.elapsedRealtime() - lastMetaWrite > 30_000) {
            it.copy(firstFixAt = it.firstFixAt ?: fix.t, lastFixAt = fix.t, fixCount = fixCount)
        }
        ReferenceStationController.update { it.copy(fixCount = fixCount, lastFix = fix) }
        if (fixCount % 5 == 1) updateNotification("已记录 $fixCount 个定位 · 精度 ${"%.1f".format(fix.acc)} m · 卫星 $satsUsed/$satsVisible")
    }

    /** GNSS time "now", extrapolated from the latest fix. */
    private fun gnssNow(): Long? = lastFix?.let { it.t + (SystemClock.elapsedRealtime() - it.er) }

    private fun mark(start: Boolean) {
        handler.post {
            val t = gnssNow() ?: run {
                ReferenceStationController.update { it.copy(error = "还没有定位，无法打点") }
                return@post
            }
            setMeta(writeNow = true) { if (start) it.copy(protocol = true, protocolRouteStart = t) else it.copy(protocol = true, protocolRouteEnd = t) }
            ReferenceStationController.update { if (start) it.copy(protocol = true, routeStart = t) else it.copy(protocol = true, routeEnd = t) }
        }
    }

    private fun setMeta(writeNow: Boolean = true, f: (ReferenceSessionMeta) -> ReferenceSessionMeta) {
        val m = meta ?: return
        val next = f(m)
        meta = next
        if (writeNow) {
            runCatching { (application as PitchPulseApp).container.referenceFileStore.writeMetaBlocking(next) }
            lastMetaWrite = SystemClock.elapsedRealtime()
        }
    }

    private fun stop() {
        handler.post {
            finish()
            scope.launch {
                (application as PitchPulseApp).container.referenceRepository.refresh()
                ServiceCompat.stopForeground(this@ReferenceStationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Unregisters callbacks and closes files. Safe to call twice. Runs on the handler thread. */
    private fun finish() {
        if (meta == null) return
        runCatching { locationManager.removeUpdates(locationListener) }
        runCatching { locationManager.unregisterGnssStatusCallback(statusCallback) }
        runCatching { locationManager.unregisterGnssMeasurementsCallback(measurementCallback) }
        runCatching { locationManager.unregisterGnssNavigationMessageCallback(navCallback) }
        runCatching { fixWriter?.flush(); fixWriter?.close() }
        fixWriter = null
        rawLogger?.close()
        rawLogger = null
        setMeta(writeNow = true) { it.copy(stoppedAt = System.currentTimeMillis(), fixCount = fixCount) }
        meta = null
        ReferenceStationController.update { it.copy(recording = false) }
    }

    private fun fail(message: String) {
        ReferenceStationController.update { it.copy(recording = false, error = message) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (meta != null) {
            val done = java.util.concurrent.CountDownLatch(1)
            handler.post { finish(); done.countDown() }
            done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        }
        thread.quitSafely()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "参考站录制", NotificationManager.IMPORTANCE_LOW).apply {
                description = "手机作为场边 GNSS 参考站时显示"
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReferenceStationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PitchPulse 参考站录制中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, notification(text)) }
    }

    companion object {
        const val ACTION_START = "com.pitchpulse.app.reference.START"
        const val ACTION_STOP = "com.pitchpulse.app.reference.STOP"
        const val ACTION_MARK_START = "com.pitchpulse.app.reference.MARK_START"
        const val ACTION_MARK_END = "com.pitchpulse.app.reference.MARK_END"
        const val EXTRA_PITCH_ID = "pitchId"
        const val EXTRA_PLACEMENT = "placement"
        const val EXTRA_KNOWN_POINT = "knownPoint"
        const val EXTRA_PROTOCOL = "protocol"
        private const val CHANNEL_ID = "reference_station"
        private const val NOTIFICATION_ID = 42
    }
}
