/*
 * Copyright 2014 - 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "Publication.h"
#include "ClientConductor.h"

namespace aeron {

Publication::Publication(
    ClientConductor &conductor,
    const std::string &channel,
    std::int64_t registrationId,
    std::int32_t streamId,
    std::int32_t sessionId,
    UnsafeBufferPosition& publicationLimit,
    LogBuffers &buffers)
    :
    m_conductor(conductor),
    m_logMetaDataBuffer(buffers.atomicBuffer(LogBufferDescriptor::LOG_META_DATA_SECTION_INDEX)),
    m_channel(channel),
    m_registrationId(registrationId),
    m_streamId(streamId),
    m_sessionId(sessionId),
    m_initialTermId(LogBufferDescriptor::initialTermId(m_logMetaDataBuffer)),
    m_maxPayloadLength(LogBufferDescriptor::mtuLength(m_logMetaDataBuffer) - DataFrameHeader::LENGTH),
    m_positionBitsToShift(util::BitUtil::numberOfTrailingZeroes(buffers.atomicBuffer(0).capacity())),
    m_publicationLimit(publicationLimit),
    m_headerWriter(LogBufferDescriptor::defaultFrameHeader(m_logMetaDataBuffer))
{
    for (int i = 0; i < LogBufferDescriptor::PARTITION_COUNT; i++)
    {
        /*
         * TODO:
         * perhaps allow copy-construction and be able to move appenders and AtomicBuffers directly into Publication for
         * locality.
         */
        m_appenders[i] = std::unique_ptr<TermAppender>(new TermAppender(
            buffers.atomicBuffer(i),
            buffers.atomicBuffer(i + LogBufferDescriptor::PARTITION_COUNT)));
    }
}

Publication::~Publication()
{
    m_conductor.releasePublication(m_registrationId);
}

bool Publication::isStillConnected()
{
    return m_conductor.isPublicationConnected(LogBufferDescriptor::timeOfLastSm(m_logMetaDataBuffer));
}

}