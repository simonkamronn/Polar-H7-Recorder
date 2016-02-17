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

import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;

import dk.compute.dtu.hrv.adapters.BleServicesAdapter;
import dk.compute.dtu.hrv.adapters.BleServicesAdapter.OnServiceItemClickListener;
import dk.compute.dtu.hrv.sensor.BleHeartRateSensor;
import dk.compute.dtu.hrv.sensor.BleSensor;
import dk.compute.dtu.hrv.sensor.BleSensors;

import java.util.List;

/**
 * For a given BLE device, this Activity provides the user interface to connect, display data,
 * and display GATT services and characteristics supported by the device.  The Activity
 * communicates with {@code BleService}, which in turn interacts with the
 * Bluetooth LE API.
 */
public class MultipleDeviceServicesActivity extends Activity {
    private final static String TAG = MultipleDeviceServicesActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
    public static final String START_SCAN_ACTION = "START_SCAN";

    private TextView connectionState;
    private TextView dataField;
    private TextView heartRateField;
    private TextView intervalField;
    private TextView deviceAddress;
    private Button demoButton;

    private ExpandableListView gattServicesList;
    private BleServicesAdapter gattServiceAdapter;

    private BleMultipleDevicesService bleService;
    private boolean isConnected = false;

    private BleSensor<?> activeSensor;
    private BleSensor<?> heartRateSensor;

	private OnServiceItemClickListener serviceListener;

    // Code to manage Service lifecycle.
    private final ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            bleService = ((BleMultipleDevicesService.LocalBinder) service).getService();
            if (!bleService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
            } else {
                Log.d(TAG, "Connected and BLE working");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            bleService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver gattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BleService.ACTION_GATT_CONNECTED.equals(action)) {
                isConnected = true;
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
            } else if (BleService.ACTION_GATT_DISCONNECTED.equals(action)) {
                isConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                clearUI();
            } else if (BleService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                List<BluetoothGattService> gattServices = bleService.getSupportedGattServices();
                if (gattServices != null)
                    gattServiceAdapter = new BleServicesAdapter(getApplicationContext(), gattServices);
				enableHeartRateSensor();
            } else if (BleService.ACTION_DATA_AVAILABLE.equals(action)) {
				displayData(intent.getStringExtra(BleService.EXTRA_SERVICE_UUID), intent.getStringExtra(BleService.EXTRA_TEXT));

            }
        }
    };

    // If a given GATT characteristic is selected, check for supported features.  This sample
    // demonstrates 'Read' and 'Notify' features.  See
    // http://d.android.com/reference/android/bluetooth/BluetoothGatt.html for the complete
    // list of supported characteristic features.
    private final ExpandableListView.OnChildClickListener servicesListClickListner =
            new ExpandableListView.OnChildClickListener() {
                @Override
                public boolean onChildClick(ExpandableListView parent, View v, int groupPosition,
                                            int childPosition, long id) {
                    if (gattServiceAdapter == null)
                        return false;

                    final BluetoothGattCharacteristic characteristic = gattServiceAdapter.getChild(groupPosition, childPosition);
                    final BleSensor<?> sensor = BleSensors.getSensor(characteristic.getService().getUuid().toString());

                    if (activeSensor != null)
                        bleService.enableSensor(activeSensor, false);

                    if (sensor == null) {
                        bleService.readCharacteristic(characteristic);
                        return true;
                    }

                    if (sensor == activeSensor)
                        return true;

                    activeSensor = sensor;
                    bleService.enableSensor(sensor, true);
                    return true;
                }
            };

    private void clearUI() {
        gattServicesList.setAdapter((SimpleExpandableListAdapter) null);
        dataField.setText(R.string.no_data);
		heartRateField.setText(R.string.no_data);
		intervalField.setText(R.string.no_data);
    }

	public void setServiceListener(OnServiceItemClickListener listener) {
		this.serviceListener = listener;
	}
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);

//        final Intent intent = getIntent();
//        deviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
//        deviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        // Sets up UI references.
        deviceAddress = (TextView) findViewById(R.id.device_address);
        connectionState = (TextView) findViewById(R.id.connection_state);
        dataField = (TextView) findViewById(R.id.data_value);
		heartRateField = (TextView) findViewById(R.id.heartrate_value);

        startService(new Intent(this, BleMultipleDevicesService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(gattUpdateReceiver, makeGattUpdateIntentFilter());

        final Intent gattServiceIntent = new Intent(this, BleMultipleDevicesService.class);
        bindService(gattServiceIntent, serviceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(gattUpdateReceiver);
        unbindService(serviceConnection);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Log.d(TAG, "Stopping service");
        stopService(new Intent(this, BleMultipleDevicesService.class));
        bleService = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        menu.findItem(R.id.menu_scan).setVisible(true);
        if (isConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_connect:
                return true;
            case R.id.menu_disconnect:
                bleService.disconnect();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
            case R.id.menu_scan:
                startScanning();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectionState.setText(resourceId);
            }
        });
    }

    private void displayData(String uuid, String data) {
		if (data != null) {
			if (uuid.equals(BleHeartRateSensor.getServiceUUIDString())) {
				heartRateField.setText(data);
			} else {
				dataField.setText(data);
			}
		}
    }

	private boolean enableHeartRateSensor() {
        Log.d(TAG, "Trying to enable heart rate sensor");
		if (gattServiceAdapter == null)
			return false;

		final BluetoothGattCharacteristic characteristic = gattServiceAdapter
				.getHeartRateCharacteristic();
		Log.d(TAG,"characteristic: " + characteristic);
		final BleSensor<?> sensor = BleSensors.getSensor(characteristic
				.getService()
				.getUuid()
				.toString());

		if (heartRateSensor != null)
			bleService.enableSensor(heartRateSensor, false);

		if (sensor == null) {
			bleService.readCharacteristic(characteristic);
			return true;
		}

		if (sensor == heartRateSensor)
			return true;

		heartRateSensor = sensor;
		bleService.enableSensor(sensor, true);

		return true;
	}

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BleService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BleService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BleService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BleService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    private void startScanning(){
        Intent startScanIntent = new Intent(this, MultipleDeviceScanActivity.class);
        startActivityForResult(startScanIntent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1){
            if (resultCode == Activity.RESULT_OK){
                String deviceName = data.getStringExtra(EXTRAS_DEVICE_NAME);
                String deviceAddress = data.getStringExtra(EXTRAS_DEVICE_ADDRESS);
                Log.d(TAG, "Connecting to: " + deviceName + " at " + deviceAddress);
                bleService.connect(deviceAddress);
            }
        }
    }
}
