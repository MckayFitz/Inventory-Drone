package com.dji.sdk.sample.demo.flightcontroller;

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
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.model.LocationCoordinate2D;
import dji.common.util.CommonCallbacks;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.flightcontroller.Simulator;

public class VirtualStickView extends RelativeLayout implements View.OnClickListener, CompoundButton.OnCheckedChangeListener, PresentableView {
    private Button btnEnableVirtualStick;
    private Button btnDisableVirtualStick;
    private Button btnHorizontalCoordinate;
    private Button btnSetYawControlMode;
    private Button btnSetVerticalControlMode;
    private Button btnSetRollPitchControlMode;
    private ToggleButton btnSimulator;
    private Button btnTakeOff;

    private TextView textView;

    private OnScreenJoystick screenJoystickRight;
    private OnScreenJoystick screenJoystickLeft;

    private Timer sendVirtualStickDataTimer;
    private SendVirtualStickDataTask sendVirtualStickDataTask;

    private float pitch;
    private float roll;
    private float yaw;
    private float throttle;
    private boolean isSimulatorActived = false;
    private FlightController flightController = null;
    private Simulator simulator = null;

    public VirtualStickView(Context context) {
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
    }

    @Override
    protected void onDetachedFromWindow() {
        if (null != sendVirtualStickDataTimer) {
            if (sendVirtualStickDataTask != null) {
                sendVirtualStickDataTask.cancel();
            }
            sendVirtualStickDataTimer.cancel();
            sendVirtualStickDataTimer.purge();
            sendVirtualStickDataTimer = null;
            sendVirtualStickDataTask = null;
        }
        tearDownListeners();
        super.onDetachedFromWindow();
    }

    private void init(Context context) {
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_virtual_stick, this, true);
        initParams();
        initUI();
    }

    private void initParams() {
        if (flightController == null) {
            if (ModuleVerificationUtil.isFlightControllerAvailable()) {
                flightController = DJISampleApplication.getAircraftInstance().getFlightController();
            }
        }
        flightController.setVerticalControlMode(VerticalControlMode.VELOCITY);
        flightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        flightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
        flightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);

        if (simulator == null) {
            simulator = ModuleVerificationUtil.getSimulator();
        }
        isSimulatorActived = simulator.isSimulatorActive();
    }

    private void initUI() {
        btnEnableVirtualStick = findViewById(R.id.btn_enable_virtual_stick);
        btnDisableVirtualStick = findViewById(R.id.btn_disable_virtual_stick);
        btnHorizontalCoordinate = findViewById(R.id.btn_horizontal_coordinate);
        btnSetYawControlMode = findViewById(R.id.btn_yaw_control_mode);
        btnSetVerticalControlMode = findViewById(R.id.btn_vertical_control_mode);
        btnSetRollPitchControlMode = findViewById(R.id.btn_roll_pitch_control_mode);
        btnTakeOff = findViewById(R.id.btn_take_off);
        btnSimulator = findViewById(R.id.btn_start_simulator);
        textView = findViewById(R.id.textview_simulator);
        screenJoystickRight = findViewById(R.id.directionJoystickRight);
        screenJoystickLeft = findViewById(R.id.directionJoystickLeft);

        btnEnableVirtualStick.setOnClickListener(this);
        btnDisableVirtualStick.setOnClickListener(this);
        btnHorizontalCoordinate.setOnClickListener(this);
        btnSetYawControlMode.setOnClickListener(this);
        btnSetVerticalControlMode.setOnClickListener(this);
        btnSetRollPitchControlMode.setOnClickListener(this);
        btnTakeOff.setOnClickListener(this);
        btnSimulator.setOnCheckedChangeListener(this);

        if (isSimulatorActived) {
            btnSimulator.setChecked(true);
            textView.setText("Simulator is On.");
        }
    }

    private void setUpListeners() {
        if (simulator != null) {
            simulator.setStateCallback(new SimulatorState.Callback() {
                @Override
                public void onUpdate(@NonNull final SimulatorState simulatorState) {
                    ToastUtils.setResultToText(textView,
                            "Yaw : " + simulatorState.getYaw() + "," +
                                    "X : " + simulatorState.getPositionX() + "\n" +
                                    "Y : " + simulatorState.getPositionY() + "," +
                                    "Z : " + simulatorState.getPositionZ());
                }
            });
        } else {
            ToastUtils.setResultToToast("Simulator disconnected!");
        }

        screenJoystickLeft.setJoystickListener((joystick, pX, pY) -> {
            if (Math.abs(pX) < 0.02) pX = 0;
            if (Math.abs(pY) < 0.02) pY = 0;
            pitch = 10 * pY;
            roll = 10 * pX;
            startSendingVirtualStickData();
        });

        screenJoystickRight.setJoystickListener((joystick, pX, pY) -> {
            if (Math.abs(pX) < 0.02) pX = 0;
            if (Math.abs(pY) < 0.02) pY = 0;
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
            case R.id.btn_enable_virtual_stick:
                flightController.setVirtualStickModeEnabled(true, djiError -> {
                    flightController.setVirtualStickAdvancedModeEnabled(true);
                    DialogUtils.showDialogBasedOnError(getContext(), djiError);
                });
                break;
            case R.id.btn_disable_virtual_stick:
                flightController.setVirtualStickModeEnabled(false, djiError ->
                        DialogUtils.showDialogBasedOnError(getContext(), djiError));
                break;
            case R.id.btn_roll_pitch_control_mode:
                RollPitchControlMode rpMode = flightController.getRollPitchControlMode();
                flightController.setRollPitchControlMode(
                        rpMode == RollPitchControlMode.VELOCITY ? RollPitchControlMode.ANGLE : RollPitchControlMode.VELOCITY);
                ToastUtils.setResultToToast(flightController.getRollPitchControlMode().name());
                break;
            case R.id.btn_yaw_control_mode:
                YawControlMode yMode = flightController.getYawControlMode();
                flightController.setYawControlMode(
                        yMode == YawControlMode.ANGULAR_VELOCITY ? YawControlMode.ANGLE : YawControlMode.ANGULAR_VELOCITY);
                ToastUtils.setResultToToast(flightController.getYawControlMode().name());
                break;
            case R.id.btn_vertical_control_mode:
                VerticalControlMode vMode = flightController.getVerticalControlMode();
                flightController.setVerticalControlMode(
                        vMode == VerticalControlMode.VELOCITY ? VerticalControlMode.POSITION : VerticalControlMode.VELOCITY);
                ToastUtils.setResultToToast(flightController.getVerticalControlMode().name());
                break;
            case R.id.btn_horizontal_coordinate:
                FlightCoordinateSystem fcs = flightController.getRollPitchCoordinateSystem();
                flightController.setRollPitchCoordinateSystem(
                        fcs == FlightCoordinateSystem.BODY ? FlightCoordinateSystem.GROUND : FlightCoordinateSystem.BODY);
                ToastUtils.setResultToToast(flightController.getRollPitchCoordinateSystem().name());
                break;
            case R.id.btn_take_off:
                flightController.startTakeoff(djiError ->
                        DialogUtils.showDialogBasedOnError(getContext(), djiError));
                break;
            default:
                break;
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView == btnSimulator) {
            if (simulator == null) return;
            if (isChecked) {
                textView.setVisibility(VISIBLE);
                simulator.start(InitializationData.createInstance(new LocationCoordinate2D(23, 113), 10, 10), djiError -> {
                    if (djiError != null) ToastUtils.setResultToToast(djiError.getDescription());
                });
            } else {
                textView.setVisibility(INVISIBLE);
                simulator.stop(djiError -> {
                    if (djiError != null) ToastUtils.setResultToToast(djiError.getDescription());
                });
            }
        }
    }

    @Override
    public int getDescription() {
        return R.string.flight_controller_listview_virtual_stick;
    }

    private class SendVirtualStickDataTask extends TimerTask {
        @Override
        public void run() {
            if (flightController != null) {
                flightController.sendVirtualStickFlightControlData(
                        new FlightControlData(pitch, roll, yaw, throttle),
                        djiError -> {
                            if (djiError != null) ToastUtils.setResultToToast(djiError.getDescription());
                        });
            }
        }
    }
}