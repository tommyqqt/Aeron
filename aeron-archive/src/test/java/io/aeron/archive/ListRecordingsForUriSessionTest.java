package io.aeron.archive;

import io.aeron.archive.codecs.RecordingDescriptorDecoder;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.stubbing.Answer;

import java.io.File;

import static io.aeron.archive.Catalog.wrapDescriptorDecoder;
import static io.aeron.archive.codecs.RecordingDescriptorDecoder.BLOCK_LENGTH;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;

public class ListRecordingsForUriSessionTest
{
    private static final long MAX_ENTRIES = 1024;
    private static final int SEGMENT_FILE_SIZE = 128 * 1024 * 1024;
    private final UnsafeBuffer descriptorBuffer = new UnsafeBuffer();
    private final RecordingDescriptorDecoder recordingDescriptorDecoder = new RecordingDescriptorDecoder();
    private long[] matchingRecordingIds = new long[3];
    private final File archiveDir = TestUtil.makeTestDirectory();
    private final EpochClock clock = mock(EpochClock.class);

    private Catalog catalog;
    private final long correlationId = 1;
    private final ControlResponseProxy controlResponseProxy = mock(ControlResponseProxy.class);
    private final ControlSession controlSession = mock(ControlSession.class);

    @Before
    public void before()
    {
        catalog = new Catalog(archiveDir, null, 0, MAX_ENTRIES, clock);
        matchingRecordingIds[0] = catalog.addNewRecording(
            0L, 0L, 0, SEGMENT_FILE_SIZE, 4096, 1024, 6, 1, "localhost", "localhost?tag=f", "sourceA");
        catalog.addNewRecording(
            0L, 0L, 0, SEGMENT_FILE_SIZE, 4096, 1024, 7, 1, "channelA", "channel?tag=f", "sourceV");
        matchingRecordingIds[1] = catalog.addNewRecording(
            0L, 0L, 0, SEGMENT_FILE_SIZE, 4096, 1024, 8, 1, "localhost", "localhost?tag=f", "sourceB");
        catalog.addNewRecording(
            0L, 0L, 0, SEGMENT_FILE_SIZE, 4096, 1024, 8, 1, "channelB", "channelB?tag=f", "sourceB");
        matchingRecordingIds[2] = catalog.addNewRecording(
            0L, 0L, 0, SEGMENT_FILE_SIZE, 4096, 1024, 8, 1, "localhost", "localhost?tag=f", "sourceB");
    }

    @After
    public void after()
    {
        CloseHelper.close(catalog);
        IoUtil.delete(archiveDir, false);
    }

    @Test
    public void shouldSendAllDescriptors()
    {
        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            0,
            3,
            "localhost",
            1,
            catalog,
            controlResponseProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        session.doWork();
        assertThat(session.isDone(), is(false));
        when(controlSession.maxPayloadLength()).thenReturn(8096);
        final MutableLong counter = new MutableLong(0);
        when(controlSession.sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy)))
            .then(verifySendDescriptor(counter));
        session.doWork();
        verify(controlSession, times(3)).sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy));
    }

    @Test
    public void shouldSend2Descriptors()
    {
        final long fromRecordingId = 1;
        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            fromRecordingId,
            2,
            "localhost",
            1,
            catalog,
            controlResponseProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        session.doWork();
        assertThat(session.isDone(), is(false));
        when(controlSession.maxPayloadLength()).thenReturn(8096);
        final MutableLong counter = new MutableLong(fromRecordingId);
        when(controlSession.sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy)))
            .then(verifySendDescriptor(counter));
        session.doWork();
        verify(controlSession, times(2)).sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy));
    }

    @Test
    public void shouldResendDescriptorWhenSendFails()
    {
        final long fromRecordingId = 1;
        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            fromRecordingId,
            1,
            "localhost",
            1,
            catalog,
            controlResponseProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        session.doWork();
        assertThat(session.isDone(), is(false));
        when(controlSession.maxPayloadLength()).thenReturn(8096);

        when(controlSession.sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy))).thenReturn(0);
        session.doWork();
        verify(controlSession, times(1)).sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy));

        final MutableLong counter = new MutableLong(fromRecordingId);
        when(controlSession.sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy)))
            .then(verifySendDescriptor(counter));
        session.doWork();
        verify(controlSession, times(2)).sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy));
    }

    @Test
    public void shouldLimitSendingToSingleMtu()
    {
        when(controlSession.maxPayloadLength()).thenReturn(BLOCK_LENGTH);
        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            0,
            3,
            "localhost",
            1,
            catalog,
            controlResponseProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        final MutableLong counter = new MutableLong(0);
        when(controlSession.sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy)))
            .then(verifySendDescriptor(counter));
        session.doWork();
        verify(controlSession).sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy));
        session.doWork();
        verify(controlSession, times(2)).sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy));
        session.doWork();
        verify(controlSession, times(3)).sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy));
    }

    @Test
    public void shouldSend2DescriptorsAndRecordingUnknown()
    {
        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            1,
            5,
            "localhost",
            1,
            catalog,
            controlResponseProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        session.doWork();
        assertThat(session.isDone(), is(false));
        when(controlSession.maxPayloadLength()).thenReturn(8096);
        final MutableLong counter = new MutableLong(1);
        when(controlSession.sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy)))
            .then(verifySendDescriptor(counter));
        session.doWork();
        verify(controlSession, times(2)).sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy));
        verify(controlSession).sendRecordingUnknown(eq(correlationId), eq(5L), eq(controlResponseProxy));
    }

    @Test
    public void shouldSendRecordingUnknown()
    {
        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            1,
            3,
            "notChannel",
            1,
            catalog,
            controlResponseProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        session.doWork();
        assertThat(session.isDone(), is(false));
        when(controlSession.maxPayloadLength()).thenReturn(8096);
        session.doWork();
        verify(controlSession, never()).sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy));
        verify(controlSession).sendRecordingUnknown(eq(correlationId), eq(5L), eq(controlResponseProxy));
    }

    @Test
    public void shouldSendUnknownOnFirst()
    {
        when(controlSession.maxPayloadLength()).thenReturn(4096 - 32);

        final ListRecordingsForUriSession session = new ListRecordingsForUriSession(
            correlationId,
            5,
            3,
            "localhost",
            1,
            catalog,
            controlResponseProxy,
            controlSession,
            descriptorBuffer,
            recordingDescriptorDecoder);

        session.doWork();

        verify(controlSession, never()).sendDescriptor(eq(correlationId), any(), eq(controlResponseProxy));
        verify(controlSession).sendRecordingUnknown(eq(correlationId), eq(5L), eq(controlResponseProxy));
    }

    private Answer<Object> verifySendDescriptor(final MutableLong counter)
    {
        return (invocation) ->
        {
            final UnsafeBuffer b = invocation.getArgument(1);
            wrapDescriptorDecoder(recordingDescriptorDecoder, b);

            final int i = counter.intValue();
            assertThat(recordingDescriptorDecoder.recordingId(), is(matchingRecordingIds[i]));
            counter.set(i + 1);
            return b.getInt(0);
        };
    }
}