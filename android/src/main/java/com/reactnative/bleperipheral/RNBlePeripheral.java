package com.reactnative.peripheral;

import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.Promise;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import java.util.Map;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.UUID;
import java.util.Random;
import java.util.Date;
import android.util.Log;
import android.content.pm.PackageManager;
import android.content.Context;
import android.os.Handler;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.bluetooth.BluetoothStatusCodes;
import android.os.ParcelUuid;
import android.util.Base64;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.lang.reflect.Method;
import java.nio.file.ProviderMismatchException;
import java.io.UnsupportedEncodingException;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.lang.reflect.Method;

public class RNBlePeripheral extends ReactContextBaseJavaModule {
    // Constants
    public static final String READ_REQUEST = "BlePeripheral:ReadRequest";
    public static final String STATE_CHANGED = "BlePeripheral:StateChanged";
    public static final String SUBSCRIBED = "BlePeripheral:Subscribed";
    public static final String UNSUBSCRIBED = "BlePeripheral:Unsubscribed";
    public static final String WRITE_REQUEST = "BlePeripheral:WriteRequest";
    public static final String LOGGER = "BlePeripheral:Logger";

    private Context context;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBluetoothAdvertiser;
    private AdvertiseCallback advertiseCallback;
    private AdvertiseSettings advertiseSettings;
    private AdvertiseData advertiseData;
    private AdvertiseData scanResponse;

    private final UUID descriptor_uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private MutableLiveData _deviceConnection = new MutableLiveData<Boolean>();
    private LiveData deviceConnection = (LiveData<Boolean>) _deviceConnection;

    private BluetoothGattServerCallback gattServerCallback;
    private BluetoothGatt gattClient;
    private BluetoothGattCallback gattClientCallback;

    private ReactContext ctx;
    private ReactContext RCtx;
    
    private class State {
        boolean isConnected = false;
        boolean isAdvertising = false;
        BluetoothDevice connectedDevice = null;
        BluetoothGatt gatt = null;
        BluetoothGattServer gattServer;
        Callback callback = null;
        UUID SERVICE_UUID = null;
        int negociatedMtu = 23;
        boolean isComplete = true;
        BluetoothManager bluetoothManager;
    }

    private State state;

    public RNBlePeripheral(ReactApplicationContext reactContext) {
        super(reactContext);
        this.ctx = reactContext;
        this.context = reactContext.getApplicationContext();
        this.mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.gattServerCallback = null;
        this.gattClient = null;
        this.gattClientCallback = null;
        this.state = new State();
    }

    @Override
    public String getName() {
        return "RNBlePeripheral";
    }

    private void sendEvent(String eventName, WritableMap params) {
        if(this.ctx != null){
            this.ctx
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
        }
    }

    private void __log(String message){
        WritableMap eventParams = Arguments.createMap();
        eventParams.putString("message", message);
        sendEvent(LOGGER, eventParams);
    }

    public class GattServerCallback extends BluetoothGattServerCallback {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            boolean isSuccess = status == BluetoothGatt.GATT_SUCCESS;
            boolean isConnected = newState == BluetoothProfile.STATE_CONNECTED;
            if (isSuccess && isConnected) {
                __log("Connected to device ");
                state.isConnected = true;
                state.connectedDevice = device;
                setCurrentDeviceConnected(device);

                WritableMap eventParams = Arguments.createMap();
                eventParams.putString("message", "device is connected");
                sendEvent(STATE_CHANGED, eventParams);

            } else {
                _deviceConnection.postValue(false);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            __log("Characteristic read request");
            WritableMap eventParams = Arguments.createMap();
            eventParams.putString("requestId", Integer.toString(requestId));
            eventParams.putString("characteristicUuid", characteristic.getUuid().toString());
            eventParams.putInt("offset", offset);

            sendEvent(READ_REQUEST, eventParams);
            state.gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, characteristic.getValue());
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
            WritableMap eventParams = Arguments.createMap();
            eventParams.putString("requestId", Integer.toString(requestId));
            eventParams.putString("characteristicUuid", characteristic.getUuid().toString());
            eventParams.putString("value", Base64.encodeToString(value, Base64.NO_WRAP));
            eventParams.putInt("offset", offset);
            sendEvent(WRITE_REQUEST, eventParams);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);
            
            WritableMap eventParams = Arguments.createMap();
            eventParams.putString("characteristicUuid", descriptor.getUuid().toString());
            eventParams.putString("centralUuid", device.toString());
            if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                sendEvent(SUBSCRIBED, eventParams);
            } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                sendEvent(UNSUBSCRIBED, eventParams);
            }
            state.gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status){
            super.onNotificationSent(device, status);
            try {
                __log( "Notification sent, " + status);
                state.isComplete = true;
            } catch(Exception e) {
                __log( "Error catch onNotificationSent");
            }
        }

        /*** */
        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu){
            super.onMtuChanged(device, mtu);

            __log("Negociated Mtu: " + mtu);
            state.negociatedMtu = mtu;
        }

    }

    public class GattClientCallback extends BluetoothGattCallback {

        @Override 
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            __log("Gatt Client on connection state change --- ");
            boolean isSuccess = status == BluetoothGatt.GATT_SUCCESS;
            boolean isConnected = newState == BluetoothProfile.STATE_CONNECTED;
            if (isSuccess && isConnected) {
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt discoveredGatt, int status) {
            super.onServicesDiscovered(discoveredGatt, status);
            __log("Gatt Client on service discovered --- ");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                state.gatt = discoveredGatt;
                BluetoothGattService service = state.gatt.getService(state.SERVICE_UUID);
            }
        }

    }

    private int getPropertyFlags(List<String> properties) {
        int propertyFlags = 0;

        for (String property : properties) {
            switch (property) {
                case "read":
                    propertyFlags |= BluetoothGattCharacteristic.PROPERTY_READ;
                    break;
                case "writeWithoutResponse":
                    propertyFlags |= BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
                    break;
                case "write":
                    propertyFlags |= BluetoothGattCharacteristic.PROPERTY_WRITE;
                    break;
                case "notify":
                    propertyFlags |= BluetoothGattCharacteristic.PROPERTY_NOTIFY;
                    break;
                // Add other properties as needed
                default:
                    break;
            }
        }

        return propertyFlags;
    }

    private int getPermissionFlags(List<String> permissions) {
        int permissionFlags = 0;
        for (String permission : permissions) {
            switch (permission) {
                case "readable":
                    permissionFlags |= BluetoothGattCharacteristic.PERMISSION_READ;
                    break;
                case "writeable":
                    permissionFlags |= BluetoothGattCharacteristic.PERMISSION_WRITE;
                    break;
                // Add other properties as needed
                default:
                    break;
            }
        }

        return permissionFlags;
    }

    private void setCurrentDeviceConnected(BluetoothDevice device) {
        BluetoothDevice currentDevice = device;
        _deviceConnection.postValue(true);
        GattClientCallback gattClientCallback = new GattClientCallback();
        this.gattClient = device.connectGatt(this.context, false, gattClientCallback);
        __log("Connecting to device.....");
    }

    @ReactMethod
    public void addListener(String eventName) {

    }

    @ReactMethod
    public void removeListeners(Integer count) {

    }

    @ReactMethod
    public void testLog(String logMessage) {
        __log(logMessage);
    }

    @ReactMethod
    public void getNegociatedMtu(Promise promise) {
        __log("getMtu called: " + state.negociatedMtu);
        promise.resolve(state.negociatedMtu);
    }

    @ReactMethod
    public void getState(Promise promise) {
        if (mBluetoothAdapter == null) {
            promise.resolve("unsupported");
            return;
        }

        int state = mBluetoothAdapter.getState();
        String stateString;

        switch (state) {
            case BluetoothAdapter.STATE_ON:
                stateString = "poweredOn";
                break;
            case BluetoothAdapter.STATE_OFF:
                stateString = "poweredOff";
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
                stateString = "turningOn";
                break;
            case BluetoothAdapter.STATE_TURNING_OFF:
                stateString = "turningOff";
                break;
            default:
                stateString = "unknown";
                break;
        }

        promise.resolve(stateString);
    }

    @ReactMethod
    public void respond(String requestId, String status, String value, Promise promise){
        try {
            int reqId = Integer.parseInt(requestId);
            int stat = BluetoothGatt.GATT_FAILURE;
            if(status.equals("success")){
                stat = BluetoothGatt.GATT_SUCCESS;
            }
            if(value != null){
                byte[] val = Base64.decode(value, Base64.DEFAULT);
                state.gattServer.sendResponse(state.connectedDevice, reqId, stat, 0, val);
            } else {
                state.gattServer.sendResponse(state.connectedDevice, reqId, stat, 0, null);
            }
            promise.resolve(null);
        } catch(Exception ex) {
            promise.reject(ex);
        }
    }

    @ReactMethod
    public void addService(ReadableMap serviceMap, Promise promise) {
        try{
            GattServerCallback gattServerCallback = new GattServerCallback();
            state.bluetoothManager = (BluetoothManager) this.context.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
            __log("setupGattServer");
            state.gattServer = state.bluetoothManager.openGattServer(this.context, gattServerCallback);
            if (state.gattServer == null) {
                __log("COULD NOT OPEN GATT SERVER");
            } else {

                __log("Setting up Service");
                // Extract properties from the ReadableMap
                String uuidString = serviceMap.getString("uuid");
                UUID serviceUuid = UUID.fromString(uuidString);
                BluetoothGattService bluetoothGattService = new BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY);

                // Handle characteristics
                if (serviceMap.hasKey("characteristics")) {
                    ReadableArray characteristicsArray = serviceMap.getArray("characteristics");
                    for (int i = 0; i < characteristicsArray.size(); i++) {
                        ReadableMap characteristicMap = characteristicsArray.getMap(i);
                        String characteristicUuidString = characteristicMap.getString("uuid");
                        String characteristicValue = null;
                        if (characteristicMap.hasKey("value")){
                            characteristicValue = characteristicMap.getString("value");
                        }
                        ReadableArray permissionsArray = characteristicMap.hasKey("permissions") ? characteristicMap.getArray("permissions") : null;
                        ReadableArray propertiesArray = characteristicMap.hasKey("properties") ? characteristicMap.getArray("properties") : null;

                        List<String> permissions = new ArrayList<>();
                        List<String> properties = new ArrayList<>();

                        boolean isNotificationEnabled = false;

                        if (permissionsArray != null) {
                            for (int j = 0; j < permissionsArray.size(); j++) {
                                permissions.add(permissionsArray.getString(j));
                            }
                        }

                        if (propertiesArray != null) {
                            for (int j = 0; j < propertiesArray.size(); j++) {
                                properties.add(propertiesArray.getString(j));
                                if(propertiesArray.getString(j) != null && propertiesArray.getString(j).equals("notify")){
                                    isNotificationEnabled = true;
                                }
                            }
                        }

                        int propertyFlags = getPropertyFlags(properties);
                        int permissionFlags = getPermissionFlags(permissions);

                        BluetoothGattCharacteristic bluetoothGattCharacteristic = new BluetoothGattCharacteristic(
                            UUID.fromString(characteristicUuidString),
                            propertyFlags,
                            permissionFlags);
                        
                        if (characteristicValue != null) {
                            // Convert base64 string to byte array
                            byte[] valueBytes = Base64.decode(characteristicValue, Base64.DEFAULT);
                            bluetoothGattCharacteristic.setValue(valueBytes);
                        }
                        if (isNotificationEnabled) {
                            bluetoothGattCharacteristic.addDescriptor(new BluetoothGattDescriptor(
                                descriptor_uuid,
                                BluetoothGattDescriptor.PERMISSION_WRITE
                            ));
                        }

                        bluetoothGattService.addCharacteristic(bluetoothGattCharacteristic);
                    }
                }
                state.gattServer.addService(bluetoothGattService);
                state.SERVICE_UUID = serviceUuid;
                __log("setupGattServer finished");
            }
            promise.resolve(null);
        }
        catch(Exception ex){
            __log("Error setting up service");
            __log(ex.getMessage());
            promise.reject(ex);
        }
    }

    @ReactMethod
    public void removeAllServices(Promise promise) {
        try {
            if (state.gattServer != null) {
                state.gattServer.clearServices();
                promise.resolve(null);
            } else {
                promise.reject("No Gatt Server found");
            }
        } catch (Exception ex) {
            promise.reject(ex);
        }
    }

    @ReactMethod
    public void startAdvertising(ReadableMap data, Promise promise) {
        __log("Start advertisement called");
        // Retrieve the device name and service UUIDs from the input object
        String deviceName = data.getString("name");
        ReadableArray serviceUuidsArray = data.getArray("serviceUuids");

        // Set up the advertisement settings
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();

        // Build the advertisement data
        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder()
                // .setIncludeDeviceName(true); // Include the device name in the advertisement
                                                // NOTE: This can fail if device name is too long
                .setIncludeTxPowerLevel(true); // Optionally include TX power level

        // Add service UUIDs to the advertisement data
        List<ParcelUuid> uuids = new ArrayList<>();
        for (int i = 0; i < serviceUuidsArray.size(); i++) {
            String uuidString = serviceUuidsArray.getString(i);
            UUID uuid = UUID.fromString(uuidString);
            dataBuilder.addServiceUuid(new ParcelUuid(uuid));
        }

        AdvertiseData dataToAdvertise = dataBuilder.build();

        // Create the advertise callback
        this.advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                state.isAdvertising = true;
                // Successfully started advertising
                promise.resolve("Advertisement started successfully");
                __log("Advertisement started correctly");
            }

            @Override
            public void onStartFailure(int errorCode) {
                state.isAdvertising = false;
                // Failed to start advertising
                promise.reject("AdvertisementError", "Failed to start advertisement: " + errorCode);
            }
        };

        // Start advertising
        this.mBluetoothAdvertiser = this.mBluetoothAdapter.getBluetoothLeAdvertiser();
        this.mBluetoothAdvertiser.startAdvertising(settings, dataToAdvertise, this.advertiseCallback);
        
        __log("StartAdvertising done ....");
    }

    @ReactMethod
    private void stopAdvertising(Promise promise) {
        __log("Stop advertising called");
        if(this.mBluetoothAdvertiser != null && this.advertiseCallback != null) {
            this.mBluetoothAdvertiser.stopAdvertising(this.advertiseCallback);
            this.advertiseCallback = null;
            state.isAdvertising = false;
            promise.resolve(null);
        }
        else{
            promise.reject("No advertisement started");
        }
    }

    @ReactMethod
    public void isAdvertising(Promise promise) {
        // Resolve the promise with the current advertising state
        promise.resolve(state.isAdvertising);
    }

    @ReactMethod
    public void getIsDeviceConnected(Callback callback, Callback callback2) {
        callback.invoke(state.isConnected);
    }
    @ReactMethod
    public void notify(String uuid, String message, Promise promise) {
        __log("Notifying from native module");
        BluetoothGattService srvc = state.gattServer.getService(state.SERVICE_UUID);
        if (srvc == null) {
            __log("Service not found");
            promise.reject("Service not found");
            return;
        }
        BluetoothGattCharacteristic ch = srvc.getCharacteristic(UUID.fromString(uuid));
        if(ch == null){
            __log("Characteristic not found");
            promise.reject("Characteristic not found");
            return;
        }
        try {
            while(!state.isComplete){

            }
            if(message == null){
                promise.reject("Message is null");
            }
            __log("Message to send: " + message);
            byte[] value = Base64.decode(message, Base64.DEFAULT);
            ch.setValue(value);
            state.isComplete = false;
            boolean success = state.gattServer.notifyCharacteristicChanged(state.connectedDevice, ch, false);
            promise.resolve(success);
        } catch(Exception ex) {
            __log("Error sending notification.....");
            promise.reject(ex);
        }
    }
}
