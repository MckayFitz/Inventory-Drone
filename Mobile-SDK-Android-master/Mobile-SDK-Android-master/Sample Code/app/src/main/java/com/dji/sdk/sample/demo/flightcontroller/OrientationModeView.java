package com.dji.sdk.sample.demo.flightcontroller;

import android.content.Context;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.utils.ModuleVerificationUtil;
import com.dji.sdk.sample.internal.utils.ToastUtils;
import com.dji.sdk.sample.internal.view.BaseThreeBtnView;

import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.FlightOrientationMode;
import dji.common.util.CommonCallbacks;
import dji.sdk.flightcontroller.FlightController;

/**
 * Class for Orientation mode.
 */
public class OrientationModeView extends BaseThreeBtnView {

    private FlightController flightController;

    public OrientationModeView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!ModuleVerificationUtil.isFlightControllerAvailable()) {
            ToastUtils.setResultToToast("Flight controller not available");
            return;
        }

        flightController = DJISampleApplication.getAircraftInstance().getFlightController();

        flightController.setStateCallback(new FlightControllerState.Callback() {
            @Override
            public void onUpdate(@NonNull FlightControllerState state) {
                String orientationMode = state.getOrientationMode().name();
                changeDescription("Current Orientation Mode:\n" + orientationMode);
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (flightController != null) {
            flightController.setStateCallback(null);
        }
    }

    @Override
    protected int getMiddleBtnTextResourceId() {
        return R.string.orientation_mode_home_lock;
    }

    @Override
    protected int getLeftBtnTextResourceId() {
        return R.string.orientation_mode_course_lock;
    }

    @Override
    protected int getRightBtnTextResourceId() {
        return R.string.orientation_mode_aircraft_heading;
    }

    @Override
    protected int getDescriptionResourceId() {
        return R.string.orientation_mode_description;
    }

    @Override
    protected void handleMiddleBtnClick() {
        setOrientationMode(FlightOrientationMode.HOME_LOCK);
    }

    @Override
    protected void handleLeftBtnClick() {
        setOrientationMode(FlightOrientationMode.COURSE_LOCK);
    }

    @Override
    protected void handleRightBtnClick() {
        setOrientationMode(FlightOrientationMode.AIRCRAFT_HEADING);
    }

    private void setOrientationMode(FlightOrientationMode mode) {
        if (flightController == null) {
            ToastUtils.setResultToToast("Flight controller unavailable");
            return;
        }

        flightController.setFlightOrientationMode(mode, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                if (error == null) {
                    ToastUtils.setResultToToast("Orientation mode set to " + mode.name());
                } else {
                    ToastUtils.setResultToToast("Error: " + error.getDescription());
                }
            }
        });
    }

    @Override
    public int getDescription() {
        return R.string.flight_controller_listview_orientation_mode;
    }
}
