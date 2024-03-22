/*
 * Copyright (c) 2023. The BifroMQ Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.baidu.bifromq.mqtt.handler.v5;


import static com.baidu.bifromq.inbox.rpc.proto.UnsubReply.Code.ERROR;
import static com.baidu.bifromq.inbox.rpc.proto.UnsubReply.Code.OK;
import static com.baidu.bifromq.mqtt.handler.MQTTSessionIdUtil.userSessionId;
import static com.baidu.bifromq.plugin.eventcollector.EventType.INBOX_TRANSIENT_ERROR;
import static com.baidu.bifromq.plugin.eventcollector.EventType.PROTOCOL_VIOLATION;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS0_DROPPED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS0_PUSHED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS1_CONFIRMED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS1_DROPPED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS1_PUSHED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.QOS2_DROPPED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.SUB_ACKED;
import static com.baidu.bifromq.plugin.eventcollector.EventType.UNSUB_ACKED;
import static com.baidu.bifromq.type.QoS.AT_LEAST_ONCE;
import static com.baidu.bifromq.type.QoS.EXACTLY_ONCE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.baidu.bifromq.inbox.rpc.proto.CommitReply;
import com.baidu.bifromq.inbox.rpc.proto.CommitRequest;
import com.baidu.bifromq.inbox.rpc.proto.CreateReply;
import com.baidu.bifromq.inbox.rpc.proto.UnsubReply;
import com.baidu.bifromq.inbox.storage.proto.Fetched;
import com.baidu.bifromq.inbox.storage.proto.Fetched.Result;
import com.baidu.bifromq.mqtt.handler.ChannelAttrs;
import com.baidu.bifromq.mqtt.handler.TenantSettings;
import com.baidu.bifromq.mqtt.handler.v3.BaseSessionHandlerTest;
import com.baidu.bifromq.mqtt.handler.v5.reason.MQTT5DisconnectReasonCode;
import com.baidu.bifromq.mqtt.session.MQTTSessionContext;
import com.baidu.bifromq.mqtt.utils.MQTTMessageUtils;
import com.baidu.bifromq.type.QoS;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttReasonCodeAndPropertiesVariableHeader;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttUnsubAckMessage;
import io.netty.handler.codec.mqtt.MqttVersion;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Slf4j
public class MQTT5PersistentSessionHandlerTest extends BaseSessionHandlerTest {

    private MQTT5PersistentSessionHandler persistentSessionHandler;
    private int sessionExpirySeconds = 120;

    @BeforeMethod(alwaysRun = true)
    public void setup(Method method) {
        super.setup(method);
        sessionContext = MQTTSessionContext.builder()
            .serverId(serverId)
            .ticker(testTicker)
            .defaultKeepAliveTimeSeconds(2)
            .distClient(distClient)
            .retainClient(retainClient)
            .localDistService(localDistService)
            .authProvider(authProvider)
            .localSessionRegistry(localSessionRegistry)
            .sessionDictClient(sessionDictClient)
            .inboxClient(inboxClient)
            .eventCollector(eventCollector)
            .resourceThrottler(resourceThrottler)
            .settingProvider(settingProvider)
            .build();
        // common mocks
        mockSettings();
        ChannelDuplexHandler sessionHandlerAdder = new ChannelDuplexHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                super.channelActive(ctx);
                ctx.pipeline().addLast(MQTT5PersistentSessionHandler.builder()
                    .settings(new TenantSettings(tenantId, settingProvider))
                    .connMsg(MqttMessageBuilders.connect()
                        .protocolVersion(MqttVersion.MQTT_5)
                        .build())
                    .userSessionId(userSessionId(clientInfo))
                    .keepAliveTimeSeconds(120)
                    .sessionExpirySeconds(sessionExpirySeconds)
                    .clientInfo(clientInfo)
                    .willMessage(null)
                    .ctx(ctx)
                    .build());
                ctx.pipeline().remove(this);
            }
        };
        mockInboxCreate(CreateReply.Code.OK);
        mockInboxReader();
        channel = new EmbeddedChannel(true, true, new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.attr(ChannelAttrs.MQTT_SESSION_CTX).set(sessionContext);
                ch.attr(ChannelAttrs.PEER_ADDR).set(new InetSocketAddress(remoteIp, remotePort));
                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast(new ChannelTrafficShapingHandler(512 * 1024, 512 * 1024));
                pipeline.addLast(MqttDecoder.class.getName(), new MqttDecoder(256 * 1024));
                pipeline.addLast(sessionHandlerAdder);
            }
        });
        persistentSessionHandler = (MQTT5PersistentSessionHandler) channel.pipeline().last();
    }


    @SneakyThrows
    @AfterMethod(alwaysRun = true)
    public void tearDown(Method method) {
        super.tearDown(method);
        fetchHints.clear();
    }

//  =============================================== connect & disconnect =============================================

    @Test
    public void handleConnect() {
        assertTrue(channel.isOpen());

        channel.writeInbound(MQTTMessageUtils.mqttConnectMessage(true));
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runPendingTasks();
        channel.flushOutbound();
        MqttMessage disconnect = channel.readOutbound();
        assertEquals(((MqttReasonCodeAndPropertiesVariableHeader) disconnect.variableHeader()).reasonCode(),
            MQTT5DisconnectReasonCode.ProtocolError.value());
        assertFalse(channel.isOpen());
        verifyEvent(PROTOCOL_VIOLATION);
    }

    @Test
    public void connectWithSEI() {
        Assert.assertTrue(channel.isActive());

        MqttMessage disconnect = MqttMessageBuilders.disconnect()
            .properties(MQTT5MessageUtils.mqttProps().addSessionExpiryInterval(0).build()).build();
        channel.writeInbound(disconnect);
        Assert.assertFalse(channel.isActive());
        verify(inboxClient, times(1)).detach(any());
    }


//  =============================================== sub & unSub =======================================================

    @Test
    public void persistentQoS0Sub() {
        mockCheckPermission(true);
        mockInboxSub(QoS.AT_MOST_ONCE, true);
        mockRetainMatch();
        int[] qos = {0, 0, 0};
        MqttSubscribeMessage subMessage = MQTTMessageUtils.qoSMqttSubMessages(qos);
        channel.writeInbound(subMessage);
        channel.runPendingTasks();
        MqttSubAckMessage subAckMessage = channel.readOutbound();
        verifySubAck(subAckMessage, qos);
        verifyEvent(SUB_ACKED);
    }

    @Test
    public void persistentQoS1Sub() {
        mockCheckPermission(true);
        mockInboxSub(QoS.AT_LEAST_ONCE, true);
        mockRetainMatch();
        int[] qos = {1, 1, 1};
        MqttSubscribeMessage subMessage = MQTTMessageUtils.qoSMqttSubMessages(qos);
        channel.writeInbound(subMessage);
        channel.runPendingTasks();
        MqttSubAckMessage subAckMessage = channel.readOutbound();
        verifySubAck(subAckMessage, qos);
        verifyEvent(SUB_ACKED);
    }

    @Test
    public void persistentQoS2Sub() {
        mockCheckPermission(true);
        mockInboxSub(QoS.EXACTLY_ONCE, true);
        mockRetainMatch();
        int[] qos = {2, 2, 2};
        MqttSubscribeMessage subMessage = MQTTMessageUtils.qoSMqttSubMessages(qos);
        channel.writeInbound(subMessage);
        channel.runPendingTasks();
        MqttSubAckMessage subAckMessage = channel.readOutbound();
        verifySubAck(subAckMessage, qos);
        verifyEvent(SUB_ACKED);
    }

    @Test
    public void persistentMixedSub() {
        mockCheckPermission(true);
        mockInboxSub(QoS.AT_MOST_ONCE, true);
        mockInboxSub(QoS.AT_LEAST_ONCE, true);
        mockInboxSub(QoS.EXACTLY_ONCE, true);
        mockRetainMatch();
        int[] qos = {0, 1, 2};
        MqttSubscribeMessage subMessage = MQTTMessageUtils.qoSMqttSubMessages(qos);
        channel.writeInbound(subMessage);
        channel.runPendingTasks();
        MqttSubAckMessage subAckMessage = channel.readOutbound();
        verifySubAck(subAckMessage, qos);
        verifyEvent(SUB_ACKED);
    }


    @Test
    public void persistentUnSub() {
        mockCheckPermission(true);
        when(inboxClient.unsub(any())).thenReturn(
            CompletableFuture.completedFuture(UnsubReply.newBuilder().setCode(OK).build()));
        channel.writeInbound(MQTTMessageUtils.qoSMqttUnSubMessages(3));
        MqttUnsubAckMessage unsubAckMessage = channel.readOutbound();
        Assert.assertNotNull(unsubAckMessage);
        verifyEvent(UNSUB_ACKED);
    }

    @Test
    public void persistentMixedSubWithDistUnSubFailed() {
        mockCheckPermission(true);
        mockDistUnmatch(true, false, true);
        when(inboxClient.unsub(any()))
            .thenReturn(CompletableFuture.completedFuture(UnsubReply.newBuilder().setCode(OK).build()))
            .thenReturn(CompletableFuture.completedFuture(UnsubReply.newBuilder().setCode(ERROR).build()))
            .thenReturn(CompletableFuture.completedFuture(UnsubReply.newBuilder().setCode(OK).build()));
        channel.writeInbound(MQTTMessageUtils.qoSMqttUnSubMessages(3));
        MqttUnsubAckMessage unsubAckMessage = channel.readOutbound();
        Assert.assertNotNull(unsubAckMessage);
        verifyEvent(UNSUB_ACKED);
    }


//  =============================================== S2CPub ============================================================

    @Test
    public void qoS0Pub() {
        mockInboxCommit(CommitReply.Code.OK);
        mockCheckPermission(true);
        inboxFetchConsumer.accept(fetch(5, 128, QoS.AT_MOST_ONCE));
        channel.runPendingTasks();
        for (int i = 0; i < 5; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.AT_MOST_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
        }
        verifyEvent(QOS0_PUSHED, QOS0_PUSHED, QOS0_PUSHED, QOS0_PUSHED, QOS0_PUSHED);
        assertEquals(fetchHints.size(), 1);
    }

    @Test
    public void qoS0PubAuthFailed() {
        // not by pass
        mockInboxCommit(CommitReply.Code.OK);
        mockCheckPermission(false);
        mockDistUnMatch(true);
        inboxFetchConsumer.accept(fetch(5, 128, QoS.AT_MOST_ONCE));
        channel.runPendingTasks();
        for (int i = 0; i < 5; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertNull(message);
        }
        verifyEvent(QOS0_DROPPED, QOS0_DROPPED, QOS0_DROPPED, QOS0_DROPPED, QOS0_DROPPED);
        verify(inboxClient, times(5)).unsub(any());
    }

    @Test
    public void qoS0PubAndHintChange() {
        mockInboxCommit(CommitReply.Code.OK);
        mockCheckPermission(true);
        int messageCount = 2;
        inboxFetchConsumer.accept(fetch(messageCount, 64 * 1024, QoS.AT_MOST_ONCE));
        channel.runPendingTasks();
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.AT_MOST_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
        }
        verifyEvent(QOS0_PUSHED, QOS0_PUSHED);
        assertEquals(fetchHints.size(), 1);
    }

    @Test
    public void qoS1PubAndAck() {
        mockInboxCommit(CommitReply.Code.OK);
        mockCheckPermission(true);
        int messageCount = 3;
        inboxFetchConsumer.accept(fetch(messageCount, 128, AT_LEAST_ONCE));
        channel.runPendingTasks();
        // s2c pub received and ack
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.AT_LEAST_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
            channel.writeInbound(MQTTMessageUtils.pubAckMessage(message.variableHeader().packetId()));
        }
        verifyEvent(QOS1_PUSHED, QOS1_PUSHED, QOS1_PUSHED, QOS1_CONFIRMED, QOS1_CONFIRMED, QOS1_CONFIRMED);
        verify(inboxClient, times(3)).commit(argThat(CommitRequest::hasSendBufferUpToSeq));
    }

    @Test
    public void qoS1PubAndNotAllAck() {
        mockCheckPermission(true);
        mockInboxCommit(CommitReply.Code.OK);
        int messageCount = 3;
        inboxFetchConsumer.accept(fetch(messageCount, 128, AT_LEAST_ONCE));
        channel.runPendingTasks();
        // s2c pub received and ack
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertEquals(message.fixedHeader().qosLevel().value(), QoS.AT_LEAST_ONCE_VALUE);
            assertEquals(message.variableHeader().topicName(), topic);
            if (i != messageCount - 1) {
                channel.writeInbound(MQTTMessageUtils.pubAckMessage(message.variableHeader().packetId()));
            }
        }
        verifyEvent(QOS1_PUSHED, QOS1_PUSHED, QOS1_PUSHED, QOS1_CONFIRMED, QOS1_CONFIRMED);
        verify(inboxClient, times(2)).commit(argThat(CommitRequest::hasSendBufferUpToSeq));
    }

    @Test
    public void qoS1PubAuthFailed() {
        // not by pass
        mockCheckPermission(false);
        mockDistUnMatch(true);
        int messageCount = 3;
        inboxFetchConsumer.accept(fetch(messageCount, 128, AT_LEAST_ONCE));
        channel.runPendingTasks();
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertNull(message);
        }
        verifyEvent(QOS1_DROPPED, QOS1_DROPPED, QOS1_DROPPED);
        verify(inboxClient, times(messageCount)).unsub(any());
    }

//    @Test
//    public void qoS2PubAndRel() {
//        mockCheckPermission(true);
//        mockInboxCommit(CommitReply.Code.OK);
//        int messageCount = 2;
//        inboxFetchConsumer.accept(fetch(messageCount, 128, EXACTLY_ONCE));
//        channel.runPendingTasks();
//        // s2c pub received and rec
//        for (int i = 0; i < messageCount; i++) {
//            MqttPublishMessage message = channel.readOutbound();
//            assertEquals(message.fixedHeader().qosLevel().value(), QoS.EXACTLY_ONCE_VALUE);
//            assertEquals(message.variableHeader().topicName(), topic);
//            channel.writeInbound(MQTTMessageUtils.publishRecMessage(message.variableHeader().packetId()));
//        }
//        // pubRel received and comp
//        for (int i = 0; i < messageCount; i++) {
//            MqttMessage message = channel.readOutbound();
//            assertEquals(message.fixedHeader().messageType(), PUBREL);
//            channel.writeInbound(MQTTMessageUtils.publishCompMessage(
//                ((MqttMessageIdVariableHeader) message.variableHeader()).messageId()));
//        }
//        verifyEvent(QOS2_PUSHED, QOS2_PUSHED, QOS2_RECEIVED, QOS2_RECEIVED, QOS2_CONFIRMED, QOS2_CONFIRMED);
//        verify(inboxClient, times(2)).commit(argThat(CommitRequest::hasSendBufferUpToSeq));
//    }

    @Test
    public void qoS2PubAuthFailed() {
        // not by pass
        mockCheckPermission(false);
        mockDistUnMatch(true);
        int messageCount = 3;
        inboxFetchConsumer.accept(fetch(messageCount, 128, EXACTLY_ONCE));
        channel.runPendingTasks();
        for (int i = 0; i < messageCount; i++) {
            MqttPublishMessage message = channel.readOutbound();
            assertNull(message);
        }
        verifyEvent(QOS2_DROPPED, QOS2_DROPPED, QOS2_DROPPED);
        verify(inboxClient, times(messageCount)).unsub(any());
    }

    @Test
    public void fetchError() {
        inboxFetchConsumer.accept(Fetched.newBuilder().setResult(Result.ERROR).build());
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runPendingTasks();
        verifyEvent();
        assertTrue(channel.isOpen());
    }

    @Test
    public void fetchNoInbox() {
        inboxFetchConsumer.accept(Fetched.newBuilder().setResult(Result.NO_INBOX).build());
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runPendingTasks();
        verifyEvent(INBOX_TRANSIENT_ERROR);
    }

}
