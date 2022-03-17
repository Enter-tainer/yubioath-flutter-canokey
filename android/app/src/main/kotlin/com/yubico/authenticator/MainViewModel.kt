package com.yubico.authenticator

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yubico.authenticator.api.Pigeon
import com.yubico.authenticator.data.device.toJson
import com.yubico.authenticator.data.oath.idAsString
import com.yubico.authenticator.data.oath.toJson
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import com.yubico.yubikit.core.Logger
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.core.util.Result
import com.yubico.yubikit.oath.CredentialData
import com.yubico.yubikit.oath.OathSession
import com.yubico.yubikit.support.DeviceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class YubiKeyAction(
    val message: String,
    val action: suspend (Result<YubiKeyDevice, Exception>) -> Unit
)

enum class OperationContext(val value: Long) {
    Oath(0), Yubikey(1), Invalid(-1);

    companion object {
        fun getByValue(value: Long) = values().firstOrNull { it.value == value } ?: Invalid
    }
}

class MainViewModel : ViewModel() {

    private val _handleYubiKey = MutableLiveData(true)
    val handleYubiKey: LiveData<Boolean> = _handleYubiKey

    val yubiKeyDevice = MutableLiveData<YubiKeyDevice?>()
    private var isUsbKeyConnected: Boolean = false

    private var _oathSessionPassword: CharArray? = null

    private var _operationContext = OperationContext.Oath

    private lateinit var _fOathApi: Pigeon.FOathApi
    private lateinit var _fManagementApi: Pigeon.FManagementApi
    private lateinit var _fDialogApi: Pigeon.FDialogApi

    fun setContext(value: OperationContext) {
        _operationContext = value
        Logger.d("Operation context is now $_operationContext")
    }

    fun setFOathApi(oathApi: Pigeon.FOathApi) {
        _fOathApi = oathApi
    }

    fun setFManagementApi(managementApi: Pigeon.FManagementApi) {
        _fManagementApi = managementApi
    }

    fun setFDialogApi(dialogApi: Pigeon.FDialogApi) {
        _fDialogApi = dialogApi
    }

    private suspend fun sendDeviceInfo(device: YubiKeyDevice) {

        val deviceInfoData = suspendCoroutine<String> {
            device.requestConnection(SmartCardConnection::class.java) { result ->
                try {
                    val pid = (device as? UsbYubiKeyDevice)?.pid
                    val deviceInfo = DeviceUtil.readInfo(result.value, pid)
                    val name = DeviceUtil.getName(deviceInfo, pid?.type)

                    val deviceInfoData = deviceInfo
                        .toJson(name, device is NfcYubiKeyDevice)
                        .toString()
                    it.resume(deviceInfoData)
                } catch (cause: Throwable) {
                    Logger.e("Failed to get device info", cause)
                    it.resumeWithException(cause)
                }
            }
        }

        _fManagementApi.updateDeviceInfo(deviceInfoData) {}
    }

    private suspend fun sendOathInfo(device: YubiKeyDevice) {

        val oathSessionData = suspendCoroutine<String> {
            device.requestConnection(SmartCardConnection::class.java) { result ->
                val oathSession = OathSession(result.value)
                val isRemembered = false
                val oathSessionData = oathSession
                    .toJson(isRemembered)
                    .toString()
                it.resume(oathSessionData)
            }
        }

        _fOathApi.updateSession(oathSessionData) {}
    }

    private suspend fun sendOathCodes(device: YubiKeyDevice) {
        val sendOathCodes = suspendCoroutine<String> {
            device.requestConnection(SmartCardConnection::class.java) { result ->
                val session = OathSession(result.value)

                val isLocked = isOathSessionLocked(session)
                if (!isLocked) {
                    val resultJson = session.calculateCodes()
                        .toJson(session.deviceId)
                        .toString()
                    it.resume(resultJson)
                }
            }
        }

        _fOathApi.updateOathCredentials(sendOathCodes) {}
    }

    private val _pendingYubiKeyAction = MutableLiveData<YubiKeyAction?>()
    private val pendingYubiKeyAction: LiveData<YubiKeyAction?> = _pendingYubiKeyAction

    private suspend fun provideYubiKey(result: Result<YubiKeyDevice, Exception>) =
        withContext(Dispatchers.IO) {
            pendingYubiKeyAction.value?.let {
                _pendingYubiKeyAction.postValue(null)
                if (!isUsbKeyConnected) {
                    withContext(Dispatchers.Main) {
                        requestHideDialog()
                    }
                }
                it.action.invoke(result)
            }
        }

    suspend fun yubikeyAttached(device: YubiKeyDevice) {

        isUsbKeyConnected = device is UsbYubiKeyDevice

        withContext(Dispatchers.IO) {
            if (pendingYubiKeyAction.value != null) {
                provideYubiKey(Result.success(device))
            } else {
                withContext(Dispatchers.Main) {
                    when (_operationContext) {
                        OperationContext.Oath -> {
                            try {
                                sendDeviceInfo(device)
                            } catch (cause: Throwable) {
                                Logger.e("Failed to send device info", cause)
                            }
                            sendOathInfo(device)
                            sendOathCodes(device)
                        }
                        OperationContext.Yubikey -> {
                            sendDeviceInfo(device)
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    fun yubikeyDetached() {

        if (isUsbKeyConnected) {
            // forget the current password only for usb keys
            _oathSessionPassword = null
            _fManagementApi.updateDeviceInfo("") {}
        }

    }

    fun onDialogClosed(result: Pigeon.Result<Void>) {
        viewModelScope.launch {
            try {
                provideYubiKey(Result.failure(Exception("User canceled")))
                result.success(null)
            } catch (cause: Throwable) {
                Logger.d("failed")
                result.error(Exception("Failed to close dialog during User cancel action"))
            }
        }
    }

    // requests flutter to show a dialog
    private fun requestShowDialog(message: String) =
        _fDialogApi.showDialogApi(message) { }

    private fun requestHideDialog() {
        _fDialogApi.closeDialogApi { }
    }

    private fun <T> withUnlockedSession(session: OathSession, block: (OathSession) -> T): T {
        val isLocked = isOathSessionLocked(session)
        if (isLocked) {
            throw Exception("Session is locked")
        }
        return block(session)
    }

    private fun getOathCredential(oathSession: OathSession, credentialId: String) =
        oathSession.credentials.firstOrNull { credential ->
            (credential != null) && credential.idAsString() == credentialId
        } ?: throw Exception("Failed to find account to delete")


    fun deleteAccount(credentialId: String, result: Pigeon.Result<Void>) {
        viewModelScope.launch(Dispatchers.IO) {
            useOathSession("Delete account", true) { session ->
                withUnlockedSession(session) {
                    val credential = getOathCredential(session, credentialId)
                    session.deleteCredential(credential)
                    result.success(null)
                }
            }
        }
    }

    fun addAccount(otpUri: String, requireTouch: Boolean, result: Pigeon.Result<String>) {

        viewModelScope.launch(Dispatchers.IO) {
            try {
                useOathSession("Add account", true) { session ->
                    withUnlockedSession(session) {
                        val credentialData: CredentialData =
                            CredentialData.parseUri(URI.create(otpUri))

                        val jsonResult = session
                            .putCredential(credentialData, requireTouch)
                            .toJson(session.deviceId)
                            .toString()
                        result.success(jsonResult)
                    }
                }
            } catch (cause: Throwable) {
                result.error(cause)
            }
        }
    }

    fun renameCredential(
        credentialId: String,
        name: String,
        issuer: String?,
        result: Pigeon.Result<String>
    ) {

        viewModelScope.launch(Dispatchers.IO) {
            try {
                useOathSession("Rename", true) { session ->
                    withUnlockedSession(session) {
                        val credential = getOathCredential(session, credentialId)

                        val jsonResult =
                            session.renameCredential(credential, name, issuer)
                                .toJson(session.deviceId)
                                .toString()

                        result.success(jsonResult)
                    }
                }
            } catch (cause: Throwable) {
                result.error(cause)
            }
        }
    }

    fun setOathPassword(current: String?, password: String, result: Pigeon.Result<Void>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                useOathSession("Set password", true) { session ->
                    if (session.isAccessKeySet) {
                        if (current == null) {
                            throw Exception("Must provide current password to be able to change it")
                        }
                        // test current password sent by the user
                        if (!session.unlock(current.toCharArray())) {
                            throw Exception("Provided current password is invalid")
                        }
                    }
                    val newPass = password.toCharArray()
                    session.setPassword(newPass)
                    _oathSessionPassword = newPass
                    Logger.d("Successfully set password")
                }
            } catch (cause: Throwable) {
                result.error(cause)
            }
        }
    }


    fun unsetOathPassword(currentPassword: String, result: Pigeon.Result<Void>) {

        viewModelScope.launch(Dispatchers.IO) {
            try {
                useOathSession("Unset password", true) { session ->
                    if (session.isAccessKeySet) {
                        // test current password sent by the user
                        if (session.unlock(currentPassword.toCharArray())) {
                            session.deleteAccessKey()
                            _oathSessionPassword = null
                            Logger.d("Successfully unset password")
                            result.success(null)
                            return@useOathSession
                        }
                    }
                    result.error(Exception("Unset password failed"))
                }
            } catch (cause: Throwable) {
                result.error(cause)
            }
        }
    }

    fun refreshOathCodes(result: Pigeon.Result<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!isUsbKeyConnected) {
                    throw Exception("Cannot refresh for nfc key")
                }

                useOathSession("Refresh codes", false) {
                    withUnlockedSession(it) { session ->
                        val resultJson = session.calculateCodes()
                            .toJson(session.deviceId)
                            .toString()
                        result.success(resultJson)
                    }
                }
            } catch (cause: Throwable) {
                result.error(cause)
            }
        }
    }

    fun calculate(credentialId: String, result: Pigeon.Result<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                useOathSession("Calculate", true) {
                    withUnlockedSession(it) { session ->

                        val credential = getOathCredential(session, credentialId)

                        val resultJson = session.calculateCode(credential)
                            .toJson()
                            .toString()

                        result.success(resultJson)
                    }
                }
            } catch (cause: Throwable) {
                result.error(cause)
            }
        }
    }

    fun unlockOathSession(
        password: String,
        remember: Boolean,
        result: Pigeon.Result<Boolean>
    ) {

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var codes: String? = null
                useOathSession("Unlocking", true) {
                    _oathSessionPassword = password.toCharArray()
                    val isLocked = isOathSessionLocked(it)

                    if (!isLocked) {
                        codes = it.calculateCodes()
                            .toJson(it.deviceId)
                            .toString()
                    }

                    result.success(!isLocked)
                }

                codes?.let {
                    _fOathApi.updateOathCredentials(it) {}
                }

            } catch (cause: Throwable) {
                result.error(cause)
            }
        }
    }

    fun resetOathSession(result: Pigeon.Result<Void>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                useOathSession("Reset YubiKey", true) {
                    // note, it is ok to reset locked session
                    it.reset()
                    result.success(null)
                }
            } catch (e: Throwable) {
                result.error(e)
            }
        }
    }

    private suspend fun <T> useOathSession(
        title: String,
        queryUserToTap: Boolean,
        action: (OathSession) -> T
    ) = suspendCoroutine<T> { outer ->
        if (queryUserToTap && !isUsbKeyConnected) {
            viewModelScope.launch(Dispatchers.Main) {
                requestShowDialog(title)
            }
        }
        _pendingYubiKeyAction.postValue(YubiKeyAction(title) { yubiKey ->
            outer.resumeWith(runCatching {
                suspendCoroutine { inner ->
                    yubiKey.value.requestConnection(SmartCardConnection::class.java) {
                        inner.resumeWith(runCatching {
                            action.invoke(OathSession(it.value))
                        })
                    }
                }
            })
        })

        yubiKeyDevice.value?.let {
            viewModelScope.launch(Dispatchers.IO) {
                provideYubiKey(Result.success(it))
            }
        }
    }

    /**
     * returns true if the session cannot be unlocked (either we don't have a password, or the password is incorrect
     *
     * returns false if we can unlock the session
     */
    private fun isOathSessionLocked(session: OathSession): Boolean {
        if (!session.isLocked) {
            return false
        }

        if (_oathSessionPassword == null) {
            return true // we have no password to unlock
        }

        val unlockSucceed = session.unlock(_oathSessionPassword!!)

        if (unlockSucceed) {
            return false // we have everything to unlock the session
        }

        _oathSessionPassword = null // reset the password as well as it did not work
        return true // the unlock did not work, session is locked
    }

    fun forgetPassword(result: Pigeon.Result<Void>) {
        result.success(null)
    }

}
