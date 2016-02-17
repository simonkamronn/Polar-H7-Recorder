/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.compute.dtu.hrv;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import dk.compute.dtu.hrv.ntp.SntpClient;
import dk.compute.dtu.hrv.sensor.BleSensor;
import dk.compute.dtu.hrv.sensor.BleSensors;
import dk.compute.dtu.hrv.storage.SimpleStorageWorker;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BleMultipleDevicesService extends Service {
    private final static String TAG = BleMultipleDevicesService.class.getSimpleName();

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter adapter;
    private int connectionState = STATE_DISCONNECTED;
    private HashMap<String, BluetoothGatt> deviceMap = new HashMap<>();

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    private final static String INTENT_PREFIX = BleMultipleDevicesService.class.getPackage().getName();
    public final static String ACTION_GATT_CONNECTED = INTENT_PREFIX+".ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED = INTENT_PREFIX+".ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED = INTENT_PREFIX+".ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE = INTENT_PREFIX+".ACTION_DATA_AVAILABLE";
    public final static String EXTRA_SERVICE_UUID = INTENT_PREFIX+".EXTRA_SERVICE_UUID";
    public final static String EXTRA_CHARACTERISTIC_UUID = INTENT_PREFIX+".EXTRA_CHARACTERISTIC_UUI";
    public final static String EXTRA_DATA = INTENT_PREFIX+".EXTRA_DATA";
    public final static String EXTRA_TEXT = INTENT_PREFIX+".EXTRA_TEXT";

    // Storage
    private Map<String, Handler> storageHandlers = new HashMap<>();
    private boolean store_data = true;
    private boolean file_open = false;
    Looper looper = null;

    // Synchronisation
    private long time_offset = 0;

    // Implements callback methods for GATT events that the app cares about.
    // For example, connection change and services discovered.
    public class BluetoothGattCallbackExecutor extends BluetoothGattExecutor {
        String deviceAddress;
        public BluetoothGattCallbackExecutor(String deviceAddress) {
            this.deviceAddress = deviceAddress;
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                connectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT server.");

                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" +
                        gatt.discoverServices());

                // Setup storage class
                try {
                    SimpleStorageWorker storageWorker = new SimpleStorageWorker(getApplicationContext());
                    // Create a Handler and give it the worker instance to handle the messages
                    storageHandlers.put(deviceAddress, new Handler(looper, storageWorker));
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (store_data)
                    openFile(deviceAddress);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                connectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);

                // Close files
                if (store_data)
                    closeFile(deviceAddress);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            super.onCharacteristicRead(gatt, characteristic, status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                final BleSensor<?> sensor = BleSensors.getSensor(characteristic.getService().getUuid().toString());
                if (sensor != null) {
                    if (sensor.onCharacteristicRead(characteristic)) {
                        return;
                    }
                }
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic, deviceAddress);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);

            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic, deviceAddress);
        }
    }

    // Construct an executor for generic GATT actions
    BluetoothGattCallbackExecutor executor = new BluetoothGattCallbackExecutor("Default");

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic,
                                 final String deviceAddress) {
        final Intent intent = new Intent(action);
        intent.putExtra(EXTRA_SERVICE_UUID, characteristic.getService().getUuid().toString());
        intent.putExtra(EXTRA_CHARACTERISTIC_UUID, characteristic.getUuid().toString());

        final BleSensor<?> sensor = BleSensors.getSensor(characteristic.getService().getUuid().toString());
        if (sensor != null) {
            sensor.onCharacteristicChanged(characteristic);
            final String text = sensor.getDataString();
            intent.putExtra(EXTRA_TEXT, text);
            sendBroadcast(intent);

            Log.d(TAG, text);
            storeData(deviceAddress, (int[]) sensor.getData());
        } else {
            // For all other profiles, writes the data formatted in HEX.
            final byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                final StringBuilder stringBuilder = new StringBuilder(data.length);
                for (byte byteChar : data)
                    stringBuilder.append(String.format("%02X ", byteChar));
                intent.putExtra(EXTRA_TEXT, new String(data) + "\n" + stringBuilder.toString());
            }
        }
        sendBroadcast(intent);
    }

    public class LocalBinder extends Binder {
        public BleMultipleDevicesService getService() {
            return BleMultipleDevicesService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    /**
	 * Enables or disables notification on a give characteristic.
	 *
	 * @param sensor
	 * @param enabled If true, enable notification.  False otherwise.
	 */
	public void enableSensor(BleSensor<?> sensor, boolean enabled) {
	    if (sensor == null) {
            Log.d(TAG, "Sensor is null");
            return;
        }

	    if (adapter == null || deviceMap.isEmpty()) {
	        Log.w(TAG, "BluetoothAdapter not initialized");
	        return;
	    }

        executor.enable(sensor, enabled);
        Log.d(TAG, String.format("Number of devices connected: %d", deviceMap.size()));
        for (String address: deviceMap.keySet()){
            BluetoothGatt gatt = deviceMap.get(address);
            executor.execute(gatt);
            Log.d(TAG, "Enabling heart rate for: " + address);
        }

	}

	private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (bluetoothManager == null) {
            bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        if (adapter == null) {
            adapter = bluetoothManager.getAdapter();
            if (adapter == null) {
                Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
                return false;
            }
        }

        if (looper == null) {
            // New handler thread for storage
            HandlerThread handlerThread = new HandlerThread("storageThread");
            handlerThread.start();
            looper = handlerThread.getLooper();
        }

        if (time_offset == 0)
            new getSynchronizationOffset().execute("dk.pool.ntp.org");

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(String address) {
        if (adapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.

        if (deviceMap.containsKey(address)) {
            Log.d(TAG, "Trying to use an existing BluetoothGatt for connection.");
            if (deviceMap.get(address).connect()) {
                connectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = adapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        Log.d(TAG, "Trying to create a new connection.");
        deviceMap.put(address, device.connectGatt(this, false, new BluetoothGattCallbackExecutor(address)));

        connectionState = STATE_CONNECTING;
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (adapter == null || deviceMap.isEmpty()) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        for (BluetoothGatt gatt: deviceMap.values())
            gatt.disconnect();

        // deviceMap.clear();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (deviceMap.isEmpty()) {
            return;
        }
        for (BluetoothGatt gatt: deviceMap.values())
            gatt.close();

        deviceMap.clear();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Close files
        if (store_data) {
            for (String deviceAddress : deviceMap.keySet()) {
                closeFile(deviceAddress);
                storageHandlers.get(deviceAddress).removeCallbacksAndMessages(SimpleStorageWorker.class);
            }
        }

        // Disconnect and close connections
        disconnect();
        close();

        Log.d(TAG, "Service stopped");
    }
    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (adapter == null || deviceMap.isEmpty()) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        for (BluetoothGatt gatt: deviceMap.values())
            gatt.readCharacteristic(characteristic);
    }

    public void updateSensor(BleSensor<?> sensor) {
        if (sensor == null)
            return;

        if (adapter == null || deviceMap.isEmpty()) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }

        executor.update(sensor);
        for (BluetoothGatt gatt: deviceMap.values())
            executor.execute(gatt);
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (deviceMap.isEmpty()) return null;

        return deviceMap.values().iterator().next().getServices();
    }

    private void openFile(String deviceAddress){
        Log.d(TAG, "Creating new file for device: " + deviceAddress);
        Message msg = storageHandlers.get(deviceAddress).obtainMessage(SimpleStorageWorker.MSG_OPEN);
        Bundle b = new Bundle();
        b.putString("Prefix", deviceAddress);
        msg.setData(b);
        msg.sendToTarget();
    }

    public void closeFile(String deviceAddress){
        storageHandlers.get(deviceAddress).obtainMessage(SimpleStorageWorker.MSG_CLOSE).sendToTarget();
    }

    private void storeData(String deviceAddress, int[] data){
        // Write to file
        if (store_data){
            int heart_rate = data[0];
            int[] rr = Arrays.copyOfRange(data, 1, data.length);
            long timestamp = SystemClock.elapsedRealtime() + time_offset;

            Log.d(TAG, "Writing from device: " + deviceAddress);
            Message msg = storageHandlers.get(deviceAddress).obtainMessage(SimpleStorageWorker.MSG_WRITE);
            Bundle bundle = new Bundle();
            bundle.putInt("heart_rate", heart_rate);
            bundle.putIntArray("rr", rr);
            bundle.putLong("timestamp", timestamp);
            msg.setData(bundle);
            msg.sendToTarget();
        }
    }

    private class getSynchronizationOffset extends AsyncTask<String, Void, long[]> {
        @Override
        protected long[] doInBackground(String... urls) {
            SntpClient client = new SntpClient();
            int timeout = 60*1000;
            if (client.requestTime(urls[0], timeout)) {
                long[] result = new long[2];
                result[0] = client.getNtpTime();
                result[1] = client.getNtpTimeReference();
                return result;
            }
            return null;
        }

        @Override
        protected void onPostExecute(long[] result) {
            super.onPostExecute(result);
            if (result == null)
                Log.d(TAG, "NTP time is null");
            else {
                long ntpTime = result[0];
                long ntpTimeRef = result[1];
                time_offset = ntpTime - ntpTimeRef;
                long now = time_offset + SystemClock.elapsedRealtime();
                Log.d(TAG, String.format("NTP time: %d\nNTP ref: %d\nNow: %d\nOffset: %d",
                        ntpTime, ntpTimeRef, now, time_offset));
            }
        }
    }
}
