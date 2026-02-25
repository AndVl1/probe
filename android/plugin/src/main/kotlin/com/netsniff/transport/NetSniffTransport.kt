package com.netsniff.transport

import com.netsniff.model.HttpTransaction

/**
 * Transport interface — extensible. Implement to add new transport mechanisms
 * (e.g., gRPC, file logging, cloud, etc.)
 */
interface NetSniffTransport {
    fun connect()
    fun send(transaction: HttpTransaction)
    fun disconnect()
    val isConnected: Boolean
}
