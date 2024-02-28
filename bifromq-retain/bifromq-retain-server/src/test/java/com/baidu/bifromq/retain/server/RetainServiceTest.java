/*
 * Copyright (c) 2024. The BifroMQ Authors. All Rights Reserved.
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

package com.baidu.bifromq.retain.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import com.baidu.bifromq.baserpc.RPCContext;
import com.baidu.bifromq.baserpc.metrics.IRPCMeter;
import com.baidu.bifromq.baserpc.metrics.RPCMetric;
import com.baidu.bifromq.deliverer.DeliveryCall;
import com.baidu.bifromq.deliverer.IMessageDeliverer;
import com.baidu.bifromq.plugin.subbroker.DeliveryResult;
import com.baidu.bifromq.retain.rpc.proto.MatchReply;
import com.baidu.bifromq.retain.rpc.proto.MatchRequest;
import com.baidu.bifromq.retain.rpc.proto.RetainReply;
import com.baidu.bifromq.retain.rpc.proto.RetainRequest;
import com.baidu.bifromq.retain.server.scheduler.IMatchCallScheduler;
import com.baidu.bifromq.retain.server.scheduler.IRetainCallScheduler;
import com.baidu.bifromq.retain.server.scheduler.MatchCallResult;
import com.baidu.bifromq.retain.store.gc.IRetainStoreGCProcessor;
import com.baidu.bifromq.type.ClientInfo;
import com.baidu.bifromq.type.MatchInfo;
import com.baidu.bifromq.type.Message;
import com.baidu.bifromq.type.TopicMessage;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Slf4j
public class RetainServiceTest {
    private String serviceName = "retainService";
    private String methodName = "testMethod";
    private String tenantId = "testTenantId";
    @Mock
    IRetainStoreGCProcessor gcProcessor;
    @Mock
    private IMessageDeliverer messageDeliverer;
    @Mock
    private IMatchCallScheduler matchCallScheduler;
    @Mock
    private IRetainCallScheduler retainCallScheduler;
    @Mock
    StreamObserver<RetainReply> retainResponseObserver;
    @Mock
    StreamObserver<MatchReply> matchResponseObserver;
    private AutoCloseable closeable;
    private RetainService service;

    @BeforeMethod
    public void setup(Method method) {
        log.info("Test case[{}.{}] start", method.getDeclaringClass().getName(), method.getName());
        Context.current()
            .withValue(RPCContext.METER_KEY_CTX_KEY, new IRPCMeter.IRPCMethodMeter() {
                @Override
                public void recordCount(RPCMetric metric) {

                }

                @Override
                public void recordCount(RPCMetric metric, double inc) {

                }

                @Override
                public Timer timer(RPCMetric metric) {
                    return Timer.builder("dummy").register(new SimpleMeterRegistry());
                }

                @Override
                public void recordSummary(RPCMetric metric, int depth) {

                }
            })
            .withValue(RPCContext.TENANT_ID_CTX_KEY, tenantId)
            .attach();
        closeable = MockitoAnnotations.openMocks(this);
        service = new RetainService(gcProcessor, messageDeliverer, matchCallScheduler, retainCallScheduler);
    }

    @AfterMethod
    public void tearDown(Method method) throws Exception {
        log.info("Test case[{}.{}] finished", method.getDeclaringClass().getName(), method.getName());
        closeable.close();
    }

    @Test
    public void testPutRetainWithException() {
        when(retainCallScheduler.schedule(any())).thenReturn(
            CompletableFuture.failedFuture(new RuntimeException("Mocked")));
        long reqId = 1;
        service.retain(RetainRequest.newBuilder().setReqId(reqId).build(), retainResponseObserver);
        verify(retainResponseObserver)
            .onNext(argThat(r -> r.getReqId() == reqId && r.getResult() == RetainReply.Result.ERROR));
    }

    @Test
    public void testMatchNothing() {
        when(matchCallScheduler.schedule(any())).thenReturn(
            CompletableFuture.completedFuture(new MatchCallResult(MatchReply.Result.OK, Collections.emptyList())));
        long reqId = 1;
        service.match(MatchRequest.newBuilder().setReqId(reqId).build(), matchResponseObserver);
        verify(matchResponseObserver)
            .onNext(argThat(r -> r.getReqId() == reqId && r.getResult() == MatchReply.Result.OK));
        verify(messageDeliverer, never()).schedule(any());
    }

    @Test
    public void testDeliverRetainMessages() {
        TopicMessage retainMsg1 = TopicMessage.newBuilder()
            .setTopic("topic1")
            .setMessage(Message.newBuilder().build())
            .setPublisher(ClientInfo.newBuilder().build())
            .build();
        TopicMessage retainMsg2 = TopicMessage.newBuilder()
            .setTopic("topic2")
            .setMessage(Message.newBuilder().build())
            .setPublisher(ClientInfo.newBuilder().build())
            .build();
        when(matchCallScheduler.schedule(any())).thenReturn(CompletableFuture.completedFuture(
            new MatchCallResult(MatchReply.Result.OK, List.of(retainMsg1, retainMsg2))));
        when(messageDeliverer.schedule(any())).thenReturn(CompletableFuture.completedFuture(DeliveryResult.Code.OK));
        MatchRequest matchRequest = MatchRequest.newBuilder()
            .setReqId(1)
            .setTenantId("tenant")
            .setMatchInfo(MatchInfo.newBuilder()
                .setTopicFilter("#")
                .setReceiverId("inbox")
                .build())
            .setDelivererKey("delivererKey")
            .setBrokerId(1)
            .build();
        service.match(matchRequest, matchResponseObserver);
        verify(matchResponseObserver)
            .onNext(argThat(r -> r.getReqId() == matchRequest.getReqId() && r.getResult() == MatchReply.Result.OK));
        ArgumentCaptor<DeliveryCall> reqCaptor = ArgumentCaptor.forClass(DeliveryCall.class);
        verify(messageDeliverer, times(2)).schedule(reqCaptor.capture());
        List<DeliveryCall> requestList = reqCaptor.getAllValues();
        DeliveryCall req1 = requestList.get(0);
        assertEquals(req1.tenantId, matchRequest.getTenantId());
        assertEquals(req1.matchInfo.getReceiverId(), matchRequest.getMatchInfo().getReceiverId());
        assertEquals(req1.matchInfo.getTopicFilter(), matchRequest.getMatchInfo().getTopicFilter());

        assertEquals(req1.msgPackWrapper.messagePack.getTopic(), retainMsg1.getTopic());
        assertEquals(req1.msgPackWrapper.messagePack.getMessage(0).getPublisher(), retainMsg1.getPublisher());

        assertEquals(req1.delivererKey.delivererKey(), matchRequest.getDelivererKey());
        assertEquals(req1.delivererKey.subBrokerId(), matchRequest.getBrokerId());
    }

    @Test
    public void testDeliverToNoInbox() {
        TopicMessage retainMsg1 = TopicMessage.newBuilder()
            .setTopic("topic1")
            .setMessage(Message.newBuilder().build())
            .setPublisher(ClientInfo.newBuilder().build())
            .build();
        TopicMessage retainMsg2 = TopicMessage.newBuilder()
            .setTopic("topic2")
            .setMessage(Message.newBuilder().build())
            .setPublisher(ClientInfo.newBuilder().build())
            .build();
        when(matchCallScheduler.schedule(any())).thenReturn(CompletableFuture.completedFuture(
            new MatchCallResult(MatchReply.Result.OK, List.of(retainMsg1, retainMsg2))));
        when(messageDeliverer.schedule(any())).thenReturn(
            CompletableFuture.completedFuture(DeliveryResult.Code.NO_SUB));
        MatchRequest matchRequest = MatchRequest.newBuilder()
            .setReqId(1)
            .setTenantId("tenant")
            .setMatchInfo(MatchInfo.newBuilder()
                .setTopicFilter("#")
                .setReceiverId("inbox")
                .build())
            .setDelivererKey("delivererKey")
            .setBrokerId(1)
            .build();
        service.match(matchRequest, matchResponseObserver);
        verify(matchResponseObserver)
            .onNext(argThat(r -> r.getReqId() == matchRequest.getReqId() && r.getResult() == MatchReply.Result.ERROR));
        verify(messageDeliverer, times(2)).schedule(any());
    }

    @Test
    public void testDeliverFailed() {
        TopicMessage retainMsg1 = TopicMessage.newBuilder()
            .setTopic("topic1")
            .setMessage(Message.newBuilder().build())
            .setPublisher(ClientInfo.newBuilder().build())
            .build();
        TopicMessage retainMsg2 = TopicMessage.newBuilder()
            .setTopic("topic2")
            .setMessage(Message.newBuilder().build())
            .setPublisher(ClientInfo.newBuilder().build())
            .build();
        when(matchCallScheduler.schedule(any())).thenReturn(CompletableFuture.completedFuture(
            new MatchCallResult(MatchReply.Result.OK, List.of(retainMsg1, retainMsg2))));
        when(messageDeliverer.schedule(any())).thenReturn(CompletableFuture.completedFuture(DeliveryResult.Code.ERROR));
        MatchRequest matchRequest = MatchRequest.newBuilder()
            .setReqId(1)
            .setTenantId("tenant")
            .setMatchInfo(MatchInfo.newBuilder()
                .setTopicFilter("#")
                .setReceiverId("inbox")
                .build())
            .setDelivererKey("delivererKey")
            .setBrokerId(1)
            .build();
        service.match(matchRequest, matchResponseObserver);
        verify(matchResponseObserver)
            .onNext(argThat(r -> r.getReqId() == matchRequest.getReqId() && r.getResult() == MatchReply.Result.ERROR));
        verify(messageDeliverer, times(2)).schedule(any());
    }

    @Test
    public void testMatchRetainWithErrorCode() {
        when(matchCallScheduler.schedule(any())).thenReturn(
            CompletableFuture.completedFuture(new MatchCallResult(MatchReply.Result.ERROR, Collections.emptyList())));
        long reqId = 1;
        service.match(MatchRequest.newBuilder().setReqId(reqId).build(), matchResponseObserver);
        verify(matchResponseObserver)
            .onNext(argThat(r -> r.getReqId() == reqId && r.getResult() == MatchReply.Result.ERROR));
        verify(messageDeliverer, never()).schedule(any());
    }

    @Test
    public void testMatchRetainWithException() {
        when(matchCallScheduler.schedule(any())).thenReturn(
            CompletableFuture.failedFuture(new RuntimeException("Mocked")));
        long reqId = 1;
        service.match(MatchRequest.newBuilder().setReqId(reqId).build(), matchResponseObserver);
        verify(matchResponseObserver)
            .onNext(argThat(r -> r.getReqId() == reqId && r.getResult() == MatchReply.Result.ERROR));
        verify(messageDeliverer, never()).schedule(any());
    }
}
