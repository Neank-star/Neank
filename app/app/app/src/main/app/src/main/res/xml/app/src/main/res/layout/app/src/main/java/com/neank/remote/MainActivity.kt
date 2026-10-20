package com.neank.remote

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import org.webrtc.*
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot

// =========================================================================
// 1. MAIN ACTIVITY (Multi-Device Hub Controller + Node Rig + WebRTC)
// =========================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var setupPanel: View
    private lateinit var topControllerBar: View
    private lateinit var bottomStudioBar: View
    private lateinit var bottomAnyDeskBar: View
    private lateinit var reelsSafeZoneGuide: View
    private lateinit var teleprompterContainer: View
    private lateinit var txtStatus: TextView
    private lateinit var txtActiveMicBadge: TextView
    private lateinit var spinnerNodeList: Spinner

    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private var hybridEngine: NeankHybridEngine? = null
    private lateinit var signaling: NeankLocalSignaling
    private lateinit var switchManager: NeankFeatureSwitchManager
    private lateinit var stealthManager: NeankStealthManager
    private val dualAudioMixer = NeankDualAudioMixer()
    private var voiceoverTransmitter: NeankVoiceoverTransmitter? = null
    private var teleprompterOverlay: NeankTeleprompterOverlay? = null

    private var mediaProjectionIntent: Intent? = null
    private var isRecording = false
    private var useFrontLens = false

    private val connectedNodes = mutableListOf("Camera_Node_1", "Camera_Node_2", "Camera_Node_3")

    private var incomingFileStream: FileOutputStream? = null
    private var incomingFileName: String = "Neank_Reel.mp4"

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            mediaProjectionIntent = result.data
            Toast.makeText(this, "AnyDesk Screen Capture Ready!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkDeviceSecurityIntegrity()
        requestRuntimePermissions()
        bindViews()
        initWebRTCCore()

        stealthManager = NeankStealthManager(this)
        switchManager = NeankFeatureSwitchManager(this, peerConnectionFactory) { dataChannel }

        signaling = NeankLocalSignaling(
            onMessageReceived = { rawJson -> handleSignalingMessage(rawJson) },
            onStatusChanged = { status ->
                runOnUiThread { txtStatus.text = status }
            }
        )

        setupNodeSpinner()
        bindMasterSwitches()
    }

    private fun checkDeviceSecurityIntegrity() {
        val isRooted = checkRootMethod1() || checkRootMethod2()
        if (isRooted) {
            Toast.makeText(this, "Security Alert: Rooted device detected!", Toast.LENGTH_LONG).show()
            finishAffinity()
        }
    }

    private fun checkRootMethod1(): Boolean {
        val buildTags = android.os.Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkRootMethod2(): Boolean {
        val paths = arrayOf("/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su")
        for (path in paths) {
            if (File(path).exists()) return true
        }
        return false
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }

    private fun setupNodeSpinner() {
        spinnerNodeList = findViewById(R.id.spinnerNodeList)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, connectedNodes)
        spinnerNodeList.adapter = adapter
        spinnerNodeList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedNode = connectedNodes[position]
                Toast.makeText(this@MainActivity, "Switched to: $selectedNode", Toast.LENGTH_SHORT).show()
                sendCommand(JSONObject().put("type", "SWITCH_NODE_TARGET").put("nodeId", selectedNode))
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun bindViews() {
        remoteVideoView = findViewById(R.id.remoteVideoView)
        setupPanel = findViewById(R.id.setupPanel)
        topControllerBar = findViewById(R.id.topControllerBar)
        bottomStudioBar = findViewById(R.id.bottomStudioBar)
        bottomAnyDeskBar = findViewById(R.id.bottomAnyDeskBar)
        reelsSafeZoneGuide = findViewById(R.id.reelsSafeZoneGuide)
        teleprompterContainer = findViewById(R.id.teleprompterContainer)
        txtStatus = findViewById(R.id.txtStatus)
        txtActiveMicBadge = findViewById(R.id.txtActiveMicBadge)

        teleprompterOverlay = NeankTeleprompterOverlay(
            containerView = teleprompterContainer,
            scrollView = findViewById(R.id.prompterScrollView),
            scriptTextView = findViewById(R.id.txtPrompterScript),
            scriptInputBox = findViewById(R.id.inputPrompterScript),
            speedSeekBar = findViewById(R.id.seekPrompterSpeed)
        )

        findViewById<Button>(R.id.btnResetPrompter).setOnClickListener {
            teleprompterOverlay?.applyUpdatedScript()
        }

        val inputHostIp = findViewById<EditText>(R.id.inputHostIp)

        findViewById<Button>(R.id.btnStartOldPhoneHost).setOnClickListener {
            requestScreenCapturePermissionOnce()
            signaling.startHostServer(8888)
            startOldPhoneHostPipeline()
        }

        findViewById<Button>(R.id.btnConnectNewPhone).setOnClickListener {
            val ip = inputHostIp.text.toString().ifEmpty { "192.168.43.1" }
            signaling.connectToHost(ip, 8888)
            setupControllerUI()
        }

        findViewById<Button>(R.id.btnModeStudio).setOnClickListener {
            sendCommand(JSONObject().put("type", "SWITCH_MODE").put("mode", "STUDIO_9_16"))
            bottomStudioBar.visibility = View.VISIBLE
            bottomAnyDeskBar.visibility = View.GONE
        }

        findViewById<Button>(R.id.btnModeAnyDesk).setOnClickListener {
            sendCommand(JSONObject().put("type", "SWITCH_MODE").put("mode", "ANYDESK_SCREEN"))
            bottomStudioBar.visibility = View.GONE
            bottomAnyDeskBar.visibility = View.VISIBLE
            reelsSafeZoneGuide.visibility = View.GONE
        }

        findViewById<Button>(R.id.btnPullReelFile).setOnClickListener {
            sendCommand(JSONObject().put("type", "PULL_LATEST_REEL"))
            Toast.makeText(this, "Pulling 9:16 Reel...", Toast.LENGTH_SHORT).show()
        }

        val btnRecord = findViewById<Button>(R.id.btnRecordReel)
        btnRecord.setOnClickListener {
            isRecording = !isRecording
            btnRecord.text = if (isRecording) "STOP REC" else "REC 9:16"
            sendCommand(JSONObject().put("type", "RECORD_TOGGLE").put("record", isRecording))

            if (isRecording) {
                if (switchManager.isNewPhoneVoiceoverOn) {
                    voiceoverTransmitter?.startLiveVoiceover()
                }
                teleprompterOverlay?.startAutoScroll()
            } else {
                voiceoverTransmitter?.stopLiveVoiceover()
                teleprompterOverlay?.pauseAutoScroll()
            }
        }

        findViewById<Button>(R.id.btnFlipLens).setOnClickListener {
            useFrontLens = !useFrontLens
            sendCommand(JSONObject().put("type", "FLIP_LENS").put("front", useFrontLens))
        }

        findViewById<Button>(R.id.btnToggleTorch).setOnClickListener {
            sendCommand(JSONObject().put("type", "TOGGLE_TORCH"))
        }

        findViewById<Button>(R.id.btnSafeZone).setOnClickListener {
            reelsSafeZoneGuide.visibility = if (reelsSafeZoneGuide.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.btnNavBack).setOnClickListener { sendNav("BACK") }
        findViewById<Button>(R.id.btnNavHome).setOnClickListener { sendNav("HOME") }
        findViewById<Button>(R.id.btnNavRecents).setOnClickListener { sendNav("RECENTS") }
        findViewById<Button>(R.id.btnNavLock).setOnClickListener { sendNav("LOCK_SCREEN") }
    }

    private fun bindMasterSwitches() {
        val drawer = findViewById<View>(R.id.masterSwitchDrawer)
        findViewById<Button>(R.id.btnToggleSwitchDrawer).setOnClickListener {
            drawer.visibility = if (drawer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<Switch>(R.id.swLiveAudioListen).setOnCheckedChangeListener { _, isChecked ->
            switchManager.setLiveAudioListen(isChecked)
        }
        findViewById<Switch>(R.id.swOldPhoneMic).setOnCheckedChangeListener { _, isChecked ->
            switchManager.isOldPhoneMicOn = isChecked
            switchManager.syncSwitchesToHost()
        }
        findViewById<Switch>(R.id.swNewPhoneVoiceover).setOnCheckedChangeListener { _, isChecked ->
            switchManager.setNewPhoneVoiceover(isChecked, voiceoverTransmitter) { notice ->
                Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Switch>(R.id.swAutoDucking).setOnCheckedChangeListener { _, isChecked ->
            switchManager.isAutoDuckingOn = isChecked
            switchManager.syncSwitchesToHost()
        }
        findViewById<Switch>(R.id.swTeleprompter).setOnCheckedChangeListener { _, isChecked ->
            switchManager.isTeleprompterOn = isChecked
            teleprompterContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        findViewById<Switch>(R.id.swReelsSafeZone).setOnCheckedChangeListener { _, isChecked ->
            switchManager.isSafeZoneGridOn = isChecked
            reelsSafeZoneGuide.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        findViewById<Switch>(R.id.swAnyDeskTouch).setOnCheckedChangeListener { _, isChecked ->
            switchManager.isAnyDeskTouchOn = isChecked
            remoteVideoView.isEnabled = isChecked
            switchManager.syncSwitchesToHost()
        }
        findViewById<Switch>(R.id.swStealthDarkScreen).setOnCheckedChangeListener { _, isChecked ->
            switchManager.isOldPhoneStealthDarkOn = isChecked
            switchManager.syncSwitchesToHost()
        }
        findViewById<Switch>(R.id.swStealthHideApp).setOnCheckedChangeListener { _, isChecked ->
            stealthManager.hideAppFromLauncher(isChecked)
        }
    }

    private fun initWebRTCCore() {
        eglBase = EglBase.create()
        remoteVideoView.init(eglBase.eglBaseContext, null)
        remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        return peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    val json = JSONObject().apply {
                        put("sdpType", "CANDIDATE")
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate", candidate.sdp)
                    }
                    signaling.sendSignalingMessage(json.toString())
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                if (stream != null) {
                    runOnUiThread {
                        stream.videoTracks.firstOrNull()?.addSink(remoteVideoView)
                        switchManager.attachRemoteAudioStreamToController(stream)
                    }
                }
            }

            override fun onDataChannel(dc: DataChannel?) {
                if (dc != null) attachDataChannelObserver(dc)
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })
    }

    private fun startOldPhoneHostPipeline() {
        peerConnection = createPeerConnection()
        val dcInit = DataChannel.Init().apply { ordered = true }
        val dc = peerConnection?.createDataChannel("NeankControlChannel", dcInit)
        if (dc != null) attachDataChannelObserver(dc)

        hybridEngine = NeankHybridEngine(this, eglBase.eglBaseContext, peerConnectionFactory) { dataChannel }
        hybridEngine?.switchOperatingMode(NeankHybridEngine.NeankMode.STUDIO_9_16_CAMERA, null, false)

        val localStream = peerConnectionFactory.createLocalMediaStream("NEANK_HOST_STREAM")
        hybridEngine?.localVideoTrack?.let {
            localStream.addTrack(it)
            it.addSink(remoteVideoView)
        }
        localStream.addTrack(switchManager.createHostLiveAudioTrack())
        peerConnection?.addStream(localStream)
    }

    private fun setupControllerUI() {
        setupPanel.visibility = View.GONE
        topControllerBar.visibility = View.VISIBLE
        bottomStudioBar.visibility = View.VISIBLE
        txtActiveMicBadge.visibility = View.VISIBLE
        spinnerNodeList.visibility = View.VISIBLE
        reelsSafeZoneGuide.visibility = View.VISIBLE

        peerConnection = createPeerConnection()
        NeankControllerGestureBinding.attachToRemoteView(remoteVideoView) { dataChannel }

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    peerConnection?.setLocalDescription(SdpAdapter(), desc)
                    val json = JSONObject().apply {
                        put("sdpType", "OFFER")
                        put("sdp", desc.description)
                    }
                    signaling.sendSignalingMessage(json.toString())
                }
            }
        }, constraints)
    }

    private fun attachDataChannelObserver(dc: DataChannel) {
        dataChannel = dc
        voiceoverTransmitter = NeankVoiceoverTransmitter(this, dc) { micLabel ->
            runOnUiThread { txtActiveMicBadge.text = micLabel }
        }
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                onDataChannelBufferReceived(buffer)
            }
        })
    }

    private fun handleSignalingMessage(rawJson: String) {
        val json = JSONObject(rawJson)
        when (json.optString("sdpType")) {
            "OFFER" -> {
                val offer = SessionDescription(SessionDescription.Type.OFFER, json.getString("sdp"))
                peerConnection?.setRemoteDescription(SdpAdapter(), offer)
                peerConnection?.createAnswer(object : SdpAdapter() {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer != null) {
                            peerConnection?.setLocalDescription(SdpAdapter(), answer)
                            val ansJson = JSONObject().apply {
                                put("sdpType", "ANSWER")
                                put("sdp", answer.description)
                            }
                            signaling.sendSignalingMessage(ansJson.toString())
                        }
                    }
                }, MediaConstraints())
            }
            "ANSWER" -> {
                val answer = SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp"))
                peerConnection?.setRemoteDescription(SdpAdapter(), answer)
            }
            "CANDIDATE" -> {
                val candidate = IceCandidate(
                    json.getString("sdpMid"),
                    json.getInt("sdpMLineIndex"),
                    json.getString("candidate")
                )
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    private fun onDataChannelBufferReceived(buffer: DataChannel.Buffer) {
        if (buffer.binary) {
            val firstByte = buffer.data.get(buffer.data.position())
            if (firstByte == 0xA1.toByte()) {
                buffer.data.get()
                val pcmBytes = ByteArray(buffer.data.remaining())
                buffer.data.get(pcmBytes)
                dualAudioMixer.onRemoteVoiceoverPacketReceived(pcmBytes)
            } else {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                incomingFileStream?.write(bytes)
            }
        } else {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(
