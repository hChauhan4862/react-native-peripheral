package com.reactnative.bleperipheral;

import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import java.util.Map;
import android.util.Log;
import java.util.Enumeration;
import android.content.pm.PackageManager;
import android.content.Context;
import java.util.HashMap;
import com.facebook.react.bridge.Callback;
import android.os.Handler;
import java.lang.reflect.Method;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.os.ParcelUuid;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.UUID;
import java.io.UnsupportedEncodingException;
import java.util.Random;
import android.bluetooth.BluetoothStatusCodes;

public class RnBlePeripheralModule extends ReactContextBaseJavaModule{
  private Context context;
  private BluetoothAdapter mBluetoothAdapter;
  private BluetoothLeAdvertiser mBluetoothAdvertiser;
  private DeviceAdvertiseCallback advertiseCallback;
  private AdvertiseSettings advertiseSettings;
  private AdvertiseData advertiseData;

  private UUID SERVICE_UUID = UUID.fromString("0000" + segment() + "-0000-1000-8000-00805f9b34fb");

  private MutableLiveData _deviceConnection = new MutableLiveData<Boolean>();
  private LiveData deviceConnection = (LiveData<Boolean>) _deviceConnection;

  private BluetoothGattServerCallback gattServerCallback;
  private BluetoothGattCallback gattClientCallback;

  private class References {
    boolean isConnected = false;
    BluetoothGatt gatt = null;
    BluetoothGattServer gattServer = null;
    BluetoothGatt gattClient = null;
    Callback callback = null;
    String message = "";
    BluetoothGattCharacteristic messageCharacteristic = null;
    UUID MESSAGE_UUID = UUID.fromString("7db3e235-3608-41f3-a03c-955fcbd2ea4b");
  }

  private final References references;

  public RnBlePeripheralModule(ReactApplicationContext context) {
    super(context);
    this.context = context.getApplicationContext();
    this.mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    this.advertiseSettings = buildAdvertiseSettings();
    this.advertiseData = buildAdvertiseData();
    this.gattServerCallback = null;
    this.gattClientCallback = null;
    this.references = new References();
  }

  @Override
  public String getName() {
    return "BLEServer";
  }

  @ReactMethod
  public void validateBle(Callback callback) {
    if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
      callback.invoke(false);
    } else {
      callback.invoke(true);
    }
  }

  @ReactMethod
  public void btEnabled(Callback callback) {
    if (mBluetoothAdapter.isEnabled()) {
      callback.invoke(true);
    } else {
      callback.invoke(false);
    }
  }

  @ReactMethod
  public void setupGattServer(Callback callback) {
    GattServerCallback gattServerCallback = new GattServerCallback();
    BluetoothManager bluetoothManager = (BluetoothManager) this.context.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
    references.gattServer = bluetoothManager.openGattServer(context, gattServerCallback);

    if (references.gattServer == null) {
      callback.invoke(false);
    } else {
      references.gattServer.addService(setupGattService());
      callback.invoke(true);
    }
  }

  @ReactMethod
  public void getBTAddress(Callback callback) {
    callback.invoke(this.SERVICE_UUID.toString());
  }

  class DeviceConnectionState {
    class Connected extends DeviceConnectionState {
      public Connected(BluetoothDevice device) {
      }
    }
    class Disconnected extends DeviceConnectionState {
      public Disconnected() {
      }
    }
  }

  public class GattServerCallback extends BluetoothGattServerCallback {

    @Override
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
      super.onConnectionStateChange(device, status, newState);
      boolean isSuccess = status == BluetoothGatt.GATT_SUCCESS;
      boolean isConnected = newState == BluetoothProfile.STATE_CONNECTED;
      if (isSuccess && isConnected) {
        references.isConnected = true;
        stopAdvertising();
      } else {
        _deviceConnection.postValue(false);
      }
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
      super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);

      String info = "onCharacteristicWriteRequest" + device.toString() + " (" + requestId + ", " + characteristic.getUuid().toString() + ", " + preparedWrite + ", " + responseNeeded + ", " + offset + ", " + value +")";
      references.callback.invoke(info);
      // if (characteristic.getUuid() == references.MESSAGE_UUID) {
      //     references.gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
      //     try {
      //         references.message = new String(value, "UTF-8");
      //         //references.callback.invoke("Mira, me mandaron esto: " + references.message);
      //     } catch (Exception e) {
      //     }
      // }
    }

    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
      super.onCharacteristicReadRequest(device, requestId, offset, characteristic);

      String info = "onCharacteristicReadRequest" + device.toString() + " (" + requestId + ", " + characteristic.getUuid().toString() + ", " + offset + ")";
      references.callback.invoke(info);
      // if (characteristic.getUuid() == references.MESSAGE_UUID) {
      //     references.gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
      //     try {
      //         references.message = new String(value, "UTF-8");
      //         //references.callback.invoke("Mira, me mandaron esto: " + references.message);
      //     } catch (Exception e) {
      //     }
      // }
    }
  }

  private BluetoothGattService setupGattService() {

    BluetoothGattService service = new BluetoothGattService(
            this.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY);

    BluetoothGattCharacteristic characteristic_state = new BluetoothGattCharacteristic(
            UUID.fromString("00000001-A123-48CE-896B-4C76973373E6"),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ
    );

    BluetoothGattCharacteristic characteristic_c2s = new BluetoothGattCharacteristic(
            UUID.fromString("00000002-A123-48CE-896B-4C76973373E6"),
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ
    );

    BluetoothGattCharacteristic characteristic_s2c = new BluetoothGattCharacteristic(
            UUID.fromString("00000003-A123-48CE-896B-4C76973373E6"),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
    );

    BluetoothGattCharacteristic characteristic_msg = new BluetoothGattCharacteristic(
            UUID.fromString("7db3e235-3608-41f3-a03c-955fcbd2ea4b"),
            BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE | BluetoothGattCharacteristic.PERMISSION_READ
    );

    service.addCharacteristic(characteristic_state);
    service.addCharacteristic(characteristic_c2s);
    service.addCharacteristic(characteristic_s2c);
    service.addCharacteristic(characteristic_msg);

    return service;
  }

  @ReactMethod
  public void startAdvertisement() {
    this.mBluetoothAdvertiser = this.mBluetoothAdapter.getBluetoothLeAdvertiser();
    if (this.advertiseCallback == null) {
      this.advertiseCallback = new DeviceAdvertiseCallback();
      this.mBluetoothAdvertiser.startAdvertising(this.advertiseSettings, this.advertiseData, this.advertiseCallback);
    }
  }

  private class DeviceAdvertiseCallback extends AdvertiseCallback {

    @Override
    public void onStartFailure(int errorCode) {
      super.onStartFailure(errorCode);
    }

    @Override
    public void onStartSuccess(AdvertiseSettings settingsInEffect) {
      super.onStartSuccess(settingsInEffect);
    }
  }

  private AdvertiseData buildAdvertiseData() {
    AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(this.SERVICE_UUID)).setIncludeDeviceName(true);
    return dataBuilder.build();
  }

  private AdvertiseSettings buildAdvertiseSettings() {
    return new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER).setTimeout(0).build();
  }

  private String segment(){
    String chars = "0123456789ABCDEF";
    String segment = "";
    for(int i = 0; i<=3; ++i){
      segment += chars.charAt(new Random().nextInt(15));
    }
    return segment;
  }

  @ReactMethod
  public void getIsDeviceConnected(Callback callback, Callback callback2) {
    callback.invoke(references.isConnected);
    references.callback = callback2;
  }

  @ReactMethod
  public boolean sendMessage(String message, Callback callback) {
    if (references.messageCharacteristic != null) {
      references.messageCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
      try {
        byte[] messageBytes = message.getBytes("UTF-8");
        references.messageCharacteristic.setValue(messageBytes);
        if (references.gatt != null) {
          int success = references.gatt.writeCharacteristic(references.messageCharacteristic, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
          callback.invoke(success + "");
          if (success == 0) {
            return true;
          }
        }
      } catch(UnsupportedEncodingException ex) {
        callback.invoke("exception " + ex.toString());
        return false;
      }
    }
    callback.invoke(false);
    return false;
  }

  @ReactMethod
  private void stopAdvertising() {
    if(this.advertiseCallback != null) {
      this.mBluetoothAdvertiser.stopAdvertising(this.advertiseCallback);
      this.advertiseCallback = null;
    }
  }

}
