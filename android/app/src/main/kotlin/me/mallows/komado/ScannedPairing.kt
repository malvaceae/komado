package me.mallows.komado

import me.mallows.komado.protocol.PairingInvitation

/** One in-process result; pairing secrets are never placed in an Intent. */
internal object ScannedPairing {
    private var value: PairingInvitation? = null
    @Synchronized fun put(invitation: PairingInvitation) { value = invitation }
    @Synchronized fun take(): PairingInvitation? = value.also { value = null }
    @Synchronized fun clear() { value = null }
}
