/*
 * Copyright 2014 Netflix, Inc.
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
 */

package com.netflix.eureka2.client.channel;

import com.netflix.eureka2.client.registry.EurekaClientRegistry;
import com.netflix.eureka2.registry.InstanceInfo;
import com.netflix.eureka2.service.RegistrationChannel;

/**
 * Channels factory for Eureka client.
 *
 * @author Tomasz Bak
 */
public interface ClientChannelFactory {

    enum Mode {Read, Write, ReadWrite}

    /**
     * Returns a new {@link com.netflix.eureka2.service.InterestChannel}.
     *
     * @return A new {@link com.netflix.eureka2.service.InterestChannel}.
     */
    ClientInterestChannel newInterestChannel(EurekaClientRegistry<InstanceInfo> clientRegistry);

    /**
     * Returns a new {@link com.netflix.eureka2.service.RegistrationChannel}.
     *
     * @return A new {@link com.netflix.eureka2.service.RegistrationChannel}.
     */
    RegistrationChannel newRegistrationChannel();

    /**
     * Mode specifies constraints on channels that can be created.
     */
    Mode mode();

    /**
     * Release underlying resources.
     */
    void shutdown();
}