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
import dji.common.util.CommonCallbacks;
import dji.sdk.flightcontroller.Compass;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;

/**
 * Class of compass calibration.
 */
public class CompassCalibrationView extends BaseThreeBtnView {

    private Compass compass;

    public CompassCalibrationView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!ModuleVerificationUtil.isFlightControllerAvailable()) {
            ToastUtils.setResultToToast("Flight controller not available.");
            return;
        }

        FlightController flightController = ((Aircraft) DJISampleApplication.getProductInstance()).getFlightController();
        compass = flightController.getCompass();

        flightController.setStateCallback(new FlightControllerState.Callback() {
            @Override
            public void onUpdate(@NonNull FlightControllerState state) {
                if (compass != null) {
                    String description = "CalibrationStatus: " + compass.getCalibrationState() + "\n"
                            + "Heading: " + compass.getHeading() + "\n"
                            + "IsCalibrating: " + compass.isCalibrating();

                    changeDescription(description);
                }
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (ModuleVerificationUtil.isFlightControllerAvailable()) {
            ((Aircraft) DJISampleApplication.getProductInstance()).getFlightController().setStateCallback(null);
        }
    }

    @Override
    protected int getDescriptionResourceId() {
        return R.string.compass_calibration_description;
    }

    @Override
    protected int getLeftBtnTextResourceId() {
        return R.string.compass_calibration_start_calibration;
    }

    @Override
    protected int getMiddleBtnTextResourceId() {
        return DISABLE; // Button hidden
    }

    @Override
    protected int getRightBtnTextResourceId() {
        return R.string.compass_calibration_stop_calibration;
    }

    @Override
    protected void handleLeftBtnClick() {
        if (ModuleVerificationUtil.isCompassAvailable()) {
            compass.startCalibration(new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        ToastUtils.setResultToToast("Compass calibration started.");
                    } else {
                        ToastUtils.setResultToToast("Failed to start calibration: " + error.getDescription());
                    }
                }
            });
        } else {
            ToastUtils.setResultToToast("Compass not available.");
        }
    }

    @Override
    protected void handleMiddleBtnClick() {
        // Not used
    }

    @Override
    protected void handleRightBtnClick() {
        if (ModuleVerificationUtil.isCompassAvailable()) {
            compass.stopCalibration(new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError error) {
                    if (error == null) {
                        ToastUtils.setResultToToast("Compass calibration stopped.");
                    } else {
                        ToastUtils.setResultToToast("Failed to stop calibration: " + error.getDescription());
                    }
                }
            });
        } else {
            ToastUtils.setResultToToast("Compass not available.");
        }
    }

    @Override
    public int getDescription() {
        return R.string.flight_controller_listview_compass_calibration;
    }
}
