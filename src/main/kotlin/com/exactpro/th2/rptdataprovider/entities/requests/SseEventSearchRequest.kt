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

package com.exactpro.th2.rptdataprovider.entities.requests

import com.exactpro.cradle.TimeRelation
import com.exactpro.th2.rptdataprovider.entities.exceptions.InvalidRequestException
import com.exactpro.th2.rptdataprovider.entities.filters.FilterPredicate
import com.exactpro.th2.rptdataprovider.entities.responses.EventTreeNode
import java.time.Instant
import java.util.concurrent.TimeUnit

data class SseEventSearchRequest(
    val filterPredicate: FilterPredicate<EventTreeNode>,
    val startTimestamp: Instant,
    val parentEvent: String?,
    val searchDirection: TimeRelation,
    val resultCountLimit: Int,
    val endTimestamp: Instant?
) {
    companion object {
        private fun asCradleTimeRelation(value: String): TimeRelation {
            if (value == "next") return TimeRelation.AFTER
            if (value == "previous") return TimeRelation.BEFORE

            throw InvalidRequestException("'$value' is not a valid timeline direction. Use 'next' or 'previous'")
        }
    }

    constructor(parameters: Map<String, List<String>>, filterPredicate: FilterPredicate<EventTreeNode>) : this(
        filterPredicate = filterPredicate,
        startTimestamp = parameters["startTimestamp"]?.first()?.let { Instant.ofEpochMilli(it.toLong()) }!!,
        parentEvent = parameters["parentEvent"]?.first(),
        searchDirection = parameters["searchDirection"]?.let {
            asCradleTimeRelation(it.first())
        } ?: TimeRelation.AFTER,
        resultCountLimit = parameters["resultCountLimit"]?.first()?.toInt() ?: 100,
        endTimestamp = parameters["endTimestamp"]?.first()?.let { Instant.ofEpochMilli(it.toLong()) }
    )
}


fun SseEventSearchRequest.checkEndTimestamp() {
    if (endTimestamp == null) return

    if (searchDirection == TimeRelation.AFTER) {
        if (startTimestamp.isAfter(endTimestamp))
            throw InvalidRequestException("startTimestamp: $startTimestamp > endTimestamp: $endTimestamp")
    } else {
        if (startTimestamp.isBefore(endTimestamp))
            throw InvalidRequestException("startTimestamp: $startTimestamp < endTimestamp: $endTimestamp")
    }
}