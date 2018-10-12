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
package io.aeron.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.SleepingMillisIdleStrategy;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;

import static net.bytebuddy.asm.Advice.to;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * A Java agent which when attached to a JVM will weave byte code to intercept events as defined by {@link EventCode}.
 * These events are recorded to an in-memory {@link org.agrona.concurrent.ringbuffer.RingBuffer} which is consumed
 * and appended asynchronous to a log as defined by the class {@link #READER_CLASSNAME_PROP_NAME} which defaults to
 * {@link EventLogReaderAgent}.
 */
@SuppressWarnings("unused")
public class EventLogAgent
{
    /**
     * Event reader {@link Agent} which consumes the {@link EventConfiguration#EVENT_RING_BUFFER} to output log events.
     */
    public static final String READER_CLASSNAME_PROP_NAME = "aeron.event.log.reader.classname";
    public static final String READER_CLASSNAME_DEFAULT = "io.aeron.agent.EventLogReaderAgent";

    private static final long SLEEP_PERIOD_MS = 1L;

    private static AgentRunner readerAgentRunner;
    private static Instrumentation instrumentation;
    private static volatile ClassFileTransformer logTransformer;

    private static final AgentBuilder.Listener LISTENER = new AgentBuilder.Listener()
    {
        public void onDiscovery(
            final String typeName,
            final ClassLoader classLoader,
            final JavaModule module,
            final boolean loaded)
        {
        }

        public void onTransformation(
            final TypeDescription typeDescription,
            final ClassLoader classLoader,
            final JavaModule module,
            final boolean loaded,
            final DynamicType dynamicType)
        {
        }

        public void onIgnored(
            final TypeDescription typeDescription,
            final ClassLoader classLoader,
            final JavaModule module,
            final boolean loaded)
        {
        }

        public void onError(
            final String typeName,
            final ClassLoader classLoader,
            final JavaModule module,
            final boolean loaded,
            final Throwable throwable)
        {
            System.err.println("ERROR " + typeName);
            throwable.printStackTrace(System.out);
        }

        public void onComplete(
            final String typeName,
            final ClassLoader classLoader,
            final JavaModule module,
            final boolean loaded)
        {
        }
    };

    @SuppressWarnings("Indendation")
    private static void agent(final boolean shouldRedefine, final Instrumentation instrumentation)
    {
        if (EventLogger.ENABLED_EVENT_CODES == 0)
        {
            return;
        }

        EventLogAgent.instrumentation = instrumentation;

        readerAgentRunner = new AgentRunner(
            new SleepingMillisIdleStrategy(SLEEP_PERIOD_MS), Throwable::printStackTrace, null, getReaderAgent());

        logTransformer = new AgentBuilder.Default(new ByteBuddy().with(TypeValidation.DISABLED))
            .with(LISTENER)
            .disableClassFormatChanges()
            .with(shouldRedefine ?
                AgentBuilder.RedefinitionStrategy.RETRANSFORMATION : AgentBuilder.RedefinitionStrategy.DISABLED)
            .type(nameEndsWith("DriverConductor"))
            .transform((builder, typeDescription, classLoader, javaModule) ->
                builder
                    .visit(to(CleanupInterceptor.CleanupImage.class).on(named("cleanupImage")))
                    .visit(to(CleanupInterceptor.CleanupPublication.class).on(named("cleanupPublication")))
                    .visit(to(CleanupInterceptor.CleanupSubscriptionLink.class).on(named("cleanupSubscriptionLink"))))
            .type(nameEndsWith("ClientCommandAdapter"))
            .transform((builder, typeDescription, classLoader, javaModule) ->
                builder
                    .visit(to(CmdInterceptor.class).on(named("onMessage"))))
            .type(nameEndsWith("ClientProxy"))
            .transform((builder, typeDescription, classLoader, javaModule) ->
                builder
                    .visit(to(CmdInterceptor.class).on(named("transmit"))))
            .type(nameEndsWith("SenderProxy"))
            .transform((builder, typeDescription, classLoader, javaModule) ->
                builder
                    .visit(to(ChannelEndpointInterceptor.SenderProxyInterceptor.RegisterSendChannelEndpoint.class)
                        .on(named("registerSendChannelEndpoint")))
                    .visit(to(ChannelEndpointInterceptor.SenderProxyInterceptor.CloseSendChannelEndpoint.class)
                        .on(named("closeSendChannelEndpoint"))))
            .type(nameEndsWith("ReceiverProxy"))
            .transform((builder, typeDescription, classLoader, javaModule) ->
                builder
                    .visit(to(ChannelEndpointInterceptor.ReceiverProxyInterceptor.RegisterReceiveChannelEndpoint.class)
                        .on(named("registerReceiveChannelEndpoint")))
                    .visit(to(ChannelEndpointInterceptor.ReceiverProxyInterceptor.CloseReceiveChannelEndpoint.class)
                        .on(named("closeReceiveChannelEndpoint"))))
            .type(nameEndsWith("UdpChannelTransport"))
            .transform((builder, typeDescription, classLoader, javaModule) ->
                builder
                    .visit(to(ChannelEndpointInterceptor.UdpChannelTransportInterceptor.SendHook.class)
                        .on(named("sendHook")))
                    .visit(to(ChannelEndpointInterceptor.UdpChannelTransportInterceptor.ReceiveHook.class)
                        .on(named("receiveHook"))))
            .installOn(instrumentation);

        final Thread thread = new Thread(readerAgentRunner);
        thread.setName("event-log-reader");
        thread.setDaemon(true);
        thread.start();
    }

    public static void premain(final String agentArgs, final Instrumentation instrumentation)
    {
        agent(false, instrumentation);
    }

    public static void agentmain(final String agentArgs, final Instrumentation instrumentation)
    {
        agent(true, instrumentation);
    }

    public static void removeTransformer()
    {
        if (logTransformer != null)
        {
            readerAgentRunner.close();
            instrumentation.removeTransformer(logTransformer);

            final ElementMatcher.Junction<TypeDescription> orClause = nameEndsWith("DriverConductor")
                .or(nameEndsWith("ClientProxy"))
                .or(nameEndsWith("ClientCommandAdapter"))
                .or(nameEndsWith("SenderProxy"))
                .or(nameEndsWith("ReceiverProxy"))
                .or(nameEndsWith("UdpChannelTransport"));

            final ResettableClassFileTransformer transformer = new AgentBuilder.Default()
                .type(orClause)
                .transform(AgentBuilder.Transformer.NoOp.INSTANCE)
                .installOn(instrumentation);

            instrumentation.removeTransformer(transformer);

            readerAgentRunner = null;
            instrumentation = null;
            logTransformer = null;
        }
    }

    private static Agent getReaderAgent()
    {
        try
        {
            final Class<?> aClass = Class.forName(
                System.getProperty(READER_CLASSNAME_PROP_NAME, READER_CLASSNAME_DEFAULT));

            return (Agent)aClass.getDeclaredConstructor().newInstance();
        }
        catch (final Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }
}
