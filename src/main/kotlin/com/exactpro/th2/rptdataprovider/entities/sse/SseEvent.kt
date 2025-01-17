/*******************************************************************************
 * Copyright 2020-2021 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.exactpro.th2.rptdataprovider.entities.sse

import com.exactpro.cradle.Direction
import com.exactpro.cradle.messages.StoredMessageId
import com.exactpro.th2.rptdataprovider.asStringSuspend
import com.exactpro.th2.rptdataprovider.entities.internal.ProviderEventId
import com.exactpro.th2.rptdataprovider.entities.responses.*
import com.exactpro.th2.rptdataprovider.handlers.SearchMessagesHandler
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Timestamp
import io.ktor.util.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong


enum class EventType {
    MESSAGE, EVENT, CLOSE, ERROR, KEEP_ALIVE, MESSAGE_IDS;

    override fun toString(): String {
        return super.toString().toLowerCase()
    }
}

data class LastScannedObjectInfo(var id: String = "", var timestamp: Long = 0, var scanCounter: Long = 0) {

    fun update(lastTimestamp: Instant) {
        timestamp = lastTimestamp.toEpochMilli()
    }

    fun update(event: BaseEventEntity, scanCnt: AtomicLong) {
        id = event.id.toString()
        timestamp = event.startTimestamp.toEpochMilli()
        scanCounter = scanCnt.incrementAndGet()
    }

    fun update(message: Message, scanCnt: AtomicLong) {
        id = message.id.toString()
        timestamp = message.timestamp.toEpochMilli()
        scanCounter = scanCnt.incrementAndGet()
    }

    fun convertToGrpc(): com.exactpro.th2.dataprovider.grpc.LastScannedObjectInfo {
        return com.exactpro.th2.dataprovider.grpc.LastScannedObjectInfo.newBuilder()
            .setId(id)
            .setTimestampMillis(timestamp)
            .setScanCounter(scanCounter)
            .build()
    }
}

data class ExceptionInfo(val exceptionName: String, val exceptionCause: String)

/**
 * The data class representing a SSE Event that will be sent to the client.
 */

data class SseEvent(val data: String = "empty data", val event: EventType? = null, val metadata: String? = null) {
    companion object {
        suspend fun build(jacksonMapper: ObjectMapper, event: EventTreeNode, counter: AtomicLong): SseEvent {
            return SseEvent(
                jacksonMapper.asStringSuspend(event),
                EventType.EVENT,
                counter.incrementAndGet().toString()
            )
        }

        suspend fun build(jacksonMapper: ObjectMapper, event: Event, counter: AtomicLong): SseEvent {
            return SseEvent(
                jacksonMapper.asStringSuspend(event),
                EventType.EVENT,
                counter.incrementAndGet().toString()
            )
        }

        suspend fun build(jacksonMapper: ObjectMapper, message: Message, counter: AtomicLong): SseEvent {
            return SseEvent(
                jacksonMapper.asStringSuspend(message),
                EventType.MESSAGE,
                counter.incrementAndGet().toString()
            )
        }

        suspend fun build(
            jacksonMapper: ObjectMapper,
            lastScannedObjectInfo: LastScannedObjectInfo,
            counter: AtomicLong
        ): SseEvent {
            return SseEvent(
                data = jacksonMapper.asStringSuspend(lastScannedObjectInfo),
                event = EventType.KEEP_ALIVE,
                metadata = counter.incrementAndGet().toString()
            )
        }

        @InternalAPI
        suspend fun build(jacksonMapper: ObjectMapper, e: Exception): SseEvent {
            return SseEvent(
                jacksonMapper.asStringSuspend(ExceptionInfo(e.javaClass.name, e.rootCause?.message ?: e.toString())),
                event = EventType.ERROR
            )
        }

        suspend fun build(
            jacksonMapper: ObjectMapper,
            streamsInfo: List<StreamInfo>
        ): SseEvent {
            return SseEvent(
                jacksonMapper.asStringSuspend(
                    mapOf(
                        "messageIds" to streamsInfo.associate { it.stream to it.lastElement?.toString() }
                    )
                ),
                event = EventType.MESSAGE_IDS
            )
        }

        suspend fun build(
            jacksonMapper: ObjectMapper,
            lastIdInStream: Map<Pair<String, Direction>, StoredMessageId?>
        ): SseEvent {
            return SseEvent(
                jacksonMapper.asStringSuspend(
                    mapOf(
                        "messageIds" to lastIdInStream.entries.associate { it.key to it.value?.toString() }
                    )
                ),
                event = EventType.MESSAGE_IDS
            )
        }
    }
}


