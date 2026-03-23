package com.dji.sdk.sample

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.TextureView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.model.MissionStepAisle
import com.dji.sdk.sample.vision.YoloDetector
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.flightcontroller.FlightControllerState
import dji.common.flightcontroller.virtualstick.FlightControlData
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem
import dji.common.flightcontroller.virtualstick.RollPitchControlMode
import dji.common.flightcontroller.virtualstick.VerticalControlMode
import dji.common.flightcontroller.virtualstick.YawControlMode
import dji.sdk.codec.DJICodecManager
import dji.sdk.flightcontroller.FlightController
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKManager
import kotlin.math.abs
import kotlin.math.sign

// ---------- Google Drive ----------
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File

// ---------- Virtual Stick speeds / timing ----------
private const val PITCH_ROLL_SPEED_MPS = 0.37f
private const val THROTTLE_SPEED_MPS  = 0.50f
private const val YAW_SPEED_DPS       = 30f
private const val STEP_INTERVAL_MS    = 100L
private const val HOVER_NOOP_MS       = 2000L   // dwell time for each pallet hover

// ---------- Safety ----------
private const val RACK_STANDOFF_M     = 1.2f

class DroneFeedActivity : AppCompatActivity() {

    // UI / video
    private lateinit var videoFeedView: TextureView
    private var codecManager: DJICodecManager? = null
    private val handler = Handler(Looper.getMainLooper())

    // DJI
    private var flightController: FlightController? = null

    // Mission state
    private var missionActive = false
    private var waitForTakeoffAlt = false      // NEW: let the single callback start the mission
    private var returnHomeActive = false
    private var isPaused = false
    private val reverseStack = mutableListOf<MissionStepAisle>()
    private var currentMission: MutableList<MissionStepAisle> = mutableListOf()
    private var currentStepIndex = 0
    private var stepStartTime = 0L
    private var stepDuration = 0L
    private var streamingRunnable: Runnable? = null
    private var landingActive = false

    // Intent/state
    private var aisleLetter: String = "A"
    private var rowIndex: Int = 1

    // Vision / OA
    private var detector: YoloDetector? = null
    private var modelLoaded = false
    private var analyzerStarted = false
    private var isBackingUp = false
    private var lastAvoidTime = 0L
    private val AVOID_COOLDOWN_MS = 10_000L
    private val THREAT_CLASSES = setOf("person", "chair", "couch", "bed", "potted plant", "tv", "refrigerator")

    // Google Drive
    private var driveService: Drive? = null
    private val aisleFolderCache = mutableMapOf<String, String>() // "AA" -> folderId
    private val RC_SIGN_IN = 43

    // Capture control
    private var lastCapturedStepIndex = -1  // ensure one photo per hover step

    // ---- Lifecycle ----
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drone_feed)
        CalibrationStore.loadIntoModel(applicationContext)
        intent.getStringExtra("aisle")?.let { aisleLetter = it }
        rowIndex = intent.getIntExtra("row", 1)
        CalibrationStore.loadIntoModel(this)
        if (!checkPermissions()) return

        videoFeedView = findViewById(R.id.video_feed)
        setupVideoFeed()
        setupFlightController()

        // YOLO (optional)
        try {
            detector = YoloDetector(this)
            modelLoaded = true
        } catch (_: Exception) {
            modelLoaded = false
            Toast.makeText(this, "YOLO disabled", Toast.LENGTH_LONG).show()
        }
        if (videoFeedView.isAvailable && modelLoaded && !analyzerStarted) {
            analyzerStarted = true
            startObstacleAnalyzerLoop()
        }

        ensureGoogleDriveReady()

        // Buttons
        findViewById<Button>(R.id.btnStart).setOnClickListener { startMission() }
        findViewById<Button>(R.id.btnHover).setOnClickListener { togglePause() }
        findViewById<Button>(R.id.btnReturnHome).setOnClickListener { returnHome() }
        findViewById<Button>(R.id.btnEmergencyStop).setOnClickListener { emergencyLand() }
        findViewById<Button>(R.id.btnBackUp).setOnClickListener {
            manualBackUpOneFoot()
        }
        findViewById<Button>(R.id.btnForwardOneFoot)?.setOnClickListener {
            nudgeForwardOneFoot()
        }


        // No manual photo button for aisle—photos are auto at each pallet hover

        // Tap-to-focus
        videoFeedView.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                v.performClick()
                triggerFocus(e.x, e.y)
                true
            } else false
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("aisle")?.let { aisleLetter = it }
        rowIndex = intent?.getIntExtra("row", 1) ?: 1
        resetMissionState()
    }

    override fun onBackPressed() {
        if (missionActive || returnHomeActive) {
            stopCurrentMission()
            returnHome()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        streamingRunnable?.let { handler.removeCallbacks(it) }
        // Keep callback during lifecycle, clear only when destroying:
        flightController?.setStateCallback(null)
        handler.removeCallbacksAndMessages(null)
        codecManager?.cleanSurface()
        super.onDestroy()
    }

    // ---- Permissions ----
    private fun checkPermissions(): Boolean {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(requestCode, perms, grants)
        if (requestCode == 101 && grants.all { it == PackageManager.PERMISSION_GRANTED }) {
            setupVideoFeed()
            setupFlightController()
            ensureGoogleDriveReady()
        } else {
            Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ---- DJI setup ----
    private fun setupFlightController() {
        val product = DJISDKManager.getInstance().product as? Aircraft ?: return
        flightController = product.flightController?.apply {
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

            setVirtualStickAdvancedModeEnabled(true)
            setVirtualStickModeEnabled(true, null)

            // Single always-on callback: ground-guard + takeoff threshold + mission start
            setStateCallback { state: FlightControllerState ->
                // Gentle ground/obstacle stop below
                val h = state.ultrasonicHeightInMeters
                if (h > 0 && h <= 1.2f) {
                    sendVirtualStickFlightControlData(FlightControlData(0f, 0f, 0f, 0f), null)
                }

                // Start the mission once airborne
                if (waitForTakeoffAlt && state.aircraftLocation.altitude >= 1.0) {
                    waitForTakeoffAlt = false
                    runOnUiThread {
                        Toast.makeText(this@DroneFeedActivity, "Starting full aisle mission…", Toast.LENGTH_SHORT).show()
                        currentMission = MissionStepAisle.missionForFullAisle(
                            letter = aisleLetter,
                            row = rowIndex,
                            positions = 28,
                            launchAGL = 0f
                        ).toMutableList()
                        currentStepIndex = 0
                        lastCapturedStepIndex = -1
                        isPaused = false
                        executeNextStep()
                    }
                }
            }
        }
    }

    private fun setupVideoFeed() {
        videoFeedView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                codecManager = DJICodecManager(this@DroneFeedActivity, st, w, h)
                if (modelLoaded && !analyzerStarted) {
                    analyzerStarted = true
                    startObstacleAnalyzerLoop()
                }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                codecManager?.cleanSurface(); return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    // ---- Mission control ----
    private fun startMission() {
        CalibrationStore.loadIntoModel(applicationContext)
        if (missionActive || returnHomeActive) {
            Toast.makeText(this, "Already flying", Toast.LENGTH_SHORT).show(); return
        }

        intent.getStringExtra("aisle")?.let { aisleLetter = it }
        rowIndex = intent.getIntExtra("row", 1)

        flightController?.startTakeoff { error ->
            if (error != null) {
                Toast.makeText(this, "Takeoff failed: ${error.description}", Toast.LENGTH_LONG).show()
                missionActive = false
            } else {
                missionActive = true
                waitForTakeoffAlt = true    // let the state callback kick off the mission
            }
        }
    }
    /** Focus at the center of the preview, wait a bit, then capture & upload. */
    /** Focus at the center of the preview, wait a bit, then capture & upload. */
    private fun focusThenCaptureAndUploadAuto() {
        val centerX = (videoFeedView.width / 2f).coerceAtLeast(1f)
        val centerY = (videoFeedView.height / 2f).coerceAtLeast(1f)

        val cam = (DJISDKManager.getInstance().product as? Aircraft)?.camera

        // Try to switch to AUTO focus if supported (AFS isn't in MSDK 4.18).
        try {
            cam?.setFocusMode(SettingsDefinitions.FocusMode.AUTO, null)
        } catch (_: Exception) {
            // Some payloads don't allow changing focus mode—ignore.
        }

        // Tap-to-focus at the preview center (same call you already use on touch)
        triggerFocus(centerX, centerY)

        // Give AF a moment to settle, then shoot + upload
        handler.postDelayed({
            captureAndUploadAuto()
        }, 650L) // adjust 500–1000ms if needed
    }


    private fun executeNextStep() {
        if ((!missionActive && !returnHomeActive) || isPaused || currentStepIndex >= currentMission.size) {

            // 1) Mission finished → hover (do NOT land)
            if (missionActive && currentStepIndex >= currentMission.size) {
                missionActive = false
                hover()
                Toast.makeText(this, "Mission complete — hovering.", Toast.LENGTH_SHORT).show()
                return
            }

            // 2) Return-home finished → hover (do NOT land)
            if (returnHomeActive && currentStepIndex >= currentMission.size) {
                returnHomeActive = false
                reverseStack.clear()
                hover()
                Toast.makeText(this, "Return complete — hovering.", Toast.LENGTH_SHORT).show()
                return
            }

            // If paused or not active, just stop here
            return
        }

        val step = currentMission[currentStepIndex]
        if (!returnHomeActive) reverseStack.add(step)

        val isYaw = step.yaw != 0f
        val isPit = step.pitch != 0f
        val isRol = step.roll != 0f
        val isThr = step.throttle != 0f
        val isNoopHover = !isYaw && !isPit && !isRol && !isThr

        // Duration (hover steps get fixed dwell time)
        fun ms(d: Float) = (d * 1000).toLong()
        val durYaw = if (isYaw) ms(kotlin.math.abs(step.yaw) / YAW_SPEED_DPS) else 0L
        val durPit = if (isPit) ms(kotlin.math.abs(step.pitch) / PITCH_ROLL_SPEED_MPS) else 0L
        val durRol = if (isRol) ms(kotlin.math.abs(step.roll) / PITCH_ROLL_SPEED_MPS) else 0L
        val durThr = if (isThr) ms(kotlin.math.abs(step.throttle) / THROTTLE_SPEED_MPS) else 0L
        stepDuration = if (isNoopHover) HOVER_NOOP_MS else maxOf(durYaw, durPit, durRol, durThr).coerceAtLeast(1L)

        stepStartTime = System.currentTimeMillis()

        // If this step is a hover pause between pallets, capture once at the start.
        if (isNoopHover && lastCapturedStepIndex != currentStepIndex) {
            lastCapturedStepIndex = currentStepIndex
            focusThenCaptureAndUploadAuto()   // async
        }

        streamingRunnable = object : Runnable {
            override fun run() {
                if (isPaused) return

                val pitchSpeed    = if (isPit) PITCH_ROLL_SPEED_MPS * sign(step.pitch) else 0f
                val rollSpeed     = if (isRol) PITCH_ROLL_SPEED_MPS * sign(step.roll)  else 0f
                val yawSpeed      = if (isYaw) YAW_SPEED_DPS       * sign(step.yaw)    else 0f
                val throttleSpeed = if (isThr) THROTTLE_SPEED_MPS  * sign(step.throttle) else 0f

                flightController?.sendVirtualStickFlightControlData(
                    FlightControlData(pitchSpeed, rollSpeed, yawSpeed, throttleSpeed), null
                )

                val elapsed = System.currentTimeMillis() - stepStartTime
                if (elapsed < stepDuration) {
                    handler.postDelayed(this, STEP_INTERVAL_MS)
                } else {
                    hover()
                    // Insert a tiny standoff if next step is a strafe after yaw
                    if (isYaw) insertRackStandoffIfNeeded(step)
                    currentStepIndex++
                    executeNextStep()
                }
            }
        }
        handler.post(streamingRunnable!!)
    }

    /** Insert a small back-off on ROLL before the first strafe after a ~90° yaw. */
    private fun insertRackStandoffIfNeeded(justFinished: MissionStepAisle) {
        if (abs(justFinished.yaw) >= 85f && currentStepIndex < currentMission.size) {
            val next = currentMission[currentStepIndex]
            val nextIsLateralStrafe = next.pitch != 0f && next.roll == 0f && next.throttle == 0f && next.yaw == 0f
            if (nextIsLateralStrafe) {
                currentMission.add(
                    currentStepIndex,
                    MissionStepAisle(pitch = 0f, roll = -RACK_STANDOFF_M, throttle = 0f, yaw = 0f)
                )
            }
        }
    }

    private fun hover() {
        flightController?.sendVirtualStickFlightControlData(FlightControlData(0f, 0f, 0f, 0f), null)
        streamingRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun togglePause() {
        if (!missionActive && !returnHomeActive) return
        isPaused = !isPaused
        if (isPaused) {
            hover(); Toast.makeText(this, "Paused", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Resumed", Toast.LENGTH_SHORT).show()
            executeNextStep()
        }
    }

    private fun stopCurrentMission() {
        hover()
        streamingRunnable?.let { handler.removeCallbacks(it) }
        resetMissionState()
        // DO NOT clear state callback here; we keep ground guard active.
    }
    // Call this when Back Up is pressed.
    private fun manualBackUpOneFoot() {
        // If a backup is already running, ignore.
        if (isBackingUp) return

        // Remember if we should resume mission afterwards
        val shouldResume = missionActive || returnHomeActive

        // Stop whatever was being streamed and hover
        streamingRunnable?.let { handler.removeCallbacks(it) }
        hover()

        isPaused = true
        isBackingUp = true
        Toast.makeText(this, "Backing up", Toast.LENGTH_SHORT).show()

        // Distance & speed
        val backupDistanceMeters = 0.3048f  // 1 ft
        val speedMps = 0.30f                // gentle & safe
        val durationMs = ((backupDistanceMeters / speedMps) * 1000).toLong()

        val start = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed < durationMs) {
                    // NOTE: If your "forward" axis is mapped to ROLL, change to (0f, -speedMps, 0f, 0f)
                    flightController?.sendVirtualStickFlightControlData(
                        FlightControlData(0f, -speedMps, 0f, 0f), null
                    )
                    handler.postDelayed(this, 100L)
                } else {
                    hover()
                    isBackingUp = false
                    isPaused = false
                    if (shouldResume) {
                        // continue the current step scheduling exactly where we left off
                        executeNextStep()
                    }
                }
            }
        }
        handler.post(runnable)
    }
    /** Move forward ~1 ft (0.3048 m), then resume the current mission/return path. */
    private fun nudgeForwardOneFoot() {
        // prevent overlapping nudges
        if (isBackingUp) return

        val shouldResume = missionActive || returnHomeActive

        // stop any streaming command loop and hover first
        streamingRunnable?.let { handler.removeCallbacks(it) }
        hover()

        isPaused = true
        isBackingUp = true  // reuse this guard to pause OA loop too
        Toast.makeText(this, "Moving forward 1 ft…", Toast.LENGTH_SHORT).show()

        val distanceM = 0.3048f      // 1 ft
        val speedMps  = 0.30f        // gentle
        val durationMs = ((distanceM / speedMps) * 1000).toLong()

        val start = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed < durationMs) {
                    // Your project uses ROLL for "forward". If yours uses PITCH, see the alt line below.
                    flightController?.sendVirtualStickFlightControlData(
                        FlightControlData(
                            /* pitch    = */ 0f,
                            /* roll     = */ +speedMps,   // <- forward via ROLL
                            /* yaw      = */ 0f,
                            /* throttle = */ 0f
                        ), null
                    )
                    // ALTERNATIVE if your forward axis is PITCH (DJI default):
                    // flightController?.sendVirtualStickFlightControlData(
                    //     FlightControlData(+speedMps, 0f, 0f, 0f), null
                    // )

                    handler.postDelayed(this, 100L)
                } else {
                    hover()
                    isBackingUp = false
                    isPaused = false
                    if (shouldResume) {
                        executeNextStep()
                    }
                }
            }
        }
        handler.post(runnable)
    }

    private fun resetMissionState() {
        missionActive = false
        returnHomeActive = false
        waitForTakeoffAlt = false
        isPaused = false
        currentMission.clear()
        currentStepIndex = 0
        reverseStack.clear()
        lastCapturedStepIndex = -1
    }

    private fun returnHome() {
        if (returnHomeActive) return
        if (reverseStack.isEmpty()) {
            Toast.makeText(this, "No path to return from.", Toast.LENGTH_SHORT).show()
            return
        }

        missionActive = false
        returnHomeActive = true
        isPaused = false
        hover()

        val backMission = mutableListOf<MissionStepAisle>()
        reverseStack.asReversed().forEach { step ->
            if (step.pitch != 0f || step.roll != 0f || step.throttle != 0f) {
                backMission += MissionStepAisle(-step.pitch, -step.roll, -step.throttle, 0f)
            }
            if (step.yaw != 0f) backMission += MissionStepAisle(0f, 0f, 0f, -step.yaw)
        }

        currentMission = backMission.toMutableList()
        currentStepIndex = 0
        Toast.makeText(this, "Returning home by retracing path…", Toast.LENGTH_SHORT).show()
        executeNextStep()
    }

    private fun emergencyLand() {
        flightController?.startLanding(null)
    }

    private fun landNow(reason: String) {
        if (landingActive) return
        landingActive = true
        missionActive = false
        returnHomeActive = false
        waitForTakeoffAlt = false
        isPaused = false
        streamingRunnable?.let { handler.removeCallbacks(it) }
        hover()

        runOnUiThread { Toast.makeText(this, "Landing: $reason", Toast.LENGTH_SHORT).show() }

        flightController?.startLanding { err: DJIError? ->
            runOnUiThread {
                if (err != null) {
                    Toast.makeText(this, "Landing failed: ${err.description}", Toast.LENGTH_LONG).show()
                    landingActive = false
                } else {
                    Toast.makeText(this, "Landing initiated", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---- Auto photo on hover ----
    private fun captureAndUploadAuto() {
        // Trigger a real camera shot (optional)
        (DJISDKManager.getInstance().product as? Aircraft)?.camera?.let { cam ->
            cam.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, null)
            cam.setPhotoFileFormat(SettingsDefinitions.PhotoFileFormat.JPEG, null)
            cam.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.SINGLE, null)
            cam.startShootPhoto(null)
        }

        // Also snapshot the preview and upload (fast & reliable)
        val bmp = videoFeedView.bitmap ?: return
        saveToGallery(bmp)
        uploadPreviewToDrive(bmp)
    }

    // ---- Camera helpers ----
    private fun triggerFocus(x: Float, y: Float) {
        (DJISDKManager.getInstance().product as? Aircraft)?.camera?.setFocusTarget(PointF(x, y), null)
    }

    private fun saveToGallery(bmp: Bitmap) {
        val name = "aisle_${aisleLetter}_${System.currentTimeMillis()}.jpg"
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/DroneApp")
        }
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)?.let { uri ->
            contentResolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }
        }
    }

    // ---- YOLO obstacle analyzer ----
    private fun startObstacleAnalyzerLoop() {
        if (!modelLoaded) return
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!landingActive) analyzeFrameForObstacles()
                handler.postDelayed(this, 1200)
            }
        }, 1200)
    }

    private fun analyzeFrameForObstacles() {
        if (isBackingUp) return
        if (System.currentTimeMillis() - lastAvoidTime < AVOID_COOLDOWN_MS) return

        val bitmap = videoFeedView.bitmap ?: return
        if (bitmap.width == 0 || bitmap.height == 0) return

        val det = detector ?: return
        val detections = try { det.detect(bitmap) } catch (_: Exception) { return }

        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val hit = detections.any { d ->
            val label = det.getLabel(d.classId)
            val threat = label in THREAT_CLASSES
            val cy = d.box.centerY()
            val inCenterBand = cy in (0.35f * h)..(0.65f * h)
            val tooClose = d.box.width() > 0.42f * w || d.box.height() > 0.42f * h
            threat && inCenterBand && tooClose
        }
        if (!hit) return

        hover()
        isPaused = true
        isBackingUp = true
        Toast.makeText(this, "🚧 Obstacle ahead — backing up 1 ft", Toast.LENGTH_SHORT).show()

        val backupDistanceMeters = 0.3f
        val speedMps = 0.30f
        val durationMs = ((backupDistanceMeters / speedMps) * 1000).toLong()

        val start = System.currentTimeMillis()
        val backupRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed < durationMs) {
                    flightController?.sendVirtualStickFlightControlData(
                        FlightControlData(-speedMps, 0f, 0f, 0f), null
                    )
                    handler.postDelayed(this, 100L)
                } else {
                    hover()
                    lastAvoidTime = System.currentTimeMillis()
                    isBackingUp = false
                    isPaused = false
                    executeNextStep()
                }
            }
        }
        handler.post(backupRunnable)
    }

    // ---- Google Drive: sign-in + upload ----
    private fun ensureGoogleDriveReady() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

        val client = GoogleSignIn.getClient(this, gso)
        val acct = GoogleSignIn.getLastSignedInAccount(this)

        if (acct == null) {
            startActivityForResult(client.signInIntent, RC_SIGN_IN)
            return
        }

        // If cached account lacks Drive scope, request it explicitly
        if (!GoogleSignIn.hasPermissions(acct, Scope(DriveScopes.DRIVE_FILE))) {
            GoogleSignIn.requestPermissions(this, RC_SIGN_IN, acct, Scope(DriveScopes.DRIVE_FILE))
            return
        }

        buildDriveService(acct)  // will also ping
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            runCatching { task.result }.onSuccess { acct ->
                buildDriveService(acct)
                Toast.makeText(this, "Google Drive ready", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "Drive sign-in failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildDriveService(acct: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            applicationContext, setOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = acct.account
        }

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("ASAP Freight Drone").build()

        // Quick sanity ping after building the service
        quickDrivePing()
    }

    private fun quickDrivePing() {
        val drive = driveService ?: return
        Thread {
            try {
                val result = drive.files().list()
                    .setPageSize(1)
                    .setFields("files(id,name)")
                    .execute()
                android.util.Log.i("DrivePing", "OK: ${result.files?.firstOrNull()?.name ?: "no files"}")
            } catch (e: Exception) {
                android.util.Log.e("DrivePing", "Ping failed", e)
            }
        }.start()
    }

    private fun ensureParentFolder(drive: Drive, parentName: String = "ASAP Drone Pics"): String {
        val q = "mimeType='application/vnd.google-apps.folder' and name='${parentName.replace("'", "\\'")}' and trashed=false"
        val found = drive.files().list().setQ(q).setSpaces("drive").setFields("files(id)").execute()
        if (!found.files.isNullOrEmpty()) return found.files[0].id

        val meta = File().apply {
            name = parentName
            mimeType = "application/vnd.google-apps.folder"
        }
        return drive.files().create(meta).setFields("id").execute().id
    }

    private fun ensureChildFolder(drive: Drive, parentId: String, childName: String): String {
        val safe = childName.trim().uppercase()
        val q = "mimeType='application/vnd.google-apps.folder' and name='${safe.replace("'", "\\'")}' and '$parentId' in parents and trashed=false"
        val found = drive.files().list().setQ(q).setSpaces("drive").setFields("files(id)").execute()
        if (!found.files.isNullOrEmpty()) return found.files[0].id

        val meta = File().apply {
            name = safe
            mimeType = "application/vnd.google-apps.folder"
            parents = listOf(parentId)
        }
        return drive.files().create(meta).setFields("id").execute().id
    }

    private fun uploadJpegToDrive(drive: Drive, name: String, bytes: ByteArray, folderId: String): String {
        val fileMeta = File().apply {
            this.name = name
            parents = listOf(folderId)
        }
        val media = ByteArrayContent("image/jpeg", bytes)
        val created = drive.files().create(fileMeta, media)
            .setFields("id, webViewLink")
            .execute()
        return created.id
    }

    private fun uploadPreviewToDrive(bmp: Bitmap) {
        ensureGoogleDriveReady()
        val drive = driveService ?: run {
            Toast.makeText(this, "Drive not ready (sign in).", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            try {
                val parentId = ensureParentFolder(drive, "ASAP Drone Pics")
                val aisleKey = aisleLetter.trim().uppercase()
                val folderId = aisleFolderCache.getOrPut(aisleKey) {
                    ensureChildFolder(drive, parentId, aisleKey)
                }

                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                val fileName = "aisle_${aisleKey}_${System.currentTimeMillis()}.jpg"

                val id = uploadJpegToDrive(drive, fileName, baos.toByteArray(), folderId)
                runOnUiThread { Toast.makeText(this, "Uploaded (id: $id)", Toast.LENGTH_SHORT).show() }

            } catch (g: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
                val msg = "Drive ${g.statusCode}: ${g.details?.message ?: g.message}"
                android.util.Log.e("DriveUpload", msg, g)
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                android.util.Log.e("DriveUpload", "Upload failed", e)
                runOnUiThread { Toast.makeText(this, "Drive upload failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
