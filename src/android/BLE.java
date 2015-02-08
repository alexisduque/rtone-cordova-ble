/*
Copyright 2015 Rtone AxD

Copyright 2014 Evothings AB

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package fr.rtone.cordova;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.bluetooth.*;
import android.bluetooth.BluetoothAdapter.LeScanCallback;
import android.content.*;
import android.app.Activity;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Iterator;
import java.io.UnsupportedEncodingException;
import java.util.Set;

import android.util.Base64;
import android.util.Log;

public class BLE extends CordovaPlugin implements LeScanCallback {
    private CallbackContext mScanCallbackContext;
    private CallbackContext mResetCallbackContext;
    private Context mContext;
    private boolean mRegisteredReceiver = false;
    private boolean mRegisteredPairingReceiver = false;
    private Runnable mOnPowerOn;
    private CallbackContext mPowerOnCallbackContext;
    private static final String TAG = "BluetoothSomfy";

    int mNextGattHandle = 1;
    HashMap<Integer, GattHandler> mGatt = null;

    @Override
    public void initialize(final CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        mContext = webView.getContext();

        if(!mRegisteredReceiver) {
            mContext.registerReceiver(new BluetoothStateReceiver(), new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            mRegisteredReceiver = true;
        }
    }

    @Override
    public boolean execute(String action, CordovaArgs args, final CallbackContext callbackContext)
            throws JSONException
    {
        if("startScan".equals(action)) { startScan(args, callbackContext); return true; }
        else if("stopScan".equals(action)) { stopScan(args, callbackContext); return true; }
        else if("connect".equals(action)) { connect(args, callbackContext); return true; }
        else if("close".equals(action)) { close(args, callbackContext); return true; }
        else if("rssi".equals(action)) { rssi(args, callbackContext); return true; }
        else if("services".equals(action)) { services(args, callbackContext); return true; }
        else if("characteristics".equals(action)) { characteristics(args, callbackContext); return true; }
        else if("descriptors".equals(action)) { descriptors(args, callbackContext); return true; }
        else if("readCharacteristic".equals(action)) { readCharacteristic(args, callbackContext); return true; }
        else if("readDescriptor".equals(action)) { readDescriptor(args, callbackContext); return true; }
        else if("writeCharacteristic".equals(action)) { writeCharacteristic(args, callbackContext); return true; }
        else if("writeDescriptor".equals(action)) { writeDescriptor(args, callbackContext); return true; }
        else if("enableNotification".equals(action)) { enableNotification(args, callbackContext); return true; }
        else if("disableNotification".equals(action)) { disableNotification(args, callbackContext); return true; }
        else if("testCharConversion".equals(action)) { testCharConversion(args, callbackContext); return true; }
        else if("reset".equals(action)) { reset(args, callbackContext); return true; }
        return false;
    }

    /**
     * Called when the WebView does a top-level navigation or refreshes.
     *
     * Plugins should stop any long-running processes and clean up internal state.
     *
     * Does nothing by default.
     *
     * Our version should stop any ongoing scan, and close any existing connections.
     */
    @Override
    public void onReset() {
        if(mScanCallbackContext != null) {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            a.stopLeScan(this);
            mScanCallbackContext = null;
        }
        if(mGatt != null) {
            Iterator<GattHandler> itr = mGatt.values().iterator();
            while(itr.hasNext()) {
                GattHandler gh = itr.next();
                if(gh.mGatt != null)
                    gh.mGatt.close();
            }
            mGatt.clear();
        }
    }

    private void checkPowerState(BluetoothAdapter adapter, CallbackContext cc, Runnable onPowerOn) {
        if(adapter == null) {
            return;
        }
        if(adapter.getState() == BluetoothAdapter.STATE_ON) {
            // Bluetooth is ON
            onPowerOn.run();
        } else {
            mOnPowerOn = onPowerOn;
            mPowerOnCallbackContext = cc;
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            cordova.startActivityForResult(this, enableBtIntent, 0);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        Runnable onPowerOn = mOnPowerOn;
        CallbackContext cc = mPowerOnCallbackContext;
        mOnPowerOn = null;
        mPowerOnCallbackContext = null;
        if(resultCode == Activity.RESULT_OK) {
            onPowerOn.run();
        } else {
            if(resultCode == Activity.RESULT_CANCELED) {
                cc.error("Bluetooth power-on canceled");
            } else {
                cc.error("Bluetooth power-on failed, code "+resultCode);
            }
        }
    }

    private void keepCallback(final CallbackContext callbackContext, JSONObject message) {
        PluginResult r = new PluginResult(PluginResult.Status.OK, message);
        r.setKeepCallback(true);
        callbackContext.sendPluginResult(r);
    }

    private void keepCallback(final CallbackContext callbackContext, String message) {
        PluginResult r = new PluginResult(PluginResult.Status.OK, message);
        r.setKeepCallback(true);
        callbackContext.sendPluginResult(r);
    }

    private void keepCallback(final CallbackContext callbackContext, byte[] message) {
        PluginResult r = new PluginResult(PluginResult.Status.OK, message);
        r.setKeepCallback(true);
        callbackContext.sendPluginResult(r);
    }

    private void startScan(final CordovaArgs args, final CallbackContext callbackContext) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        final LeScanCallback self = this;
        checkPowerState(adapter, callbackContext, new Runnable() {
            @Override
            public void run() {
                if(!adapter.startLeScan(self)) {
                    callbackContext.error("Android function startLeScan failed");
                    return;
                }
                mScanCallbackContext = callbackContext;
            }
        });
    }

    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
        if(mScanCallbackContext == null) {
            return;
        }
        try {
            //System.out.println("onLeScan "+device.getAddress()+" "+rssi+" "+device.getName());
            JSONObject o = new JSONObject();
            o.put("address", device.getAddress());
            o.put("rssi", rssi);
            o.put("name", device.getName());
            o.put("bond", device.getBondState());
            o.put("scanRecord", Base64.encodeToString(scanRecord, Base64.NO_WRAP));
            keepCallback(mScanCallbackContext, o);
        } catch(JSONException e) {
            mScanCallbackContext.error(e.toString());
        }
    }

    private void stopScan(final CordovaArgs args, final CallbackContext callbackContext) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        adapter.stopLeScan(this);
        mScanCallbackContext = null;
    }

    private void connect(final CordovaArgs args, final CallbackContext callbackContext) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        checkPowerState(adapter, callbackContext, new Runnable() {
            @Override
            public void run() {
                try {
                    BluetoothDevice connectDevice = BluetoothAdapter.getDefaultAdapter().
                            getRemoteDevice(args.getString(0));
                    boolean autoConnect = args.getBoolean(1);
                    String passkey = args.getString(2);
                    Log.i(TAG, "autoConnect : " + String.valueOf(autoConnect));
                    if(mGatt == null)
                        mGatt = new HashMap<Integer, GattHandler>();
                    GattHandler gh = new GattHandler(mNextGattHandle, callbackContext, passkey);
                    if(connectDevice.getBondState() == 12) {
                        unpairDevice(connectDevice);
                    }
                    gh.mGatt = connectDevice.connectGatt(mContext, autoConnect, gh);
                    Object res = mGatt.put(mNextGattHandle, gh);
                    assert(res == null);
                    mNextGattHandle++;
                } catch(Exception e) {
                    e.printStackTrace();
                    callbackContext.error(e.toString());
                }
            }
        });
    }

    private BluetoothDevice getPairedDevice(final BluetoothAdapter adapter, String address) {
        Set<BluetoothDevice> devices = adapter.getBondedDevices();
        Iterator<BluetoothDevice> it = devices.iterator();
        while (it.hasNext()) {
            BluetoothDevice device = (BluetoothDevice) it.next();
            if (address.equals(device.getAddress()))
                return device;
        }
        return null;
    }

    void unpairDevice(BluetoothDevice device) {
        try {
            Method method = BluetoothDevice.class.getMethod("removeBond", (Class[]) null);
            method.invoke(device, (Object[]) null);
            Log.i(TAG, "BondedDevice : removeBond()");

        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
        }
    }

    void pairDevice(BluetoothDevice device) {
        try {
            device.getClass().getMethod("setPairingConfirmation", boolean.class).invoke(device, true);
            //device.getClass().getMethod("cancelPairingUserInput", boolean.class).invoke(device, true);
            byte[] passkey = ByteBuffer.allocate(6).putInt(000000).array();
            Log.i(TAG, "Passkey : " + passkey.toString());
            Method m = device.getClass().getMethod("setPin", byte[].class);
            m.invoke(device, passkey);
            Method bond = device.getClass().getMethod("createBond", (Class[]) null);
            bond.invoke(device, (Object[]) null);
            Log.i(TAG, "Bonded Device : remove()");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void refresh(BluetoothGatt gatt) {
        try {
            Method method = gatt.getClass().getMethod("refresh", (Class[]) null);
            method.invoke(gatt, (Object[]) null);
            Log.i(TAG, "Refresh Device : refresh()");
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
        }
    }

    private void close(final CordovaArgs args, final CallbackContext callbackContext) {
        try {
            GattHandler gh = mGatt.get(args.getInt(0));
            gh.mGatt.close();
            //refresh(gh.mGatt);
            Log.d(TAG, "Device disconnected");
            if (gh.mGatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
                mGatt.remove(args.getInt(0));
            }

        } catch(JSONException e) {
            e.printStackTrace();
            callbackContext.error(e.toString());
        }
    }

    private void rssi(final CordovaArgs args, final CallbackContext callbackContext) {
        GattHandler gh = null;
        try {
            gh = mGatt.get(args.getInt(0));
            if(gh.mRssiContext != null) {
                callbackContext.error("Previous call to rssi() not yet completed!");
                return;
            }
            gh.mRssiContext = callbackContext;
            if(!gh.mGatt.readRemoteRssi()) {
                gh.mRssiContext = null;
                callbackContext.error("readRemoteRssi");
            }
        } catch(Exception e) {
            e.printStackTrace();
            if(gh != null) {
                gh.mRssiContext = null;
            }
            callbackContext.error(e.toString());
        }
    }

    private void services(final CordovaArgs args, final CallbackContext callbackContext) {
        try {
            final GattHandler gh = mGatt.get(args.getInt(0));
            gh.mOperations.add(new Runnable() {
                @Override
                public void run() {
                    gh.mCurrentOpContext = callbackContext;
                    if (!gh.mGatt.discoverServices()) {
                        gh.mCurrentOpContext = null;
                        callbackContext.error("discoverServices");
                        gh.process();
                    }
                }
            });
            gh.process();
        } catch(Exception e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
            callbackContext.error(e.toString());
        }
    }

    private void characteristics(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        final GattHandler gh = mGatt.get(args.getInt(0));
        JSONArray a = new JSONArray();
        for(BluetoothGattCharacteristic c : gh.mServices.get(args.getInt(1)).getCharacteristics()) {
            if(gh.mCharacteristics == null)
                gh.mCharacteristics = new HashMap<Integer, BluetoothGattCharacteristic>();
            Object res = gh.mCharacteristics.put(gh.mNextHandle, c);
            assert(res == null);

            JSONObject o = new JSONObject();
            o.put("handle", gh.mNextHandle);
            o.put("uuid", c.getUuid().toString());
            o.put("permissions", c.getPermissions());
            o.put("properties", c.getProperties());
            o.put("writeType", c.getWriteType());

            gh.mNextHandle++;
            a.put(o);
        }
        callbackContext.success(a);
    }

    private void descriptors(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        final GattHandler gh = mGatt.get(args.getInt(0));
        JSONArray a = new JSONArray();
        for(BluetoothGattDescriptor d : gh.mCharacteristics.get(args.getInt(1)).getDescriptors()) {
            if(gh.mDescriptors == null)
                gh.mDescriptors = new HashMap<Integer, BluetoothGattDescriptor>();
            Object res = gh.mDescriptors.put(gh.mNextHandle, d);
            assert(res == null);

            JSONObject o = new JSONObject();
            o.put("handle", gh.mNextHandle);
            o.put("uuid", d.getUuid().toString());
            o.put("permissions", d.getPermissions());

            gh.mNextHandle++;
            a.put(o);
        }
        callbackContext.success(a);
    }

    private void readCharacteristic(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        final GattHandler gh = mGatt.get(args.getInt(0));
        gh.mOperations.add(new Runnable() {
            @Override
            public void run() {
                try {
                    gh.mCurrentOpContext = callbackContext;
                    Log.i(TAG, "readCharacteristic() - Char handle : " + args.getInt(1) + " - Device handle : " + gh.mHandle + "/" + args.getInt(0) + " - Bond State : " + gh.mGatt.getDevice().getBondState());
                    if (!gh.mGatt.readCharacteristic(gh.mCharacteristics.get(args.getInt(1)))) {
                        gh.mCurrentOpContext = null;
                        callbackContext.error("readCharacteristic");
                        gh.process();
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                    callbackContext.error(e.toString());
                    gh.process();
                }
            }
        });
        gh.process();
    }

    private void readDescriptor(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        final GattHandler gh = mGatt.get(args.getInt(0));
        gh.mOperations.add(new Runnable() {
            @Override
            public void run() {
                try {
                    gh.mCurrentOpContext = callbackContext;
                    if(!gh.mGatt.readDescriptor(gh.mDescriptors.get(args.getInt(1)))) {
                        gh.mCurrentOpContext = null;
                        callbackContext.error("readDescriptor");
                        gh.process();
                    }
                } catch(JSONException e) {
                    e.printStackTrace();
                    callbackContext.error(e.toString());
                    gh.process();
                }
            }
        });
        gh.process();
    }

    private void writeCharacteristic(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        final GattHandler gh = mGatt.get(args.getInt(0));
        gh.mOperations.add(new Runnable() {
            @Override
            public void run() {
                try {
                    gh.mCurrentOpContext = callbackContext;
                    BluetoothGattCharacteristic c = gh.mCharacteristics.get(args.getInt(1));
                    Log.i(TAG, "writeCharacteristic("+args.getInt(0)+", "+args.getInt(1)+", "+args.getString(2)+")");
                    c.setValue(args.getArrayBuffer(2));
                    if(!gh.mGatt.writeCharacteristic(c)) {
                        gh.mCurrentOpContext = null;
                        callbackContext.error("writeCharacteristic");
                        gh.process();
                    }
                } catch(JSONException e) {
                    e.printStackTrace();
                    callbackContext.error(e.toString());
                    gh.process();
                }
            }
        });
        gh.process();
    }

    private void writeDescriptor(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        final GattHandler gh = mGatt.get(args.getInt(0));
        gh.mOperations.add(new Runnable() {
            @Override
            public void run() {
                try {
                    gh.mCurrentOpContext = callbackContext;
                    BluetoothGattDescriptor d = gh.mDescriptors.get(args.getInt(1));
                    d.setValue(args.getArrayBuffer(2));
                    if(!gh.mGatt.writeDescriptor(d)) {
                        gh.mCurrentOpContext = null;
                        callbackContext.error("writeDescriptor");
                        gh.process();
                    }
                } catch(JSONException e) {
                    e.printStackTrace();
                    callbackContext.error(e.toString());
                    gh.process();
                }
            }
        });
        gh.process();
    }

    private void enableNotification(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        final GattHandler gh = mGatt.get(args.getInt(0));
        BluetoothGattCharacteristic c = gh.mCharacteristics.get(args.getInt(1));
        gh.mNotifications.put(c, callbackContext);
        if(!gh.mGatt.setCharacteristicNotification(c, true)) {
            callbackContext.error("setCharacteristicNotification");
        }
    }

    private void disableNotification(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        final GattHandler gh = mGatt.get(args.getInt(0));
        BluetoothGattCharacteristic c = gh.mCharacteristics.get(args.getInt(1));
        gh.mNotifications.remove(c);
        if(gh.mGatt.setCharacteristicNotification(c, false)) {
            callbackContext.success();
        } else {
            callbackContext.error("setCharacteristicNotification");
        }
    }

    private void testCharConversion(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
        byte[] b = {(byte)args.getInt(0)};
        callbackContext.success(b);
    }

    private void reset(final CordovaArgs args, final CallbackContext cc) throws JSONException {
        mResetCallbackContext = null;
        mGatt = null;
        mNextGattHandle = 1;
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if(mScanCallbackContext != null) {
            a.stopLeScan(this);
            mScanCallbackContext = null;
        }
        int state = a.getState();
        //STATE_OFF, STATE_TURNING_ON, STATE_ON, STATE_TURNING_OFF.
        if(state == BluetoothAdapter.STATE_TURNING_ON) {
            // reset in progress; wait for STATE_ON.
            mResetCallbackContext = cc;
            return;
        }
        if(state == BluetoothAdapter.STATE_TURNING_OFF) {
            // reset in progress; wait for STATE_OFF.
            mResetCallbackContext = cc;
            return;
        }
        if(state == BluetoothAdapter.STATE_OFF) {
            boolean res = a.enable();
            if(res) {
                mResetCallbackContext = cc;
            } else {
                cc.error("enable");
            }
            return;
        }
        if(state == BluetoothAdapter.STATE_ON) {
            boolean res = a.disable();
            if(res) {
                mResetCallbackContext = cc;
            } else {
                cc.error("disable");
            }
            return;
        }
        cc.error("Unknown state: " + state);
    }

    class BluetoothStateReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            int state = a.getState();
            System.out.println("BluetoothState: "+a);
            if(mResetCallbackContext != null) {
                if(state == BluetoothAdapter.STATE_OFF) {
                    boolean res = a.enable();
                    if(!res) {
                        mResetCallbackContext.error("enable");
                        mResetCallbackContext = null;
                    }
                }
                if(state == BluetoothAdapter.STATE_ON) {
                    mResetCallbackContext.success();
                    mResetCallbackContext = null;
                }
            }
        }
    };

    private class GattHandler extends BluetoothGattCallback {
        final int mHandle;
        String mPasskey;
        LinkedList<Runnable> mOperations = new LinkedList<Runnable>();
        CallbackContext mConnectContext, mRssiContext, mCurrentOpContext;
        BluetoothGatt mGatt;
        int mNextHandle = 1;
        HashMap<Integer, BluetoothGattService> mServices;
        HashMap<Integer, BluetoothGattCharacteristic> mCharacteristics;
        HashMap<Integer, BluetoothGattDescriptor> mDescriptors;
        HashMap<BluetoothGattCharacteristic, CallbackContext> mNotifications =
                new HashMap<BluetoothGattCharacteristic, CallbackContext>();

        GattHandler(int h, CallbackContext cc, String passkey) {
            mHandle = h;
            mConnectContext = cc;
            mPasskey = passkey;
        }

        void process() {
            if(mCurrentOpContext != null)
                return;
            Runnable r = mOperations.poll();
            if(r == null)
                return;
            r.run();
        }

        private BroadcastReceiver mPairingBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                BluetoothDevice remoteDevice = (BluetoothDevice) intent.getExtras().get(BluetoothDevice.EXTRA_DEVICE);
                try {
                    if (intent.getAction().equals("android.bluetooth.device.action.PAIRING_REQUEST")) {
                        byte[] pin = (byte[]) BluetoothDevice.class.getMethod("convertPinToBytes", String.class)
                                .invoke(BluetoothDevice.class, mPasskey);
                        BluetoothDevice.class.getMethod("setPin", byte[].class)
                                .invoke(remoteDevice, pin);
                        BluetoothDevice.class.getMethod("setPairingConfirmation", boolean.class)
                                .invoke(remoteDevice, true);
                        Log.d(TAG, "Success to set passkey.");
                        final IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                        mContext.registerReceiver(mBondingBroadcastReceiver, filter);
                    }
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage());
                    e.printStackTrace();
                }
                mContext.unregisterReceiver(this);
                mRegisteredPairingReceiver = false;
            }
        };

        private BroadcastReceiver mBondingBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                final int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);

                Log.d(TAG, "Bond state changed for: " + device.getAddress() + " new state: " + bondState + " previous: " + previousBondState);

                if (bondState == BluetoothDevice.BOND_BONDED) {
                    try {
                        BluetoothDevice.class.getMethod("cancelPairingUserInput").invoke(mGatt.getDevice());
                        Log.d(TAG, "Cancel user input");
                    } catch (Exception e) {
                        Log.e(TAG, e.getMessage());
                        e.printStackTrace();
                    }
                    mContext.unregisterReceiver(this);
                }
            }
        };

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if(status == BluetoothGatt.GATT_SUCCESS) {
                if (!this.mPasskey.isEmpty()) {
                    if(!mRegisteredPairingReceiver) {
                        final IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
                        mContext.registerReceiver(mPairingBroadcastReceiver, filter);
                        mRegisteredPairingReceiver = true;
                    }
                    try {
                        Method createBond = BluetoothDevice.class.getMethod("createBond", (Class[]) null);
                        createBond.invoke(gatt.getDevice(), (Object[]) null);
                    } catch (Exception e) {
                        Log.e(TAG, e.getMessage());
                        e.printStackTrace();
                    }
                }
                try {
                    JSONObject o = new JSONObject();
                    o.put("deviceHandle", mHandle);
                    o.put("state", newState);
                    keepCallback(mConnectContext, o);
                } catch(JSONException e) {
                    e.printStackTrace();
                    assert(false);
                }
            } else {
                mConnectContext.error(status);
            }
            //refresh(gatt);
        }
        @Override
        public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
            if(status == BluetoothGatt.GATT_SUCCESS) {
                mRssiContext.success(rssi);
            } else {
                mRssiContext.error(status);
            }
        }
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if(status == BluetoothGatt.GATT_SUCCESS) {
                List<BluetoothGattService> services = g.getServices();
                JSONArray a = new JSONArray();
                for(BluetoothGattService s : services) {
                    // give the service a handle.
                    if(mServices == null)
                        mServices = new HashMap<Integer, BluetoothGattService>();
                    Object res = mServices.put(mNextHandle, s);
                    assert(res == null);

                    try {
                        JSONObject o = new JSONObject();
                        o.put("handle", mNextHandle);
                        o.put("uuid", s.getUuid().toString());
                        o.put("type", s.getType());

                        mNextHandle++;
                        a.put(o);
                    } catch(JSONException e) {
                        e.printStackTrace();
                        assert(false);
                    }
                }
                mCurrentOpContext.success(a);
            } else {
                mCurrentOpContext.error(status);
            }
            mCurrentOpContext = null;
            process();
        }
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            Log.i(TAG, "readCharacteristic() - Bond State : " + g.getDevice().getBondState());
            if(status == BluetoothGatt.GATT_SUCCESS) {
                mCurrentOpContext.success(c.getValue());
            } else {
                mCurrentOpContext.error(status);
            }
            mCurrentOpContext = null;
            process();
        }
        @Override
        public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if(status == BluetoothGatt.GATT_SUCCESS) {
                mCurrentOpContext.success(d.getValue());
            } else {
                mCurrentOpContext.error(status);
            }
            mCurrentOpContext = null;
            process();
        }
        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if(status == BluetoothGatt.GATT_SUCCESS) {
                mCurrentOpContext.success();
            } else {
                mCurrentOpContext.error(status);
            }
            mCurrentOpContext = null;
            process();
        }
        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if(status == BluetoothGatt.GATT_SUCCESS) {
                mCurrentOpContext.success();
            } else {
                mCurrentOpContext.error(status);
            }
            mCurrentOpContext = null;
            process();
        }
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            CallbackContext cc = mNotifications.get(c);
            keepCallback(cc, c.getValue());
        }
    };
}
