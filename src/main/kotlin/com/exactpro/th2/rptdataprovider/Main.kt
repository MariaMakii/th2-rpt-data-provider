/*******************************************************************************
 * Copyright 2020-2020 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.rptdataprovider

import com.exactpro.cradle.utils.CradleIdException
import com.exactpro.th2.rptdataprovider.entities.exceptions.ChannelClosedException
import com.exactpro.th2.rptdataprovider.entities.exceptions.InvalidRequestException
import com.exactpro.th2.rptdataprovider.entities.requests.EventSearchRequest
import com.exactpro.th2.rptdataprovider.entities.requests.MessageSearchRequest
import com.exactpro.th2.rptdataprovider.entities.requests.SseEventSearchRequest
import com.exactpro.th2.rptdataprovider.entities.requests.SseMessageSearchRequest
import com.exactpro.th2.rptdataprovider.entities.sse.EventType
import com.exactpro.th2.rptdataprovider.entities.sse.SseEvent
import com.exactpro.th2.rptdataprovider.services.cradle.CradleObjectNotFoundException
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.io.Writer
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlin.system.measureTimeMillis

class Main(args: Array<String>) {

    private val logger = KotlinLogging.logger {}

    private val context = Context(args)
    private val jacksonMapper = context.jacksonMapper
    private val checkRequestAliveDelay = context.configuration.checkRequestsAliveDelay.value.toLong()
    private val configuration = context.configuration

    private class Timeouts {
        class Config(var requestTimeout: Long = 5000L, var excludes: List<String> = listOf("sse"))

        companion object : ApplicationFeature<ApplicationCallPipeline, Config, Unit> {
            override val key: AttributeKey<Unit> = AttributeKey("Timeouts")

            override fun install(pipeline: ApplicationCallPipeline, configure: Config.() -> Unit) {
                val config = Config().apply(configure)
                val timeout = config.requestTimeout
                val excludes = config.excludes

                if (timeout <= 0) return

                pipeline.intercept(ApplicationCallPipeline.Features) {
                    if (excludes.any { call.request.uri.contains(it) }) return@intercept
                    withTimeout(timeout) {
                        proceed()
                    }
                }
            }
        }
    }

    @EngineAPI
    @InternalAPI
    suspend fun checkContext(context: ApplicationCall) {
        context.javaClass.getDeclaredField("call").also {
            it.trySetAccessible()
            val nettyApplicationRequest = it.get(context) as NettyApplicationCall

            while (coroutineContext.isActive) {
                if (nettyApplicationRequest.context.isRemoved)
                    throw ChannelClosedException("Channel is closed")

                delay(checkRequestAliveDelay)
            }
        }
    }

    @InternalAPI
    private suspend fun sendErrorCode(call: ApplicationCall, e: Exception, code: HttpStatusCode) {
        call.respondText(e.rootCause?.message ?: e.toString(), ContentType.Text.Plain, code)
    }

    @InternalAPI
    private suspend fun sendErrorCodeOrEmptyJson(
        probe: Boolean,
        call: ApplicationCall,
        e: Exception,
        code: HttpStatusCode
    ) {
        if (probe) {
            call.respondText(
                jacksonMapper.writeValueAsString(null), ContentType.Application.Json
            )
        } else {
            sendErrorCode(call, e, code)
        }
    }

    @ExperimentalCoroutinesApi
    @EngineAPI
    @InternalAPI
    private suspend fun handleRequest(
        call: ApplicationCall,
        context: ApplicationCall,
        requestName: String,
        cacheControl: CacheControl?,
        probe: Boolean,
        useSse: Boolean,
        vararg parameters: Any?,
        calledFun: suspend () -> Any
    ) {
        val stringParameters = parameters.contentDeepToString()
        coroutineScope {
            measureTimeMillis {
                logger.debug { "handling '$requestName' request with parameters '$stringParameters'" }
                try {
                    try {
                        if (useSse) {
                            val function = calledFun.invoke()
                            @Suppress("UNCHECKED_CAST")
                            handleSseRequest(call, context, function as suspend (Writer) -> Unit)
                        } else {
                            handleRestApiRequest(call, context, cacheControl, probe, calledFun)
                        }
                    } catch (e: Exception) {
                        throw e.rootCause ?: e
                    }
                } catch (e: InvalidRequestException) {
                    logger.error(e) { "unable to handle request '$requestName' with parameters '$stringParameters' - invalid request" }
                } catch (e: CradleObjectNotFoundException) {
                    logger.error(e) { "unable to handle request '$requestName' with parameters '$stringParameters' - missing cradle data" }
                } catch (e: ChannelClosedException) {
                    logger.debug { "unable to handle request '$requestName' with parameters '$stringParameters' - channel closed" }
                } catch (e: Exception) {
                    logger.error(e) { "unable to handle request '$requestName' with parameters '$stringParameters' - unexpected exception" }
                }
            }.let { logger.debug { "request '$requestName' with parameters '$stringParameters' handled - time=${it}ms" } }
        }
    }

    @ExperimentalCoroutinesApi
    @EngineAPI
    @InternalAPI
    private suspend fun handleSseRequest(
        call: ApplicationCall,
        context: ApplicationCall,
        calledFun: suspend (Writer) -> Unit
    ) {
        coroutineScope {
            launch {
                launch {
                    checkContext(context)
                }
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        calledFun.invoke(this)
                    } catch (e: Exception) {
                        val errorCode = when (e) {
                            is InvalidRequestException -> HttpStatusCode.BadRequest
                            is CradleObjectNotFoundException -> HttpStatusCode.NotFound
                            is ChannelClosedException -> HttpStatusCode.RequestTimeout
                            else -> HttpStatusCode.InternalServerError
                        }.toString()
                        eventWrite(SseEvent(errorCode, event = EventType.ERROR))
                        throw e
                    } finally {
                        eventWrite(SseEvent(event = EventType.CLOSE))
                        asyncClose()
                    }
                }
                coroutineContext.cancelChildren()
            }.join()
        }
    }


    @ExperimentalCoroutinesApi
    @EngineAPI
    @InternalAPI
    private suspend fun handleRestApiRequest(
        call: ApplicationCall,
        context: ApplicationCall,
        cacheControl: CacheControl?,
        probe: Boolean,
        calledFun: suspend () -> Any
    ) {
        coroutineScope {
            try {
                launch {
                    launch {
                        checkContext(context)
                    }
                    cacheControl?.let { call.response.cacheControl(it) }
                    call.respondText(
                        jacksonMapper.asStringSuspend(calledFun.invoke()),
                        ContentType.Application.Json
                    )
                    coroutineContext.cancelChildren()
                }.join()
            } catch (e: Exception) {
                when (val exception = e.rootCause ?: e) {
                    is InvalidRequestException -> sendErrorCode(call, exception, HttpStatusCode.BadRequest)
                    is CradleObjectNotFoundException -> sendErrorCodeOrEmptyJson(
                        probe, call, exception, HttpStatusCode.NotFound
                    )
                    is ChannelClosedException -> sendErrorCode(call, exception, HttpStatusCode.RequestTimeout)
                    is CradleIdException -> sendErrorCodeOrEmptyJson(probe, call, e, HttpStatusCode.InternalServerError)
                    else -> sendErrorCode(call, exception as Exception, HttpStatusCode.InternalServerError)
                }
                throw e
            }
        }
    }

    private fun inPast(rightTimeBoundary: Instant?): Boolean {
        return rightTimeBoundary?.isBefore(Instant.now()) != false
    }

    @FlowPreview
    @ExperimentalCoroutinesApi
    @EngineAPI
    @InternalAPI
    fun run() {

        val notModifiedCacheControl = this.context.cacheControlNotModified
        val rarelyModifiedCacheControl = this.context.cacheControlRarelyModified
        val frequentlyModifiedCacheControl = this.context.cacheControlFrequentlyModified

        val cradleService = this.context.cradleService

        val eventCache = this.context.eventCache
        val messageCache = this.context.messageCache

        val searchEventsHandler = this.context.searchEventsHandler
        val searchMessagesHandler = this.context.searchMessagesHandler

        val eventFiltersPredicateFactory = this.context.eventFiltersPredicateFactory
        val messageFiltersPredicateFactory = this.context.messageFiltersPredicateFactory

        val sseEventSearchStep = this.context.sseEventSearchStep

        System.setProperty(IO_PARALLELISM_PROPERTY_NAME, configuration.ioDispatcherThreadPoolSize.value)

        embeddedServer(Netty, configuration.port.value.toInt()) {

            install(Compression)
            install(Timeouts) {
                requestTimeout = context.timeout
            }

            routing {

                get("/event/{id}") {
                    val probe = call.parameters["probe"]?.toBoolean() ?: false
                    handleRequest(
                        call, context, "get single event", notModifiedCacheControl, probe,
                        false, call.parameters.toMap()
                    ) {
                        eventCache.getOrPut(call.parameters["id"]!!)
                    }
                }

                get("/messageStreams") {
                    handleRequest(
                        call, context, "get message streams", rarelyModifiedCacheControl,
                        probe = false, useSse = false
                    ) {
                        cradleService.getMessageStreams()
                    }
                }

                get("/message/{id}") {
                    val probe = call.parameters["probe"]?.toBoolean() ?: false
                    handleRequest(
                        call, context, "get single message",
                        notModifiedCacheControl, probe, false, call.parameters.toMap()
                    ) {
                        messageCache.getOrPut(call.parameters["id"]!!)
                    }
                }

                get("/search/messages") {
                    val queryParametersMap = call.request.queryParameters.toMap()
                    val probe = call.parameters["probe"]?.toBoolean() ?: false
                    handleRequest(call, context, "search messages", null, probe, false, queryParametersMap) {
                        val request = MessageSearchRequest(queryParametersMap)
                        searchMessagesHandler.searchMessages(request)
                            .also {
                                call.response.cacheControl(
                                    if (it.size == request.limit || inPast(request.timestampTo)) {
                                        notModifiedCacheControl
                                    } else {
                                        frequentlyModifiedCacheControl
                                    }
                                )
                            }
                    }
                }

                get("search/sse/messages") {
                    val queryParametersMap = call.request.queryParameters.toMap()
                    handleRequest(call, context, "search messages sse", null, false, true, queryParametersMap) {
                        suspend fun(w: Writer) {
                            val filterPredicate = messageFiltersPredicateFactory.build(queryParametersMap)
                            val request = SseMessageSearchRequest(queryParametersMap, filterPredicate)
                            searchMessagesHandler.searchMessagesSse(request, jacksonMapper, w)
                        }
                    }
                }

                get("search/events") {
                    val queryParametersMap = call.request.queryParameters.toMap()
                    val probe = call.parameters["probe"]?.toBoolean() ?: false
                    handleRequest(call, context, "search events", null, probe, false, queryParametersMap) {
                        val request = EventSearchRequest(queryParametersMap)
                        searchEventsHandler.searchEvents(request)
                            .also { call.response.cacheControl(frequentlyModifiedCacheControl) }
                    }
                }

                get("search/sse/events") {
                    val queryParametersMap = call.request.queryParameters.toMap()
                    handleRequest(call, context, "search events sse", null, false, true, queryParametersMap) {
                        suspend fun(w: Writer) {
                            val filterPredicate =
                                eventFiltersPredicateFactory.build(queryParametersMap)
                            val request = SseEventSearchRequest(queryParametersMap, filterPredicate)
                            searchEventsHandler.searchEventsSse(request, jacksonMapper, sseEventSearchStep, w)
                        }
                    }
                }

                get("filters/sse-messages") {
                    val queryParametersMap = call.request.queryParameters.toMap()
                    handleRequest(call, context, "get message filters", null, false, false, queryParametersMap) {
                        messageFiltersPredicateFactory.getFiltersNames()
                    }
                }

                get("filters/sse-events") {
                    val queryParametersMap = call.request.queryParameters.toMap()
                    handleRequest(call, context, "get event filters", null, false, false, queryParametersMap) {
                        eventFiltersPredicateFactory.getFiltersNames()
                    }
                }

                get("filters/sse-messages/{name}") {
                    val queryParametersMap = call.request.queryParameters.toMap()
                    handleRequest(call, context, "get message filters", null, false, false, queryParametersMap) {
                        messageFiltersPredicateFactory.getFilterInfo(call.parameters["name"]!!)
                    }
                }


                get("filters/sse-events/{name}") {
                    val queryParametersMap = call.request.queryParameters.toMap()
                    handleRequest(call, context, "get event filters", null, false, false, queryParametersMap) {
                        eventFiltersPredicateFactory.getFilterInfo(call.parameters["name"]!!)
                    }
                }
            }
        }.start(false)

        logger.info { "serving on: http://${configuration.hostname.value}:${configuration.port.value}" }
    }
}


@FlowPreview
@EngineAPI
@InternalAPI
@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    Main(args).run()
}
