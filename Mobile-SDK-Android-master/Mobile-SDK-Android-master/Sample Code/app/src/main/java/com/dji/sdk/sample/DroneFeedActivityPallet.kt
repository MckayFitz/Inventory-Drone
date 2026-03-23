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
import android.view.TextureView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.model.MissionStepPallet
import com.dji.sdk.sample.model.MissionStepPallet.Companion.missionForPallet
import com.dji.sdk.sample.model.MissionStepPallet.Companion.missionForPallets
import dji.common.camera.SettingsDefinitions
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

// -------- Speeds / timing --------
private const val PITCH_ROLL_SPEED_MPS = 0.67f
private const val THROTTLE_SPEED_MPS   = 0.50f
private const val YAW_SPEED_DPS        = 30f
private const val STEP_INTERVAL_MS     = 100L
private const val HOVER_NOOP_MS        = 2000L   // dwell at pallet for focus+shoot
private const val FOCUS_SETTLE_MS      = 450L    // wait after focus tap

// -------- Safety --------
private const val ULTRASONIC_STOP_M    = 1.2f

class DroneFeedActivityPallet : AppCompatActivity() {

    companion object {
        private const val EXTRA_AISLE   = "aisle"
        private const val EXTRA_ROW     = "row"
        private const val EXTRA_PALLET  = "pallet"     // single
        const val EXTRA_PALLETS         = "pallets"    // ArrayList<Int>
        private const val RC_PERMS      = 101
    }

    // UI / video
    private lateinit var videoFeedView: TextureView
    private var codecManager: DJICodecManager? = null
    private val handler = Handler(Looper.getMainLooper())

    // DJI
    private var flightController: FlightController? = null

    // Mission state
    private var missionActive = false
    private var waitForTakeoffAlt = false
    private var returnHomeActive = false
    private var isPaused = false

    private var currentMission: MutableList<MissionStepPallet> = mutableListOf()
    private var currentStepIndex = 0
    private var stepStartTime = 0L
    private var stepDuration = 0L
    private var streamingRunnable: Runnable? = null

    // Segment reverse path & photo gating
    private val reverseStackThisSegment = mutableListOf<MissionStepPallet>()
    private var didShootThisPallet = false // reset at each boundary
    private var driveService: Drive? = null
    private val aisleFolderCache = mutableMapOf<String, String>() // "AA" -> folderId
    private val RC_SIGN_IN = 43
    private var isBackingUp = false
    // Intent/state
    private var aisleLetter: String = "A"
    private var rowIndex: Int = 1
    private var palletIndex: Int = 1
    private var palletIndices: ArrayList<Int>? = null
    private var finalFacePallets: Boolean = true
    private var extraForwardM: Float = 0f
    private val isMulti get() = (palletIndices?.isNotEmpty() == true)

    // ---- Lifecycle ----
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drone_feed)

        pullIntent(intent)
        if (!checkPerms()) return
        CalibrationStore.loadIntoModel(this)
        videoFeedView = findViewById(R.id.video_feed)
        setupVideoFeed()
        setupFlightController()

        // Buttons
        findViewById<Button>(R.id.btnStart).setOnClickListener { startMission() }
        findViewById<Button>(R.id.btnHover).setOnClickListener { togglePause() }
        findViewById<Button>(R.id.btnReturnHome).setOnClickListener { startReturnPathForThisSegment() }
        findViewById<Button>(R.id.btnEmergencyStop).setOnClickListener { emergencyLand() }
        findViewById<Button>(R.id.btnTakePhoto).setOnClickListener { focusCenterThenShoot() }
        findViewById<Button>(R.id.btnBackUp).setOnClickListener {
            manualBackUpOneFoot()
        }
        findViewById<Button>(R.id.btnForwardOneFoot)?.setOnClickListener {
            nudgeForwardOneFoot()
        }

        // Tap-to-focus anywhere on preview
        videoFeedView.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                v.performClick()
                triggerFocus(e.x, e.y)
                true
            } else false
        }

        // If you use Drive upload, ensure sign-in is ready:
        // ensureGoogleDriveReady()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        pullIntent(intent)
        // DO NOT reset mission state here automatically; only when user stops mission.
    }

    override fun onDestroy() {
        streamingRunnable?.let { handler.removeCallbacks(it) }
        flightController?.setStateCallback(null)
        handler.removeCallbacksAndMessages(null)
        codecManager?.cleanSurface()
        super.onDestroy()
    }

    // ---- Intent extras ----
    private fun pullIntent(i: Intent?) {
        if (i == null) return
        aisleLetter = i.getStringExtra(EXTRA_AISLE) ?: aisleLetter
        rowIndex = i.getIntExtra(EXTRA_ROW, rowIndex)

        palletIndices = i.getIntegerArrayListExtra(EXTRA_PALLETS)
            ?.filter { it in 1..28 }?.distinct()?.let { ArrayList(it) }

        palletIndex = when {
            palletIndices?.isNotEmpty() == true -> palletIndices!!.first()
            i.hasExtra(EXTRA_PALLET) -> i.getIntExtra(EXTRA_PALLET, palletIndex)
            else -> palletIndex
        }.coerceIn(1, 28)

        finalFacePallets = i.getBooleanExtra("finalFacePallets", true)
        extraForwardM = i.getFloatExtra("extraForwardM", 0f)
    }

    // ---- Permissions ----
    private fun checkPerms(): Boolean {
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
        val need = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), RC_PERMS)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(code, p, g)
        if (code == RC_PERMS && g.all { it == PackageManager.PERMISSION_GRANTED }) {
            setupVideoFeed()
            setupFlightController()
            // ensureGoogleDriveReady()
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

            // Always-on state callback: ground guard + kick off after takeoff
            setStateCallback { state: FlightControllerState ->
                val h = state.ultrasonicHeightInMeters
                if (h > 0 && h <= ULTRASONIC_STOP_M) {
                    sendVirtualStickFlightControlData(FlightControlData(0f, 0f, 0f, 0f), null)
                }

                if (waitForTakeoffAlt && state.aircraftLocation.altitude >= 1.0) {
                    waitForTakeoffAlt = false
                    runOnUiThread {
                        buildMission()
                        startMissionExecution()
                    }
                }
            }
        }
    }

    private fun setupVideoFeed() {
        videoFeedView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                codecManager = DJICodecManager(this@DroneFeedActivityPallet, st, w, h)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true.also { codecManager?.cleanSurface() }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    // ---- Mission build ----
    private fun buildMission() {
        CalibrationStore.loadIntoModel(applicationContext)
        CalibrationStore.loadIntoModel(this)
        currentMission.clear()
        reverseStackThisSegment.clear()
        didShootThisPallet = false
        currentStepIndex = 0

        if (isMulti) {
            currentMission.addAll(
                missionForPallets(
                    letter = aisleLetter,
                    row = rowIndex,
                    pallets = palletIndices!!.toList(),
                    finalFacePallets = finalFacePallets,
                    extraForwardM = extraForwardM,
                    launchAGL = 0f
                )
            )
        } else {
            currentMission.addAll(
                missionForPallet(
                    letter = aisleLetter,
                    row = rowIndex,
                    palletIndex = palletIndex,
                    finalFacePallets = finalFacePallets,
                    extraForwardM = extraForwardM,
                    launchAGL = 0f
                )
            )
        }
    }

    // ---- Mission control ----
    private fun startMission() {
        if (missionActive || returnHomeActive) {
            Toast.makeText(this, "Already flying", Toast.LENGTH_SHORT).show(); return
        }
        missionActive = true
        flightController?.startTakeoff { error ->
            if (error != null) {
                missionActive = false
                Toast.makeText(this, "Takeoff failed: ${error.description}", Toast.LENGTH_LONG).show()
            } else {
                waitForTakeoffAlt = true
            }
        }
    }

    private fun startMissionExecution() {
        isPaused = false
        Toast.makeText(this, "Starting pallet mission…", Toast.LENGTH_SHORT).show()
        executeNextStep()
    }

    private fun executeNextStep() {
        // End conditions
        if ((!missionActive && !returnHomeActive) || isPaused || currentStepIndex >= currentMission.size) {
            if (currentStepIndex >= currentMission.size) {
                missionActive = false
                returnHomeActive = false
                hover()
                Toast.makeText(this, "Mission complete — hovering.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val step = currentMission[currentStepIndex]

        // Boundary step: reset reverse path & photo gating for this pallet segment
        if (step.boundary) {
            reverseStackThisSegment.clear()
            didShootThisPallet = false
            currentStepIndex++
            executeNextStep()
            return
        }

        if (!returnHomeActive) reverseStackThisSegment.add(step)

        val isYaw = step.yaw != 0f
        val isPit = step.pitch != 0f
        val isRol = step.roll != 0f
        val isThr = step.throttle != 0f
        val isNoopHover = !isYaw && !isPit && !isRol && !isThr

        fun ms(d: Float) = (d * 1000).toLong()
        val durYaw = if (isYaw) ms(abs(step.yaw) / YAW_SPEED_DPS) else 0L
        val durPit = if (isPit) ms(abs(step.pitch) / PITCH_ROLL_SPEED_MPS) else 0L
        val durRol = if (isRol) ms(abs(step.roll) / PITCH_ROLL_SPEED_MPS) else 0L
        val durThr = if (isThr) ms(abs(step.throttle) / THROTTLE_SPEED_MPS) else 0L
        stepDuration = if (isNoopHover) HOVER_NOOP_MS else maxOf(durYaw, durPit, durRol, durThr).coerceAtLeast(1L)
        stepStartTime = System.currentTimeMillis()

        // If this is the first hover of the pallet, focus + shoot once
        if (isNoopHover && !didShootThisPallet) {
            didShootThisPallet = true
            handler.postDelayed({ focusCenterThenShoot() }, (FOCUS_SETTLE_MS / 3)) // start focusing early in dwell
        }

        streamingRunnable = object : Runnable {
            override fun run() {
                if (isPaused) return

                val pitchSpeed    = if (isPit) PITCH_ROLL_SPEED_MPS * sign(step.pitch) else 0f
                val rollSpeed     = if (isRol) PITCH_ROLL_SPEED_MPS * sign(step.roll)  else 0f
                val yawSpeed      = if (isYaw) YAW_SPEED_DPS        * sign(step.yaw)   else 0f
                val throttleSpeed = if (isThr) THROTTLE_SPEED_MPS   * sign(step.throttle) else 0f

                flightController?.sendVirtualStickFlightControlData(
                    FlightControlData(pitchSpeed, rollSpeed, yawSpeed, throttleSpeed), null
                )

                val elapsed = System.currentTimeMillis() - stepStartTime
                if (elapsed < stepDuration) {
                    handler.postDelayed(this, STEP_INTERVAL_MS)
                } else {
                    hover()
                    currentStepIndex++
                    executeNextStep()
                }
            }
        }
        handler.post(streamingRunnable!!)
    }

    private fun startReturnPathForThisSegment() {
        if (returnHomeActive) return
        if (reverseStackThisSegment.isEmpty()) {
            Toast.makeText(this, "No path to return from.", Toast.LENGTH_SHORT).show()
            return
        }

        missionActive = false
        returnHomeActive = true
        isPaused = false
        hover()

        val backMission = mutableListOf<MissionStepPallet>()
        reverseStackThisSegment.asReversed().forEach { step ->
            if (step.pitch != 0f || step.roll != 0f || step.throttle != 0f) {
                backMission += MissionStepPallet(-step.pitch, -step.roll, -step.throttle, 0f)
            }
            if (step.yaw != 0f) backMission += MissionStepPallet(0f, 0f, 0f, -step.yaw)
        }

        currentMission = backMission.toMutableList()
        currentStepIndex = 0
        reverseStackThisSegment.clear() // returning now
        didShootThisPallet = false
        Toast.makeText(this, "Returning by retracing last pallet path…", Toast.LENGTH_SHORT).show()
        executeNextStep()
    }

    private fun hover() {
        flightController?.sendVirtualStickFlightControlData(FlightControlData(0f, 0f, 0f, 0f), null)
        streamingRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun togglePause() {
        if (!missionActive && !returnHomeActive) return
        isPaused = !isPaused
        if (isPaused) {
            hover()
            Toast.makeText(this, "Paused — hovering.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Resumed.", Toast.LENGTH_SHORT).show()
            executeNextStep()
        }
    }

    private fun emergencyLand() {
        hover()
        flightController?.startLanding(null)
    }

    // ---- Focus + Photo (called at hover) ----
    private fun focusCenterThenShoot() {
        val w = videoFeedView.width.toFloat().takeIf { it > 0 } ?: return
        val h = videoFeedView.height.toFloat().takeIf { it > 0 } ?: return
        triggerFocus(w / 2f, h / 2f)

        handler.postDelayed({
            takePhoto()
        }, FOCUS_SETTLE_MS)
    }

    private fun triggerFocus(x: Float, y: Float) {
        (DJISDKManager.getInstance().product as? Aircraft)?.camera?.setFocusTarget(PointF(x, y), null)
    }

    private fun takePhoto() {
        val cam = (DJISDKManager.getInstance().product as? Aircraft)?.camera ?: return
        cam.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO) { err ->
            if (err == null) {
                cam.setPhotoFileFormat(SettingsDefinitions.PhotoFileFormat.JPEG, null)
                cam.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.SINGLE) { me ->
                    if (me == null) {
                        cam.startShootPhoto { ce ->
                            val msg = ce?.description ?: "Photo taken"
                            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                            videoFeedView.bitmap?.let { bmp ->
                                saveToGallery(bmp)
                                // If using Drive, uncomment:
                                uploadPreviewToDrive(bmp)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun saveToGallery(bmp: Bitmap) {
        val name = "drone_${aisleLetter}_${System.currentTimeMillis()}.jpg"
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
                val fileName = "drone_${aisleKey}_${System.currentTimeMillis()}.jpg"
                val id = uploadJpegToDrive(drive, fileName, baos.toByteArray(), folderId)
                runOnUiThread { Toast.makeText(this, "Uploaded to $aisleKey (id: $id)", Toast.LENGTH_SHORT).show() }
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

    private fun landNow(reason: String) {
        hover()
        Toast.makeText(this, "Landing: $reason", Toast.LENGTH_SHORT).show()
        flightController?.startLanding(null)
    }
}
