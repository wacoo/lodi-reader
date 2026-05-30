package com.gugesoft.lodibookreader;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class ShakeDetector implements SensorEventListener {

    private float shakeThreshold = 12.0f; // sensitivity
    private static final int SHAKE_INTERVAL_MS = 1000;

    private long lastShakeTime = 0;

    private Runnable onShakeCallback;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    public ShakeDetector(Context context, float shakeIntensity, Runnable onShakeCallback) {
        this.shakeThreshold = shakeIntensity;
        this.onShakeCallback = onShakeCallback;

        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    public void setShakeThreshold(float shakeThreshold) {
        this.shakeThreshold = shakeThreshold;
    }

    public void start() {
        if (accelerometer != null) {
            sensorManager.registerListener(
                    this,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL
            );
        }
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        float acceleration = (float) Math.sqrt(x * x + y * y + z * z);

        long currentTime = System.currentTimeMillis();

        if (acceleration > shakeThreshold) {
            if (currentTime - lastShakeTime > SHAKE_INTERVAL_MS) {

                lastShakeTime = currentTime;

                if (onShakeCallback != null) {
                    onShakeCallback.run();
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed
    }
}