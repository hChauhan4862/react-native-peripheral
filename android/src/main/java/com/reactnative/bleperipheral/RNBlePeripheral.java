package com.reactnative.bleperipheral;

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
    private final UUID descriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private ReactContext ctx;
    private Context context;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBluetoothAdvertiser;
    private BluetoothGattServerCallback gattServerCallback;
    private AdvertiseCallback advertiseCallback;


    private MutableLiveData deviceConnection = new MutableLiveData<Boolean>();


    
    private class State {
        boolean isConnected = false;
        boolean isAdvertising = false;
        BluetoothDevice connectedDevice = null;
        BluetoothGattServer gattServer;
        Callback callback = null;
        UUID serviceUuid = null;
        int negociatedMtu = 23;
        boolean isComplete = true;
        BluetoothManager bluetoothManager = null;
    }

    private void sleepWithDelay(Promise promise) {
        try {
            Thread.sleep(100); // Add a small delay to prevent busy-waiting
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            promise.reject("Thread interrupted", e);
        }
    }

    private State state = null;

    public RNBlePeripheral(ReactApplicationContext reactContext) {
        super(reactContext);
        this.ctx = reactContext;
        this.context = reactContext.getApplicationContext();
        this.reset();
    }
    
    private void reset() {
        this.mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.gattServerCallback = null;
        stopServer();
        this.state = new State();
    }

    private void stopServer() {
        if (state != null && state.gattServer != null){
            state.gattServer.close();
        }
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

    @ReactMethod
    public void addListener(String eventName) {
        // This method is required for React Native's event emitter but is not used in this module.
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // This method is required for React Native's event emitter but is not used in this module.
    }

    @ReactMethod
    public void testLog(String logMessage) {
        __log(logMessage);
    }

    @ReactMethod
    public void getNegociatedMtu(Promise promise) {
        __log("getMtu called: " + this.state.negociatedMtu);
        promise.resolve(this.state.negociatedMtu);
    }

    @ReactMethod
    public void getState(Promise promise) {
        if (this.mBluetoothAdapter == null) {
            promise.resolve("unsupported");
            return;
        }

        int stat = this.mBluetoothAdapter.getState();
        String stateString;

        switch (stat) {
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
            if(this.state.connectedDevice == null){
                promise.reject("No device connected");
                return;
            }
            int reqId = Integer.parseInt(requestId);
            int stat = BluetoothGatt.GATT_FAILURE;
            if(status.equals("success")){
                stat = BluetoothGatt.GATT_SUCCESS;
            }
            if(value != null){
                byte[] val = Base64.decode(value, Base64.DEFAULT);
                this.state.gattServer.sendResponse(this.state.connectedDevice, reqId, stat, 0, val);
            } else {
                this.state.gattServer.sendResponse(this.state.connectedDevice, reqId, stat, 0, null);
            }
            promise.resolve(null);
        } catch(Exception ex) {
            promise.reject(ex);
        }
    }
    
    @ReactMethod
    public void addService(ReadableMap serviceMap, Promise promise) {
        try {
            setupGattServer();
            if (this.state.gattServer == null) {
                __log("COULD NOT OPEN GATT SERVER");
                promise.reject("GattServerError", "Could not open GATT server");
                return;
            }
            __log("Setting up Service");
            BluetoothGattService bluetoothGattService = createBluetoothGattService(serviceMap);
            this.state.gattServer.addService(bluetoothGattService);
            this.state.serviceUuid = bluetoothGattService.getUuid();
            __log("setupGattServer finished");
            promise.resolve(null);
        } catch (Exception ex) {
            __log("Error setting up service");
            __log(ex.getMessage());
            promise.reject(ex);
        }
    }

    private void setupGattServer() {
        this.gattServerCallback = new BluetoothGattServerCallback() {
            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                super.onConnectionStateChange(device, status, newState);
                handleConnectionStateChange(device, status, newState);
            }

            @Override
            public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
                handleCharacteristicReadRequest(device, requestId, offset, characteristic);
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
                handleCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
            }

            @Override
            public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);
                handleDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);
            }

            @Override
            public void onNotificationSent(BluetoothDevice device, int status) {
                super.onNotificationSent(device, status);
                handleNotificationSent(device, status);
            }

            @Override
            public void onMtuChanged(BluetoothDevice device, int mtu) {
                super.onMtuChanged(device, mtu);
                handleMtuChanged(device, mtu);
            }
        };
        this.state.bluetoothManager = (BluetoothManager) this.context.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
        __log("setupGattServer");
        this.state.gattServer = this.state.bluetoothManager.openGattServer(this.context, this.gattServerCallback);
    }

    private BluetoothGattService createBluetoothGattService(ReadableMap serviceMap) {
        String uuidString = serviceMap.getString("uuid");
        UUID serviceUuid = UUID.fromString(uuidString);
        BluetoothGattService bluetoothGattService = new BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        if (serviceMap.hasKey("characteristics")) {
            ReadableArray characteristicsArray = serviceMap.getArray("characteristics");
            for (int i = 0; i < characteristicsArray.size(); i++) {
                ReadableMap characteristicMap = characteristicsArray.getMap(i);
                BluetoothGattCharacteristic characteristic = createBluetoothGattCharacteristic(characteristicMap);
                bluetoothGattService.addCharacteristic(characteristic);
            }
        }
        return bluetoothGattService;
    }

    private BluetoothGattCharacteristic createBluetoothGattCharacteristic(ReadableMap characteristicMap) {
        String characteristicUuidString = characteristicMap.getString("uuid");
        String characteristicValue = characteristicMap.hasKey("value") ? characteristicMap.getString("value") : null;
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
                if (propertiesArray.getString(j) != null && propertiesArray.getString(j).equals("notify")) {
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
            byte[] valueBytes = Base64.decode(characteristicValue, Base64.DEFAULT);
            bluetoothGattCharacteristic.setValue(valueBytes);
        }

        if (isNotificationEnabled) {
            bluetoothGattCharacteristic.addDescriptor(new BluetoothGattDescriptor(
                    descriptorUuid,
                    BluetoothGattDescriptor.PERMISSION_WRITE
            ));
        }

        return bluetoothGattCharacteristic;
    }

    private void handleConnectionStateChange(BluetoothDevice device, int status, int newState) {
        boolean isSuccess = status == BluetoothGatt.GATT_SUCCESS;
        boolean isConnected = newState == BluetoothProfile.STATE_CONNECTED;
        __log("OnConnectionStateChange isSuccess: " + isSuccess + " isConnected: " + isConnected);
        if (isSuccess && isConnected) {
            __log("Connected to device ");
            state.isConnected = true;
            state.connectedDevice = device;
            WritableMap eventParams = Arguments.createMap();
            eventParams.putString("message", "device is connected");
            sendEvent(STATE_CHANGED, eventParams);
        } else {
            deviceConnection.postValue(false);
        }
    }

    private void handleCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
        __log("Characteristic read request");
        WritableMap eventParams = Arguments.createMap();
        eventParams.putString("requestId", Integer.toString(requestId));
        eventParams.putString("characteristicUuid", characteristic.getUuid().toString());
        eventParams.putInt("offset", offset);
        sendEvent(READ_REQUEST, eventParams);
        state.gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, characteristic.getValue());
    }

    private void handleCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
        (void)device;
        (void)preparedWrite;
        (void)responseNeeded;

        WritableMap eventParams = Arguments.createMap();
        eventParams.putString("requestId", Integer.toString(requestId));
        eventParams.putString("characteristicUuid", characteristic.getUuid().toString());
        eventParams.putString("value", Base64.encodeToString(value, Base64.NO_WRAP));
        eventParams.putInt("offset", offset);
        sendEvent(WRITE_REQUEST, eventParams);
    }

    private void handleDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
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

    private void handleNotificationSent(BluetoothDevice device, int status) {
        try {
            __log("Notification sent, " + status);
            state.isComplete = true;
        } catch (Exception e) {
            __log("Error catch onNotificationSent");
        }
    }

    private void handleMtuChanged(BluetoothDevice device, int mtu) {
        __log("Negociated Mtu: " + mtu);
        state.negociatedMtu = mtu;
    }

    @ReactMethod
    public void removeAllServices(Promise promise) {
        try {
            if (this.state.gattServer != null) {
                this.state.gattServer.clearServices();
                __log("Resetting variables ............");
                this.reset();
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
            this.state.isAdvertising = false;
            this.mBluetoothAdvertiser.stopAdvertising(this.advertiseCallback);
            this.advertiseCallback = null;
            promise.resolve(null);
        }
        else{
            promise.reject("No advertisement started");
        }
    }

    @ReactMethod
    public void isAdvertising(Promise promise) {
        // Resolve the promise with the current advertising state
        promise.resolve(this.state.isAdvertising);
    }

    @ReactMethod
    public void notify(String uuid, String message, Promise promise) {
        __log("Notifying from native module");
        BluetoothGattService srvc = this.state.gattServer.getService(this.state.serviceUuid);
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
            while(!this.state.isComplete){
                sleepWithDelay(promise);
            }
            if(message == null){
                promise.reject("Message is null");
                return;
            }
            if(this.state.connectedDevice == null){
                promise.reject("No device connected");
                return;
            }
            __log("Message to send: " + message);
            byte[] value = Base64.decode(message, Base64.DEFAULT);
            ch.setValue(value);
            this.state.isComplete = false;
            boolean success = this.state.gattServer.notifyCharacteristicChanged(this.state.connectedDevice, ch, false);
            promise.resolve(success);
        } catch(Exception ex) {
            __log("Error sending notification.....");
            promise.reject(ex);
        }
    }
}
