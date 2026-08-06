/*
 * Copied VERBATIM from the decompilation of MainUI.apk
 * (device-extract/decompiled/mainui/sources/com/ts/can/carinfo/ICarInfoService.java).
 *
 * DO NOT re-author this as an .aidl file. AIDL assigns transaction codes by
 * declaration order, and this interface's are NOT in declaration order:
 *
 *     requestCarAirInfo    = 1
 *     requestCarAirLtTemp  = 2
 *     requestCarAirRtTemp  = 3
 *     requestCarDoorInfo   = 4
 *     requestCarIllInfo    = 5
 *     requestCarBaseInfo   = 6      <- last code, but declared fourth
 *
 * Regenerating from a hand-written .aidl in the interface's declared order
 * would assign requestCarBaseInfo code 4, and every call would silently invoke
 * requestCarDoorInfo instead. The literals below are the contract.
 *
 * The service is exported by MainUI's own manifest:
 *
 *     <service android:name="com.ts.can.carinfo.CarInfoService"
 *              android:exported="true">
 *       <intent-filter>
 *         <action android:name="com.ts.can.carinfo.CarInfoService" />
 *         <category android:name="android.intent.category.DEFAULT" />
 *       </intent-filter>
 *     </service>
 */
package com.ts.can.carinfo;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface ICarInfoService extends IInterface {
    int[] requestCarAirInfo() throws RemoteException;

    String requestCarAirLtTemp() throws RemoteException;

    String requestCarAirRtTemp() throws RemoteException;

    int[] requestCarBaseInfo() throws RemoteException;

    int[] requestCarDoorInfo() throws RemoteException;

    boolean requestCarIllInfo() throws RemoteException;

    public static abstract class Stub extends Binder implements ICarInfoService {
        private static final String DESCRIPTOR = "com.ts.can.carinfo.ICarInfoService";
        static final int TRANSACTION_requestCarAirInfo = 1;
        static final int TRANSACTION_requestCarAirLtTemp = 2;
        static final int TRANSACTION_requestCarAirRtTemp = 3;
        static final int TRANSACTION_requestCarBaseInfo = 6;
        static final int TRANSACTION_requestCarDoorInfo = 4;
        static final int TRANSACTION_requestCarIllInfo = 5;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ICarInfoService asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && (iin instanceof ICarInfoService)) {
                return (ICarInfoService) iin;
            }
            return new Proxy(obj);
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }

        @Override // android.os.Binder
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case 1:
                    data.enforceInterface(DESCRIPTOR);
                    int[] _result = requestCarAirInfo();
                    reply.writeNoException();
                    reply.writeIntArray(_result);
                    return true;
                case 2:
                    data.enforceInterface(DESCRIPTOR);
                    String _result2 = requestCarAirLtTemp();
                    reply.writeNoException();
                    reply.writeString(_result2);
                    return true;
                case 3:
                    data.enforceInterface(DESCRIPTOR);
                    String _result3 = requestCarAirRtTemp();
                    reply.writeNoException();
                    reply.writeString(_result3);
                    return true;
                case 4:
                    data.enforceInterface(DESCRIPTOR);
                    int[] _result4 = requestCarDoorInfo();
                    reply.writeNoException();
                    reply.writeIntArray(_result4);
                    return true;
                case 5:
                    data.enforceInterface(DESCRIPTOR);
                    boolean _result5 = requestCarIllInfo();
                    reply.writeNoException();
                    reply.writeInt(_result5 ? 1 : 0);
                    return true;
                case 6:
                    data.enforceInterface(DESCRIPTOR);
                    int[] _result6 = requestCarBaseInfo();
                    reply.writeNoException();
                    reply.writeIntArray(_result6);
                    return true;
                case 1598968902:
                    reply.writeString(DESCRIPTOR);
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements ICarInfoService {
            private IBinder mRemote;

            Proxy(IBinder remote) {
                this.mRemote = remote;
            }

            @Override // android.os.IInterface
            public IBinder asBinder() {
                return this.mRemote;
            }

            public String getInterfaceDescriptor() {
                return Stub.DESCRIPTOR;
            }

            @Override // com.ts.can.carinfo.ICarInfoService
            public int[] requestCarAirInfo() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    this.mRemote.transact(1, _data, _reply, 0);
                    _reply.readException();
                    int[] _result = _reply.createIntArray();
                    return _result;
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override // com.ts.can.carinfo.ICarInfoService
            public String requestCarAirLtTemp() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    this.mRemote.transact(2, _data, _reply, 0);
                    _reply.readException();
                    String _result = _reply.readString();
                    return _result;
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override // com.ts.can.carinfo.ICarInfoService
            public String requestCarAirRtTemp() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    this.mRemote.transact(3, _data, _reply, 0);
                    _reply.readException();
                    String _result = _reply.readString();
                    return _result;
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override // com.ts.can.carinfo.ICarInfoService
            public int[] requestCarDoorInfo() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    this.mRemote.transact(4, _data, _reply, 0);
                    _reply.readException();
                    int[] _result = _reply.createIntArray();
                    return _result;
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override // com.ts.can.carinfo.ICarInfoService
            public boolean requestCarIllInfo() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    this.mRemote.transact(5, _data, _reply, 0);
                    _reply.readException();
                    boolean _result = _reply.readInt() != 0;
                    return _result;
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override // com.ts.can.carinfo.ICarInfoService
            public int[] requestCarBaseInfo() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    this.mRemote.transact(6, _data, _reply, 0);
                    _reply.readException();
                    int[] _result = _reply.createIntArray();
                    return _result;
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }
    }
}
