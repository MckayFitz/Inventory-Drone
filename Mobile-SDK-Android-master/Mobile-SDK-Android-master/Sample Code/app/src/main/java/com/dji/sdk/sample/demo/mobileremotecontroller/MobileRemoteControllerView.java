package com.dji.sdk.sample.demo.mobileremotecontroller;

import android.app.Service;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.OnScreenJoystickListener;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.utils.DialogUtils;
import com.dji.sdk.sample.internal.utils.ModuleVerificationUtil;
import com.dji.sdk.sample.internal.utils.OnScreenJoystick;
import com.dji.sdk.sample.internal.utils.ToastUtils;
import com.dji.sdk.sample.internal.view.PresentableView;

import java.util.Timer;
import java.util.TimerTask;

import dji.common.error.DJIError;
import dji.common.flightcontroller.simulator.InitializationData;
import dji.common.flightcontroller.simulator.SimulatorState;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.model.LocationCoordinate2D;
import dji.common.util.CommonCallbacks.CompletionCallback;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.flightcontroller.Simulator;
import dji.sdk.mobilerc.MobileRemoteController;
import dji.sdk.products.Aircraft;

public class MobileRemoteControllerView extends RelativeLayout implements View.OnClickListener, CompoundButton.OnCheckedChangeListener, PresentableView {

    private ToggleButton btnSimulator;
    private Button btnTakeOff;
    private Button autoLand;
    private Button forceLand;

    private TextView textView;

    private OnScreenJoystick screenJoystickRight;
    private OnScreenJoystick screenJoystickLeft;
    private MobileRemoteController mobileRemoteController;
    private FlightController flightController;
    private float pitch, roll, yaw, throttle;
    private Timer sendVirtualStickDataTimer;
    private SendVirtualStickDataTask sendVirtualStickDataTask;

    public MobileRemoteControllerView(Context context) {
        super(context);
        init(context);
    }

    @NonNull
    @Override
    public String getHint() {
        return this.getClass().getSimpleName() + ".java";
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setUpListeners();
        if (ModuleVerificationUtil.isFlightControllerAvailable()) {
            flightController = DJISampleApplication.getAircraftInstance().getFlightController();
            flightController.setVirtualStickModeEnabled(true, djiError -> ToastUtils.setResultToToast(
                    djiError == null ? "Virtual Stick Enabled" : djiError.getDescription()
            ));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        tearDownListeners();
        if (sendVirtualStickDataTimer != null) {
            sendVirtualStickDataTask.cancel();
            sendVirtualStickDataTimer.cancel();
            sendVirtualStickDataTimer = null;
        }
        super.onDetachedFromWindow();
    }

    private void init(Context context) {
        setClickable(true);
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_mobile_rc, this, true);
        initUI();
    }

    private void initUI() {
        btnTakeOff = findViewById(R.id.btn_take_off);
        autoLand = findViewById(R.id.btn_auto_land);
        forceLand = findViewById(R.id.btn_force_land);
        btnSimulator = findViewById(R.id.btn_start_simulator);
        textView = findViewById(R.id.textview_simulator);

        screenJoystickRight = findViewById(R.id.directionJoystickRight);
        screenJoystickLeft = findViewById(R.id.directionJoystickLeft);

        btnTakeOff.setOnClickListener(this);
        autoLand.setOnClickListener(this);
        forceLand.setOnClickListener(this);
        btnSimulator.setOnCheckedChangeListener(this);
    }

    private void setUpListeners() {
        Simulator simulator = ModuleVerificationUtil.getSimulator();
        if (simulator != null) {
            simulator.setStateCallback(simulatorState -> ToastUtils.setResultToText(textView,
                    "Yaw : " + simulatorState.getYaw() + ", X : " + simulatorState.getPositionX() + ",\n" +
                            "Y : " + simulatorState.getPositionY() + ", Z : " + simulatorState.getPositionZ()));
        }

        try {
            mobileRemoteController = ((Aircraft) DJISampleApplication.getAircraftInstance()).getMobileRemoteController();
        } catch (Exception exception) {
            exception.printStackTrace();
        }

        screenJoystickLeft.setJoystickListener((joystick, pX, pY) -> {
            pitch = 10 * pY;
            roll = 10 * pX;
            startSendingVirtualStickData();
        });

        screenJoystickRight.setJoystickListener((joystick, pX, pY) -> {
            yaw = 20 * pX;
            throttle = 4 * pY;
            startSendingVirtualStickData();
        });
    }

    private void startSendingVirtualStickData() {
        if (sendVirtualStickDataTimer == null) {
            sendVirtualStickDataTask = new SendVirtualStickDataTask();
            sendVirtualStickDataTimer = new Timer();
            sendVirtualStickDataTimer.schedule(sendVirtualStickDataTask, 0, 200);
        }
    }

    private void tearDownListeners() {
        Simulator simulator = ModuleVerificationUtil.getSimulator();
        if (simulator != null) {
            simulator.setStateCallback(null);
        }
        screenJoystickLeft.setJoystickListener(null);
        screenJoystickRight.setJoystickListener(null);
    }

    @Override
    public void onClick(View v) {
        if (flightController == null) return;
        switch (v.getId()) {
            case R.id.btn_take_off:
                flightController.startTakeoff(djiError -> DialogUtils.showDialogBasedOnError(getContext(), djiError));
                break;
            case R.id.btn_force_land:
                flightController.confirmLanding(djiError -> DialogUtils.showDialogBasedOnError(getContext(), djiError));
                break;
            case R.id.btn_auto_land:
                flightController.startLanding(djiError -> DialogUtils.showDialogBasedOnError(getContext(), djiError));
                break;
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
        if (compoundButton == btnSimulator) {
            Simulator simulator = ModuleVerificationUtil.getSimulator();
            if (simulator == null) return;
            if (isChecked) {
                textView.setVisibility(VISIBLE);
                simulator.start(InitializationData.createInstance(new LocationCoordinate2D(23, 113), 10, 10), null);
            } else {
                textView.setVisibility(INVISIBLE);
                simulator.stop(null);
            }
        }
    }

    @Override
    public int getDescription() {
        return R.string.component_listview_mobile_remote_controller;
    }

    private class SendVirtualStickDataTask extends TimerTask {
        @Override
        public void run() {
            if (flightController != null) {
                flightController.sendVirtualStickFlightControlData(
                        new FlightControlData(roll, pitch, yaw, throttle),
                        djiError -> {
                            if (djiError != null) {
                                ToastUtils.setResultToToast(djiError.getDescription());
                            }
                        });
            }
        }
    }
}
