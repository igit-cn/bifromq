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

package com.baidu.bifromq.starter.metrics.netty;

import com.baidu.bifromq.baseenv.EnvProvider;
import io.netty.buffer.ByteBufAllocatorMetric;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A non-blocking wrapper of {@link io.netty.buffer.PooledByteBufAllocatorMetric}.
 */
public class PooledByteBufAllocatorMetric implements ByteBufAllocatorMetric {
    private static final Executor EXECUTOR =
        Executors.newSingleThreadExecutor(EnvProvider.INSTANCE.newThreadFactory("netty-metrics-reader", true));
    private final AtomicBoolean isReading = new AtomicBoolean(false);

    private final io.netty.buffer.PooledByteBufAllocatorMetric delegate;
    private volatile long usedHeapMemory;
    private volatile long usedDirectMemory;

    public PooledByteBufAllocatorMetric(io.netty.buffer.PooledByteBufAllocatorMetric delegate) {
        this.delegate = delegate;
    }

    @Override
    public long usedHeapMemory() {
        scheduleUpdate();
        return usedHeapMemory;
    }

    @Override
    public long usedDirectMemory() {
        scheduleUpdate();
        return usedDirectMemory;
    }

    private void scheduleUpdate() {
        if (isReading.compareAndSet(false, true)) {
            EXECUTOR.execute(() -> {
                usedHeapMemory = delegate.usedHeapMemory();
                usedDirectMemory = delegate.usedDirectMemory();
                isReading.set(false);
            });
        }
    }
}
