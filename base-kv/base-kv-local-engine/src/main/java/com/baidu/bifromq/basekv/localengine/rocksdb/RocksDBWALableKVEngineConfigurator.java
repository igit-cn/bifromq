/*
 * Copyright (c) 2023. Baidu, Inc. All Rights Reserved.
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

package com.baidu.bifromq.basekv.localengine.rocksdb;

import com.baidu.bifromq.basekv.localengine.IWALableKVEngineConfigurator;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import org.rocksdb.DBOptions;

@Accessors(chain = true, fluent = true)
@Getter
@Setter
@SuperBuilder(toBuilder = true)
public final class RocksDBWALableKVEngineConfigurator
    extends RocksDBKVEngineConfigurator<RocksDBWALableKVEngineConfigurator>
    implements IWALableKVEngineConfigurator {
    private boolean asyncWALFlush = false;
    private boolean fsyncWAL = false;

    @Override
    public DBOptions dbOptions() {
        DBOptions options = super.dbOptions();
        options.setManualWalFlush(asyncWALFlush);
        return options;
    }
}
