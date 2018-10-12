/*
 * Copyright 2014-2018 Real Logic Ltd.
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
package io.aeron.cluster;

import io.aeron.ChannelUriStringBuilder;
import io.aeron.DirectBufferVector;
import io.aeron.Publication;
import io.aeron.cluster.codecs.*;
import io.aeron.exceptions.AeronException;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import static io.aeron.CommonContext.UDP_MEDIA;

class LogPublisher
{
    private static final int SEND_ATTEMPTS = 3;
    public static final int SESSION_HEADER_LENGTH =
        MessageHeaderEncoder.ENCODED_LENGTH + SessionHeaderEncoder.BLOCK_LENGTH;

    private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
    private final SessionHeaderEncoder sessionHeaderEncoder = new SessionHeaderEncoder();
    private final SessionOpenEventEncoder sessionOpenEventEncoder = new SessionOpenEventEncoder();
    private final SessionCloseEventEncoder sessionCloseEventEncoder = new SessionCloseEventEncoder();
    private final TimerEventEncoder timerEventEncoder = new TimerEventEncoder();
    private final ClusterActionRequestEncoder clusterActionRequestEncoder = new ClusterActionRequestEncoder();
    private final NewLeadershipTermEventEncoder newLeadershipTermEventEncoder = new NewLeadershipTermEventEncoder();
    private final ClusterChangeEventEncoder clusterChangeEventEncoder = new ClusterChangeEventEncoder();
    private final ExpandableArrayBuffer expandableArrayBuffer = new ExpandableArrayBuffer();
    private final BufferClaim bufferClaim = new BufferClaim();
    private final DirectBufferVector[] vectors = new DirectBufferVector[2];
    private final DirectBufferVector messageVector = new DirectBufferVector();
    private Publication publication;

    LogPublisher()
    {
        final UnsafeBuffer headerBuffer = new UnsafeBuffer(new byte[SESSION_HEADER_LENGTH]);
        sessionHeaderEncoder.wrapAndApplyHeader(headerBuffer, 0, new MessageHeaderEncoder());

        vectors[0] = new DirectBufferVector(headerBuffer, 0, SESSION_HEADER_LENGTH);
        vectors[1] = messageVector;
    }

    void connect(final Publication publication)
    {
        this.publication = publication;
    }

    void disconnect()
    {
        if (null != publication)
        {
            publication.close();
            publication = null;
        }
    }

    long position()
    {
        if (null == publication)
        {
            return 0;
        }

        return publication.position();
    }

    int sessionId()
    {
        return publication.sessionId();
    }

    void addPassiveFollower(final String followerLogEndpoint)
    {
        if (null != publication)
        {
            final ChannelUriStringBuilder builder = new ChannelUriStringBuilder().media(UDP_MEDIA);

            publication.addDestination(builder.endpoint(followerLogEndpoint).build());
        }
    }

    void removePassiveFollower(final String followerLogEndpoint)
    {
        if (null != publication)
        {
            final ChannelUriStringBuilder builder = new ChannelUriStringBuilder().media(UDP_MEDIA);

            publication.removeDestination(builder.endpoint(followerLogEndpoint).build());
        }
    }

    boolean appendMessage(
        final long correlationId,
        final long clusterSessionId,
        final long timestampMs,
        final DirectBuffer buffer,
        final int offset,
        final int length)
    {
        sessionHeaderEncoder
            .correlationId(correlationId)
            .clusterSessionId(clusterSessionId)
            .timestamp(timestampMs);

        messageVector.reset(buffer, offset, length);

        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = publication.offer(vectors, null);
            if (result > 0)
            {
                return true;
            }

            checkResult(result);
        }
        while (--attempts > 0);

        return false;
    }

    long appendSessionOpen(final ClusterSession session, final long nowMs)
    {
        long result;
        final byte[] encodedPrincipal = session.encodedPrincipal();
        final String channel = session.responseChannel();

        sessionOpenEventEncoder
            .wrapAndApplyHeader(expandableArrayBuffer, 0, messageHeaderEncoder)
            .clusterSessionId(session.id())
            .correlationId(session.lastCorrelationId())
            .timestamp(nowMs)
            .responseStreamId(session.responseStreamId())
            .responseChannel(channel)
            .putEncodedPrincipal(encodedPrincipal, 0, encodedPrincipal.length);

        final int length = sessionOpenEventEncoder.encodedLength() + MessageHeaderEncoder.ENCODED_LENGTH;

        int attempts = SEND_ATTEMPTS;
        do
        {
            result = publication.offer(expandableArrayBuffer, 0, length);
            if (result > 0)
            {
                return result;
            }

            checkResult(result);
        }
        while (--attempts > 0);

        return result;
    }

    boolean appendSessionClose(final ClusterSession session, final long nowMs)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + SessionCloseEventEncoder.BLOCK_LENGTH;

        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = publication.tryClaim(length, bufferClaim);
            if (result > 0)
            {
                sessionCloseEventEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .clusterSessionId(session.id())
                    .timestamp(nowMs)
                    .closeReason(session.closeReason());

                bufferClaim.commit();

                return true;
            }

            checkResult(result);
        }
        while (--attempts > 0);

        return false;
    }

    boolean appendTimer(final long correlationId, final long nowMs)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + TimerEventEncoder.BLOCK_LENGTH;

        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = publication.tryClaim(length, bufferClaim);
            if (result > 0)
            {
                timerEventEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .correlationId(correlationId)
                    .timestamp(nowMs);

                bufferClaim.commit();

                return true;
            }

            checkResult(result);
        }
        while (--attempts > 0);

        return false;
    }

    boolean appendClusterAction(
        final long leadershipTermId, final long logPosition, final long nowMs, final ClusterAction action)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + ClusterActionRequestEncoder.BLOCK_LENGTH;

        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = publication.tryClaim(length, bufferClaim);
            if (result > 0)
            {
                clusterActionRequestEncoder.wrapAndApplyHeader(
                    bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .logPosition(logPosition)
                    .leadershipTermId(leadershipTermId)
                    .timestamp(nowMs)
                    .action(action);

                bufferClaim.commit();

                return true;
            }

            checkResult(result);
        }
        while (--attempts > 0);

        return false;
    }

    boolean appendNewLeadershipTermEvent(
        final long leadershipTermId,
        final long logPosition,
        final long nowMs,
        final int leaderMemberId,
        final int logSessionId)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + NewLeadershipTermEventEncoder.BLOCK_LENGTH;

        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = publication.tryClaim(length, bufferClaim);
            if (result > 0)
            {
                newLeadershipTermEventEncoder.wrapAndApplyHeader(
                    bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .leadershipTermId(leadershipTermId)
                    .logPosition(logPosition)
                    .timestamp(nowMs)
                    .leaderMemberId(leaderMemberId)
                    .logSessionId(logSessionId);

                bufferClaim.commit();

                return true;
            }

            checkResult(result);
        }
        while (--attempts > 0);

        return false;
    }

    boolean appendClusterChangeEvent(
        final long leadershipTermId,
        final long logPosition,
        final long nowMs,
        final int leaderMemberId,
        final int clusterSize,
        final ChangeType eventType,
        final int memberId,
        final String clusterMembers)
    {
        long result;

        clusterChangeEventEncoder
            .wrapAndApplyHeader(expandableArrayBuffer, 0, messageHeaderEncoder)
            .leadershipTermId(leadershipTermId)
            .logPosition(logPosition)
            .timestamp(nowMs)
            .leaderMemberId(leaderMemberId)
            .clusterSize(clusterSize)
            .eventType(eventType)
            .memberId(memberId)
            .clusterMembers(clusterMembers);

        final int length = clusterChangeEventEncoder.encodedLength() + MessageHeaderEncoder.ENCODED_LENGTH;

        int attempts = SEND_ATTEMPTS;
        do
        {
            result = publication.offer(expandableArrayBuffer, 0, length);
            if (result > 0)
            {
                return true;
            }

            checkResult(result);
        }
        while (--attempts > 0);

        return false;
    }

    private static void checkResult(final long result)
    {
        if (result == Publication.NOT_CONNECTED ||
            result == Publication.CLOSED ||
            result == Publication.MAX_POSITION_EXCEEDED)
        {
            throw new AeronException("unexpected publication state: " + result);
        }
    }
}
