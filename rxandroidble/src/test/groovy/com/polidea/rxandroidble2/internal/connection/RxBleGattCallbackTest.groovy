package com.polidea.rxandroidble2.internal.connection

import android.bluetooth.*
import com.polidea.rxandroidble2.HiddenBluetoothGattCallback
import com.polidea.rxandroidble2.exceptions.*
import hkhc.electricspock.ElectricSpecification
import io.reactivex.observers.TestObserver
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import org.robolectric.annotation.Config
import spock.lang.Shared
import spock.lang.Unroll

import static android.bluetooth.BluetoothGatt.GATT_FAILURE
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS
import static android.bluetooth.BluetoothProfile.*
import static com.polidea.rxandroidble2.RxBleConnection.RxBleConnectionState.DISCONNECTED

@Config(manifest = Config.NONE)
class RxBleGattCallbackTest extends ElectricSpecification {

    DisconnectionRouter mockDisconnectionRouter
    PublishSubject mockDisconnectionSubject
    RxBleGattCallback objectUnderTest
    @Shared def mockBluetoothGatt = Mock BluetoothGatt
    @Shared def mockBluetoothGattCharacteristic = Mock BluetoothGattCharacteristic
    @Shared def mockBluetoothGattDescriptor = Mock BluetoothGattDescriptor
    @Shared def mockBluetoothDevice = Mock BluetoothDevice
    @Shared def mockBluetoothDeviceMacAddress = "MacAddress"

    def setupSpec() {
        mockBluetoothGatt.getDevice() >> mockBluetoothDevice
        mockBluetoothDevice.getAddress() >> mockBluetoothDeviceMacAddress
    }

    def setup() {
        mockDisconnectionRouter = Mock DisconnectionRouter
        mockDisconnectionSubject = PublishSubject.create()
        mockDisconnectionRouter.asErrorOnlyObservable() >> mockDisconnectionSubject
        objectUnderTest = new RxBleGattCallback(Schedulers.trampoline(), Mock(BluetoothGattProvider), mockDisconnectionRouter,
                new NativeCallbackDispatcher())
    }

    def "sanity check"() {

        expect:
        GATT_SUCCESS != GATT_FAILURE
    }

    @Unroll
    def "should relay BluetoothGattCallback callbacks to appropriate Observables"() {

        given:
        def testSubscriber = observableGetter.call(objectUnderTest).test()

        when:
        callbackCaller.call(objectUnderTest.getBluetoothGattCallback())

        then:
        testSubscriber.assertValueCount(1)

        where:
        observableGetter << [
                { return (it as RxBleGattCallback).getOnConnectionStateChange() },
                { return (it as RxBleGattCallback).getOnServicesDiscovered() },
                { return (it as RxBleGattCallback).getOnCharacteristicRead() },
                { return (it as RxBleGattCallback).getOnCharacteristicWrite() },
                { return (it as RxBleGattCallback).getOnCharacteristicChanged() },
                { return (it as RxBleGattCallback).getOnDescriptorRead() },
                { return (it as RxBleGattCallback).getOnDescriptorWrite() },
                { return (it as RxBleGattCallback).getOnRssiRead() },
                { return (it as RxBleGattCallback).getConnectionParametersUpdates() }
        ]
        callbackCaller << [
                { (it as BluetoothGattCallback).onConnectionStateChange(mockBluetoothGatt, GATT_SUCCESS, STATE_CONNECTED) },
                { (it as BluetoothGattCallback).onServicesDiscovered(mockBluetoothGatt, GATT_SUCCESS) },
                { (it as BluetoothGattCallback).onCharacteristicRead(mockBluetoothGatt, mockBluetoothGattCharacteristic, GATT_SUCCESS) },
                { (it as BluetoothGattCallback).onCharacteristicWrite(mockBluetoothGatt, mockBluetoothGattCharacteristic, GATT_SUCCESS) },
                { (it as BluetoothGattCallback).onCharacteristicChanged(mockBluetoothGatt, mockBluetoothGattCharacteristic) },
                { (it as BluetoothGattCallback).onDescriptorRead(mockBluetoothGatt, mockBluetoothGattDescriptor, GATT_SUCCESS) },
                { (it as BluetoothGattCallback).onDescriptorWrite(mockBluetoothGatt, mockBluetoothGattDescriptor, GATT_SUCCESS) },
                { (it as BluetoothGattCallback).onReadRemoteRssi(mockBluetoothGatt, 1, GATT_SUCCESS) },
                { (it as HiddenBluetoothGattCallback).onConnectionUpdated(mockBluetoothGatt, 1, 1, 1, GATT_SUCCESS) }
        ]
    }

    def "observeDisconnect() should emit error when DisconnectionRouter.asGenericObservable() emits error"() {

        given:
        def testException = new RuntimeException("test")
        def testSubscriber = objectUnderTest.observeDisconnect().test()

        when:
        mockDisconnectionSubject.onError(testException)

        then:
        testSubscriber.assertError(testException)
    }

    @Unroll
    def "should call DisconnectionRouter.onDisconnectedException() when .onConnectionStateChange() callback will receive STATE_DISCONNECTED/STATE_DISCONNECTING regardless of status"() {

        when:
        objectUnderTest.getBluetoothGattCallback().onConnectionStateChange(mockBluetoothGatt, status, state)

        then:
        1 * mockDisconnectionRouter.onDisconnectedException({ BleDisconnectedException e -> e.bluetoothDeviceAddress == mockBluetoothDeviceMacAddress })

        where:
        [state, status] << [[STATE_DISCONNECTED, STATE_DISCONNECTING], [GATT_SUCCESS, GATT_FAILURE]].combinations()
    }

    @Unroll
    def "should call DisconnectionRouter.onGattConnectionStateException() when .onConnectionStateChange() callback will receive STATE_CONNECTED/STATE_CONNECTING with status != GATT_SUCCESS "() {

        when:
        objectUnderTest.getBluetoothGattCallback().onConnectionStateChange(mockBluetoothGatt, GATT_FAILURE, state)

        then:
        1 * mockDisconnectionRouter.onGattConnectionStateException({ BleGattException e ->
            e.macAddress == mockBluetoothDeviceMacAddress &&
                    e.status == GATT_FAILURE &&
                    e.bleGattOperationType == BleGattOperationType.CONNECTION_STATE
        })

        where:
        state << [STATE_CONNECTED, STATE_CONNECTING]
    }

    @Unroll
    def "observeDisconnect() should not call DisconnectionRouter.route() if any of BluetoothGatt.on*() [other than onConnectionStateChanged()] callbacks will receive status != GATT_SUCCESS"() {

        when:
        callbackCaller.call(objectUnderTest.getBluetoothGattCallback())

        then:
        0 * mockDisconnectionRouter.onDisconnectedException(_)

        and:
        0 * mockDisconnectionRouter.onGattConnectionStateException(_)

        where:
        callbackCaller << [
                { (it as BluetoothGattCallback).onServicesDiscovered(mockBluetoothGatt, GATT_FAILURE) },
                { (it as BluetoothGattCallback).onCharacteristicRead(mockBluetoothGatt, mockBluetoothGattCharacteristic, GATT_FAILURE) },
                { (it as BluetoothGattCallback).onCharacteristicWrite(mockBluetoothGatt, mockBluetoothGattCharacteristic, GATT_FAILURE) },
                { (it as BluetoothGattCallback).onDescriptorRead(mockBluetoothGatt, mockBluetoothGattDescriptor, GATT_FAILURE) },
                { (it as BluetoothGattCallback).onDescriptorWrite(mockBluetoothGatt, mockBluetoothGattDescriptor, GATT_FAILURE) },
                { (it as BluetoothGattCallback).onReadRemoteRssi(mockBluetoothGatt, 1, GATT_FAILURE) },
                { (it as HiddenBluetoothGattCallback).onConnectionUpdated(mockBluetoothGatt, 1, 1, 1, GATT_FAILURE) }
        ]
    }

    @Unroll
    def "getOnConnectionStateChange() should not throw if onConnectionStateChange() received STATE_DISCONNECTED"() {

        given:
        def testSubscriber = objectUnderTest.getOnConnectionStateChange().test()

        when:
        objectUnderTest.getBluetoothGattCallback().onConnectionStateChange(mockBluetoothGatt, status, STATE_DISCONNECTED)

        then:
        testSubscriber.assertNoErrors()
        testSubscriber.assertValue(DISCONNECTED)

        where:
        status << [
                GATT_SUCCESS,
                GATT_FAILURE
        ]
    }

    @Unroll
    def "callbacks other than getOnConnectionStateChange() should throw if DisconnectionRouter.asObservable() emits an exception"() {

        given:
        def testException = new RuntimeException("test")
        def testSubscriber = observableGetter.call(objectUnderTest).test()

        when:
        mockDisconnectionSubject.onError(testException)

        then:
        testSubscriber.assertError(testException)

        where:
        observableGetter << [
                { return (it as RxBleGattCallback).getOnServicesDiscovered() },
                { return (it as RxBleGattCallback).getOnCharacteristicRead() },
                { return (it as RxBleGattCallback).getOnCharacteristicWrite() },
                { return (it as RxBleGattCallback).getOnCharacteristicChanged() },
                { return (it as RxBleGattCallback).getOnDescriptorRead() },
                { return (it as RxBleGattCallback).getOnDescriptorWrite() },
                { return (it as RxBleGattCallback).getOnRssiRead() },
                { return (it as RxBleGattCallback).getConnectionParametersUpdates() }
        ]
    }

    @Unroll
    def "callbacks should emit error if their respective BluetoothGatt.on*() callbacks received status != GATT_SUCCESS"() {

        given:
        def testSubscriber = observablePicker.call(objectUnderTest).test()

        when:
        callbackCaller.call(objectUnderTest.getBluetoothGattCallback())

        then:
        errorAssertion.call(testSubscriber)

        where:
        observablePicker << [
                { (it as RxBleGattCallback).getOnServicesDiscovered() },
                { (it as RxBleGattCallback).getOnCharacteristicRead() },
                { (it as RxBleGattCallback).getOnCharacteristicWrite() },
                { (it as RxBleGattCallback).getOnDescriptorRead() },
                { (it as RxBleGattCallback).getOnDescriptorWrite() },
                { (it as RxBleGattCallback).getOnRssiRead() },
                { (it as RxBleGattCallback).getConnectionParametersUpdates() }
        ]
        callbackCaller << [
                { (it as BluetoothGattCallback).onServicesDiscovered(mockBluetoothGatt, GATT_FAILURE) },
                { (it as BluetoothGattCallback).onCharacteristicRead(mockBluetoothGatt, mockBluetoothGattCharacteristic, GATT_FAILURE) },
                { (it as BluetoothGattCallback).onCharacteristicWrite(mockBluetoothGatt, mockBluetoothGattCharacteristic, GATT_FAILURE) },
                { (it as BluetoothGattCallback).onDescriptorRead(mockBluetoothGatt, mockBluetoothGattDescriptor, GATT_FAILURE) },
                { (it as BluetoothGattCallback).onDescriptorWrite(mockBluetoothGatt, mockBluetoothGattDescriptor, GATT_FAILURE) },
                { (it as BluetoothGattCallback).onReadRemoteRssi(mockBluetoothGatt, 1, GATT_FAILURE) },
                { (it as HiddenBluetoothGattCallback).onConnectionUpdated(mockBluetoothGatt, 1, 1, 1, GATT_FAILURE) }
        ]
        errorAssertion << [
                { (it as TestObserver).assertError { it instanceof BleGattException && it.getMacAddress() == mockBluetoothDeviceMacAddress } },
                { (it as TestObserver).assertError { it instanceof BleGattCharacteristicException && it.characteristic == mockBluetoothGattCharacteristic && it.getMacAddress() == mockBluetoothDeviceMacAddress } },
                { (it as TestObserver).assertError { it instanceof BleGattCharacteristicException && it.characteristic == mockBluetoothGattCharacteristic && it.getMacAddress() == mockBluetoothDeviceMacAddress } },
                { (it as TestObserver).assertError { it instanceof BleGattDescriptorException && it.descriptor == mockBluetoothGattDescriptor && it.getMacAddress() == mockBluetoothDeviceMacAddress } },
                { (it as TestObserver).assertError { it instanceof BleGattDescriptorException && it.descriptor == mockBluetoothGattDescriptor && it.getMacAddress() == mockBluetoothDeviceMacAddress } },
                { (it as TestObserver).assertError { it instanceof BleGattException && it.getMacAddress() == mockBluetoothDeviceMacAddress } },
                { (it as TestObserver).assertError { it instanceof BleGattException && it.getMacAddress() == mockBluetoothDeviceMacAddress } }
        ]
    }

    @Unroll
    def "should transmit error on proper callback when status != BluetoothGatt.GATT_SUCCESS, subsequent calls to callbacks will work normally"() {

        given:
        def testSubscriber = givenSubscription.call(objectUnderTest).test()

        when:
        whenAction.call(objectUnderTest.getBluetoothGattCallback(), GATT_FAILURE)

        then:
        testSubscriber.assertError({ it instanceof BleGattException && ((BleGattException) it).status == GATT_FAILURE })

        when:
        def secondTestSubscriber = givenSubscription.call(objectUnderTest).test()
        whenAction.call(objectUnderTest.getBluetoothGattCallback(), GATT_SUCCESS)

        then:
        secondTestSubscriber.assertValueCount(1)

        where:
        givenSubscription << [
                { RxBleGattCallback objectUnderTest -> objectUnderTest.getOnCharacteristicRead() },
                { RxBleGattCallback objectUnderTest -> objectUnderTest.getOnCharacteristicWrite() },
                { RxBleGattCallback objectUnderTest -> objectUnderTest.getOnDescriptorRead() },
                { RxBleGattCallback objectUnderTest -> objectUnderTest.getOnDescriptorWrite() },
                { RxBleGattCallback objectUnderTest -> objectUnderTest.getOnRssiRead() },
                { RxBleGattCallback objectUnderTest -> objectUnderTest.getOnServicesDiscovered() },
                { RxBleGattCallback objectUnderTest -> objectUnderTest.getConnectionParametersUpdates() }
        ]
        whenAction << [
                { BluetoothGattCallback callback, int status -> callback.onCharacteristicRead(mockBluetoothGatt, Mock(BluetoothGattCharacteristic), status) },
                { BluetoothGattCallback callback, int status -> callback.onCharacteristicWrite(mockBluetoothGatt, Mock(BluetoothGattCharacteristic), status) },
                { BluetoothGattCallback callback, int status -> callback.onDescriptorRead(mockBluetoothGatt, Mock(BluetoothGattDescriptor), status) },
                { BluetoothGattCallback callback, int status -> callback.onDescriptorWrite(mockBluetoothGatt, Mock(BluetoothGattDescriptor), status) },
                { BluetoothGattCallback callback, int status -> callback.onReadRemoteRssi(mockBluetoothGatt, 0, status) },
                { BluetoothGattCallback callback, int status -> callback.onServicesDiscovered(mockBluetoothGatt, status) },
                { BluetoothGattCallback callback, int status -> (callback as HiddenBluetoothGattCallback).onConnectionUpdated(mockBluetoothGatt, 1, 1, 1, status) }
        ]
    }
}
