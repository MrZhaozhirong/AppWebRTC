package com.zzrblog.appwebrtc;

import android.widget.SeekBar;
import android.widget.TextView;

import org.webrtc.CameraEnumerationAndroid;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class CaptureQualityController implements SeekBar.OnSeekBarChangeListener  {
    private final List<CameraEnumerationAndroid.CaptureFormat> formats =
            Arrays.asList(
                    new CameraEnumerationAndroid.CaptureFormat(1280, 720, 0, 30000),
                    new CameraEnumerationAndroid.CaptureFormat(960, 540, 0, 30000),
                    new CameraEnumerationAndroid.CaptureFormat(640, 480, 0, 30000),
                    new CameraEnumerationAndroid.CaptureFormat(480, 360, 0, 30000),
                    new CameraEnumerationAndroid.CaptureFormat(320, 240, 0, 30000),
                    new CameraEnumerationAndroid.CaptureFormat(256, 144, 0, 30000));
    // Prioritize framerate below this threshold and resolution above the threshold.
    private static final int FRAMERATE_THRESHOLD = 15;
    private TextView captureFormatText;
    private CallFragment.OnCallEvents callEvents;
    private int width;
    private int height;
    private int framerate;
    private double targetBandwidth;

    public CaptureQualityController(
            TextView captureFormatText, CallFragment.OnCallEvents callEvents) {
        this.captureFormatText = captureFormatText;
        this.callEvents = callEvents;
    }

    private final Comparator<CameraEnumerationAndroid.CaptureFormat> compareFormats =
            new Comparator<CameraEnumerationAndroid.CaptureFormat>() {
        @Override
        public int compare(CameraEnumerationAndroid.CaptureFormat first, CameraEnumerationAndroid.CaptureFormat second) {
            int firstFps = calculateFramerate(targetBandwidth, first);
            int secondFps = calculateFramerate(targetBandwidth, second);

            if ((firstFps >= FRAMERATE_THRESHOLD && secondFps >= FRAMERATE_THRESHOLD)
                    || firstFps == secondFps) {
                // Compare resolution.
                return first.width * first.height - second.width * second.height;
            } else {
                // Compare fps.
                return firstFps - secondFps;
            }
        }
    };

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (progress == 0) {
            width = 0;
            height = 0;
            framerate = 0;
            captureFormatText.setText(R.string.muted);
            return;
        }

        // Extract max bandwidth (in millipixels / second).
        long maxCaptureBandwidth = java.lang.Long.MIN_VALUE;
        for (CameraEnumerationAndroid.CaptureFormat format : formats) {
            maxCaptureBandwidth = Math.max(maxCaptureBandwidth, (long) format.width * format.height * format.framerate.max);
        }
        // Fraction between 0 and 1.
        double bandwidthFraction = (double) progress / 100.0;
        // Make a log-scale transformation, still between 0 and 1.
        final double kExpConstant = 3.0;
        bandwidthFraction =
                (Math.exp(kExpConstant * bandwidthFraction) - 1) / (Math.exp(kExpConstant) - 1);
        targetBandwidth = bandwidthFraction * maxCaptureBandwidth;

        // Choose the best format given a target bandwidth.
        final CameraEnumerationAndroid.CaptureFormat bestFormat = Collections.max(formats, compareFormats);
        width = bestFormat.width;
        height = bestFormat.height;
        framerate = calculateFramerate(targetBandwidth, bestFormat);

        captureFormatText.setText(
                String.format(captureFormatText.getContext().getString(R.string.format_description),
                        width, height, framerate));
    }

    // Return the highest frame rate possible based on bandwidth and format.
    private int calculateFramerate(double bandwidth, CameraEnumerationAndroid.CaptureFormat format) {
        return (int) Math.round(
                Math.min(format.framerate.max, (int) Math.round(bandwidth / (format.width * format.height)))
                        / 1000.0);
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        callEvents.onCaptureFormatChange(width, height, framerate);
    }
}
