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

package com.example.android.bluetoothlegatt;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import org.apache.http.util.EncodingUtils;
import org.apache.http.util.ExceptionUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import android.os.Handler;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;

    private ArrayList<byte[]> cacheData2 = new ArrayList<>();
    private long cacheTime = 0;
    private String mStandardBle = null;
    Handler mToastHandler = new Handler();
    Runnable mRunnable = new Runnable(){
        @Override
        public void run() {
            long currTime = System.currentTimeMillis();
            Log.i(TAG, "write cache to file! currTime="+currTime+", cacheTime="+cacheTime+", interval="+(currTime - cacheTime));
            boolean isSame = writeCacheToFile2 ();
            cacheData2.clear();
            final Intent intent = new Intent(ACTION_DATA_AVAILABLE);
            intent.putExtra(EXTRA_DATA_SAME, isSame);
            intent.putExtra(EXTRA_DATA, "");
            sendBroadcast(intent);
        }
    };
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";
    public final static String EXTRA_DATA_SAME =
            "com.example.bluetooth.le.EXTRA_DATA_SAME";

    public final static UUID UUID_HEART_RATE_MEASUREMENT =
            UUID.fromString(SampleGattAttributes.HEART_RATE_MEASUREMENT);

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" +
                        mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        // This is special handling for the Heart Rate Measurement profile.  Data parsing is
        // carried out as per profile specifications:
        // http://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.heart_rate_measurement.xml
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            int flag = characteristic.getProperties();
            int format = -1;
            if ((flag & 0x01) != 0) {
                format = BluetoothGattCharacteristic.FORMAT_UINT16;
                Log.d(TAG, "Heart rate format UINT16.");
            } else {
                format = BluetoothGattCharacteristic.FORMAT_UINT8;
                Log.d(TAG, "Heart rate format UINT8.");
            }
            final int heartRate = characteristic.getIntValue(format, 1);
            Log.d(TAG, String.format("Received heart rate: %d", heartRate));
            intent.putExtra(EXTRA_DATA, String.valueOf(heartRate));
        } else {
            // For all other profiles, writes the data formatted in HEX.
            final byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                //// print data received.
                final StringBuilder stringBuilder = new StringBuilder(data.length);
                for(byte byteChar : data)
                    stringBuilder.append(String.format("%02X ", byteChar));
                //Log.i(TAG, "windsome1:"+ stringBuilder.toString());
                //Log.i(TAG, "windsome2:"+ new String(data));
                intent.putExtra(EXTRA_DATA, new String(data) + "\n" + stringBuilder.toString());

                //// write to file.
                long currTime = System.currentTimeMillis();
                /*if ((currTime - cacheTime) > 500) {
                    Log.i(TAG, "write cache to file! currTime="+currTime+", cacheTime="+cacheTime+", interval="+(currTime - cacheTime));
                    boolean isSame = writeCacheToFile2 ();
                    cacheData2.clear();
                    intent.putExtra(EXTRA_DATA_SAME, isSame);
                }*/
                cacheData2.add(data);
                cacheTime = currTime;
                mToastHandler.removeCallbacks(mRunnable);
                mToastHandler.postDelayed(mRunnable, 300);

            }
        }
        sendBroadcast(intent);
    }

    private boolean writeCacheToFile ()
    {
        /*StringBuilder hexBuilder = new StringBuilder();
        for (int i = 0; i < cacheData2.size(); i++) {
            byte[] item = cacheData2.get(i);
            for(byte byteChar : item)
                hexBuilder.append(String.format("%02X ", byteChar));
            //?? add \n to line end??
            hexBuilder.append("\n");
        }
        //?? add 2 \n to message end.
        hexBuilder.append("\n\n");*/

        // save cache data, and clear.

        if(Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)){
            File sdCardDir = Environment.getExternalStorageDirectory();//获取SDCard目录
            try {
                File saveFile1 = new File(sdCardDir, "ble_byte.txt");
                File saveFile2 = new File(sdCardDir, "ble_asci.txt");
                FileOutputStream fout1 = new FileOutputStream(saveFile1, true);
                FileOutputStream fout2 = new FileOutputStream(saveFile2, true);
                //FileOutputStream fout1 = openFileOutput("out_byte", MODE_APPEND);
                //FileOutputStream fout2 = openFileOutput("out_asci", MODE_APPEND);
                for (int i = 0; i < cacheData2.size(); i++) {
                    byte[] item = cacheData2.get(i);
                    StringBuilder hexBuilder = new StringBuilder();
                    for(byte byteChar : item)
                        hexBuilder.append(String.format("%02X ", byteChar));
                    fout1.write(hexBuilder.toString().getBytes());
                    fout1.write("\n".getBytes());
                    fout2.write(item);
                }
                fout1.write("\n\n".getBytes());
                fout2.write("\n\n".getBytes());
                fout1.close();
                fout2.close();
            } catch(Exception e){
                Log.e(TAG, "error:"+e);
                e.printStackTrace();
                return false;
            }
        } else {
            Log.e(TAG, "sd card fail!");
        }

        return true;
    }
    private boolean writeCacheToFile2 ()
    {
        if(Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)){
            File sdCardDir = Environment.getExternalStorageDirectory();//获取SDCard目录
            try {
                boolean isSame = false;
                //File saveFile1 = new File(sdCardDir, "ble_byte.txt");
                File saveFile2 = new File(sdCardDir, "ble_asci.txt");
                //FileOutputStream fout1 = new FileOutputStream(saveFile1, true);
                FileOutputStream fout2 = new FileOutputStream(saveFile2, true);
                //FileOutputStream fout1 = openFileOutput("out_byte", MODE_APPEND);
                //FileOutputStream fout2 = openFileOutput("out_asci", MODE_APPEND);
                StringBuilder hexBuilder = new StringBuilder();
                for (int i = 0; i < cacheData2.size(); i++) {
                    byte[] item = cacheData2.get(i);
                    for(byte byteChar : item)
                        hexBuilder.append(String.format("%02X ", byteChar));
                    //?? add \n to line end??
                    hexBuilder.append("\n");
                    fout2.write(item);
                }
                //fout1.write(hexBuilder.toString().getBytes());
                fout2.write("\nBinary:\n".getBytes());
                fout2.write(hexBuilder.toString().getBytes());
                // check with standard cardiochek_ble file.
                if (mStandardBle == null) {
                    mStandardBle = getStandardFile ();
                    if (mStandardBle != null)
                        mStandardBle = mStandardBle.replaceAll("[\\t\\n\\r]", "");
                }
                if (mStandardBle != null && !mStandardBle.equals("")) {
                    // compare file.
                    String hexStr = hexBuilder.toString();
                    hexStr = hexStr.replaceAll("[\\t\\n\\r]", "");
                    if (mStandardBle.equalsIgnoreCase(hexStr)) {
                        fout2.write("\nSAME AS cardiochek_ble\n".getBytes());
                        Log.i(TAG, "SAME AS cardiochek_ble");
                        isSame = true;
                    } else {
                        fout2.write("\nDIFF WITH cardiochek_ble\n".getBytes());
                        Log.i(TAG, "nDIFF WITH cardiochek_ble");
                    }
                } else {
                    fout2.write("\nNO cardiochek_ble\n".getBytes());
                    Log.i(TAG, "NO cardiochek_ble");
                }
                //fout1.write("\n\n".getBytes());
                fout2.write("\n\n".getBytes());

                //fout1.close();
                fout2.close();
                return isSame;
            } catch(Exception e){
                Log.e(TAG, "error:"+e);
                e.printStackTrace();
                return false;
            }
        } else {
            Log.e(TAG, "sd card fail!");
        }

        return false;
    }

    private String getStandardFile() {
        String sdcardPath = "";
        if(Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            File sdCardDir = Environment.getExternalStorageDirectory();//获取SDCard目录
            sdcardPath = sdCardDir.getPath();
        }
        String standardFile = findFile(sdcardPath, "cardiochek_ble");
        if (standardFile == null) {
            standardFile = findFile(sdcardPath + "/Downloads", "cardiochek_ble");
        }
        if (standardFile == null) {
            Log.w(TAG, "no cardiochek_ble file");
            return null;
        }

        try {
            File file = new File(standardFile);
            FileInputStream fis = new FileInputStream(file);
            int length = fis.available();
            byte[] buffer = new byte[length];
            fis.read(buffer);
            String res = EncodingUtils.getString(buffer, "UTF-8");
            fis.close();
            return res;
        } catch (Exception e) {
            Log.e(TAG, "read cardiochek_ble fail!");
        }
        return null;
    }


    private String findFile (String folder, String keyword) {
        File file = new File(folder);
        if (!file.isDirectory()) {
            return null;
        }
        if (keyword.equals("")) {
            return null;
        }
        File[] files = new File(file.getPath()).listFiles();

        for (File f : files) {
            if (f.getName().indexOf(keyword) >= 0) {
                return f.getPath();
            }
        }
        return null;
    }

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
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

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

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
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }

        if (!mBluetoothGatt.setCharacteristicNotification(characteristic, enabled)) {
            Log.e(TAG, "setCharacteristicNotification fail! enabled="+enabled);
        }

        if ((UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")).equals(characteristic.getUuid())) {
            //mBluetoothGatt.writeCharacteristic(characteristic);
            List<BluetoothGattDescriptor> descs = characteristic.getDescriptors();
            for (int i = 0; i < descs.size(); i++) {
                BluetoothGattDescriptor desc = descs.get(i);
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                Log.d(TAG, "writeDescriptor notify, uuid=" + desc.getUuid().toString());
                mBluetoothGatt.writeDescriptor(desc);
            }
            //BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            //descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            //mBluetoothGatt.writeDescriptor(descriptor);
        }

        // This is specific to Heart Rate Measurement.
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }
}
