package com.dji.sdk.sample.demo.lidar

import android.app.Service
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.dji.sdk.sample.databinding.ViewLidarBinding
import com.dji.sdk.sample.internal.utils.ToastUtils
import com.dji.sdk.sample.internal.utils.ViewHelper
import com.dji.sdk.sample.internal.view.PopupNumberPicker
import com.dji.sdk.sample.internal.view.PopupNumberPickerDouble
import com.dji.sdk.sample.internal.view.PresentableView
import dji.common.airlink.PhysicalSource
import dji.common.error.DJIError
import dji.common.perception.*
import dji.lidar_map.views.PointCloudColorMode
import dji.sdk.camera.VideoFeeder
import dji.sdk.lidar.Lidar
import dji.sdk.lidar.processor.DJILidarLiveViewDataProcessor
import dji.sdk.lidar.processor.PointCloudDisplayMode
import dji.sdk.lidar.reader.PointCloudLiveViewData
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class LidarView(context: Context) : LinearLayout(context), PresentableView, View.OnClickListener {

    private lateinit var binding: ViewLidarBinding
    private var currentLidar: Lidar? = null
    private var pointCloudStatusListener: Lidar.DJIPointCloudStatusListener? = null

    private var mPopupNumberPicker: PopupNumberPicker? = null
    private var mPopupDoubleNumberPicker: PopupNumberPickerDouble? = null
    private val indexChosen = intArrayOf(-1, -1, -1)

    private var pointCloudColorMode: PointCloudColorMode? = null
    private var lidarPointCloudVisibleLightPixelMode: DJILidarPointCloudVisibleLightPixelMode? = null
    private var pointCloudDisplayMode: PointCloudDisplayMode? = null
    private var recordingStatus: RecordingStatus? = null
    private var recordingTime: Long = 0

    init {
        initUI(context)
    }

    private fun initUI(context: Context) {
        isClickable = true
        orientation = VERTICAL

        val inflater = LayoutInflater.from(context)
        binding = ViewLidarBinding.inflate(inflater, this)
        addView(binding.root)

        binding.btnSetVideoSource.setOnClickListener(this)
        binding.btnUpdateLidar.setOnClickListener(this)
        binding.btnSendCommandToRemote.setOnClickListener(this)
        binding.btnStartReadPointCloudLiveViewData.setOnClickListener(this)
        binding.btnStopReadPointCloudLiveViewData.setOnClickListener(this)
        binding.btnSetPointCloudLiveViewDisplayMode.setOnClickListener(this)
        binding.btnFreshPointCloudLiveViewDataProcessedBuffers.setOnClickListener(this)
        binding.btnClearPointCloudLiveViewDataProcessedBuffers.setOnClickListener(this)
        binding.btnSetPointCloudVisibleLightPixelBtn.setOnClickListener(this)
        binding.btnSetPointCloudColorMode.setOnClickListener(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        binding.videoViewPrimaryVideoFeed.registerLiveVideo(
            VideoFeeder.getInstance().primaryVideoFeed, true
        )
        DJILidarLiveViewDataProcessor.getInstance().bindView(binding.pointCloudViewSurface)
        DJILidarLiveViewDataProcessor.getInstance().setLidarIndex(0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        DJILidarLiveViewDataProcessor.getInstance().destroy()
    }

    override fun onClick(v: View?) {
        if (currentLidar == null) updateLidar(0)
        if (currentLidar == null) {
            ToastUtils.setResultToToast("Lidar Disconnect")
            return
        }

        when (v) {
            binding.btnSetVideoSource -> setVideoSource()
            binding.btnUpdateLidar -> updateLidar(0)
            binding.btnSendCommandToRemote -> sendCommandToRemote()
            binding.btnStartReadPointCloudLiveViewData -> startReadPointCloudLiveViewData()
            binding.btnStopReadPointCloudLiveViewData -> stopReadPointCloudLiveViewData()
            binding.btnSetPointCloudLiveViewDisplayMode -> setPointCloudLiveViewDisplayMode()
            binding.btnFreshPointCloudLiveViewDataProcessedBuffers -> freshPointCloudLiveViewDataProcessedBuffers()
            binding.btnClearPointCloudLiveViewDataProcessedBuffers -> clearPointCloudLiveViewDataProcessedBuffers()
            binding.btnSetPointCloudVisibleLightPixelBtn -> setPointCloudVisibleLightPixel()
            binding.btnSetPointCloudColorMode -> setPointCloudColorMode()
        }
    }

    private fun startReadPointCloudLiveViewData() {
        currentLidar?.startReadPointCloudLiveViewData {
            ToastUtils.setResultToToast(it?.description ?: "Success")
        }
    }

    private fun stopReadPointCloudLiveViewData() {
        currentLidar?.stopReadPointCloudLiveViewData {
            ToastUtils.setResultToToast(it?.description ?: "Success")
        }
    }

    private fun sendCommandToRemote() {
        val actions = DJILidarPointCloudRecord.getValues()
        val runnable = Runnable {
            currentLidar?.pointCloudRecord(actions[indexChosen[0]]) {
                ToastUtils.setResultToToast(it?.description ?: "Success")
            }
            resetIndex()
        }
        initPopupNumberPicker(ViewHelper.makeList(actions), runnable)
    }

    private fun setPointCloudLiveViewDisplayMode() {
        val modes = PointCloudDisplayMode.values()
        val runnable = Runnable {
            pointCloudDisplayMode = modes[indexChosen[0]]
            DJILidarLiveViewDataProcessor.getInstance().setPointCloudLiveViewDisplayMode(pointCloudDisplayMode)
            resetIndex()
        }
        initPopupNumberPicker(ViewHelper.makeList(modes), runnable)
    }

    private fun freshPointCloudLiveViewDataProcessedBuffers() {
        DJILidarLiveViewDataProcessor.getInstance().freshPointCloudLiveViewDataProcessedBuffers()
    }

    private fun clearPointCloudLiveViewDataProcessedBuffers() {
        DJILidarLiveViewDataProcessor.getInstance().clearPointCloudLiveViewDataProcessedBuffers()
    }

    private fun setPointCloudVisibleLightPixel() {
        val modes = DJILidarPointCloudVisibleLightPixelMode.values()
        val runnable = Runnable {
            lidarPointCloudVisibleLightPixelMode = DJILidarPointCloudVisibleLightPixelMode.find(indexChosen[0])
            currentLidar?.setPointCloudVisibleLightPixel(lidarPointCloudVisibleLightPixelMode) {
                ToastUtils.setResultToToast(it?.description ?: "Success")
            }
            resetIndex()
        }
        initPopupNumberPicker(ViewHelper.makeList(modes), runnable)
    }

    private fun setPointCloudColorMode() {
        val modes = PointCloudColorMode.values()
        val runnable = Runnable {
            pointCloudColorMode = modes[indexChosen[0]]
            DJILidarLiveViewDataProcessor.getInstance().setPointCloudLiveViewColorMode(pointCloudColorMode)
            resetIndex()
        }
        initPopupNumberPicker(ViewHelper.makeList(modes), runnable)
    }

    private fun initPopupNumberPicker(list: ArrayList<String>, r: Runnable) {
        mPopupNumberPicker = PopupNumberPicker(context, list, { pos1, _ ->
            mPopupNumberPicker?.dismiss()
            mPopupNumberPicker = null
            indexChosen[0] = pos1
            handler.post(r)
        }, 250, 200, 0)
        mPopupNumberPicker?.showAtLocation(this, Gravity.CENTER, 0, 0)
    }

    private fun initPopupNumberPicker(list1: ArrayList<String>, list2: ArrayList<String>, r: Runnable) {
        mPopupDoubleNumberPicker = PopupNumberPickerDouble(
            context, list1, null, list2, null, { pos1, pos2 ->
                mPopupDoubleNumberPicker?.dismiss()
                mPopupDoubleNumberPicker = null
                indexChosen[0] = pos1
                indexChosen[1] = pos2
                handler.post(r)
            }, 500, 200, 0
        )
        mPopupDoubleNumberPicker?.showAtLocation(this, Gravity.CENTER, 0, 0)
    }

    private fun updatePointCloudInfo() {
        val builder = StringBuilder().apply {
            append("PointCloudColorMode: $pointCloudColorMode\n")
            append("VisibleLightPixelMode: $lidarPointCloudVisibleLightPixelMode\n")
            append("DisplayMode: $pointCloudDisplayMode\n")
            append("RecordingStatus: $recordingStatus\n")
            append("RecordingTime: $recordingTime\n")
        }
        binding.lidarInfoView.text = builder.toString()
    }

    private fun resetIndex() {
        indexChosen.fill(-1)
    }

    private fun updateLidar(index: Int) {
        unInitListener()
        val product = DJISDKManager.getInstance().product as? Aircraft ?: return
        currentLidar = product.lidars.find { it.index == index && it.isConnected }
        initListener(index)
    }

    private fun initListener(index: Int) {
        val lidar = currentLidar ?: return
        DJILidarLiveViewDataProcessor.getInstance().setLidarIndex(index)
        lidar.addPointCloudLiveViewDataListener(object : Lidar.DJIPointCloudLiveDataListener {
            override fun onReceiveLiveViewData(data: Array<PointCloudLiveViewData>, length: Int) {
                DJILidarLiveViewDataProcessor.getInstance().addPointCloudLiveViewData(data, length)
            }

            override fun onError(error: DJIError) {
                ToastUtils.setResultToToast(error?.description ?: "Unknown")
            }
        })
        pointCloudStatusListener = object : Lidar.DJIPointCloudStatusListener {
            override fun onPointCloudRecordStatusChange(newStatus: RecordingStatus) {
                recordingStatus = newStatus
                updatePointCloudInfo()
            }

            override fun onPointCloudRecordStatusRecordingTimeChange(newRecordingTime: Long) {
                recordingTime = newRecordingTime
                updatePointCloudInfo()
            }

            override fun onIMUPreHeatStatusChange(p0: DJILidarIMUPreheatStatus?) {}
        }
        lidar.addPointCloudStatusListener(pointCloudStatusListener)
    }

    private fun unInitListener() {
        currentLidar?.removePointCloudLiveViewDataListener(null)
        currentLidar?.removePointCloudStatusListener(pointCloudStatusListener)
    }

    private fun setVideoSource() {
        val product = DJISDKManager.getInstance().product ?: return ToastUtils.setResultToToast("Disconnected")
        val ocuSyncLink = product.airLink?.ocuSyncLink ?: return ToastUtils.setResultToToast("No ocuSyncLink")

        val sources = listOf(
            PhysicalSource.LEFT_CAM,
            PhysicalSource.RIGHT_CAM,
            PhysicalSource.TOP_CAM,
            PhysicalSource.FPV_CAM
        )

        val names = sources.map { it.name }
        val runnable = Runnable {
            ocuSyncLink.assignSourceToPrimaryChannel(
                sources[indexChosen[0]],
                sources[indexChosen[1]]
            ) {
                ToastUtils.setResultToToast(it?.description ?: "Success")
            }
            resetIndex()
        }
        initPopupNumberPicker(ArrayList(names), ArrayList(names), runnable)
    }

    override fun getDescription() = com.dji.sdk.sample.R.string.component_listview_lidar_view
    override fun getHint() = this.javaClass.simpleName + ".kt"
}
