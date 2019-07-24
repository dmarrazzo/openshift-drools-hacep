/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.remote.impl;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.kie.remote.RemoteStatefulSession;
import org.kie.remote.TopicsConfig;
import org.kie.remote.command.FireAllRulesCommand;
import org.kie.remote.impl.producer.Sender;

public class RemoteStatefulSessionImpl implements RemoteStatefulSession {

    private final Sender sender;
    private final Map<String, CompletableFuture<Object>> requestsStore;
    private final TopicsConfig topicsConfig;

    private volatile boolean firingUntilHalt;

    public RemoteStatefulSessionImpl( Sender sender, Map<String, CompletableFuture<Object>> requestsStore, TopicsConfig topicsConfig ) {
        this.sender = sender;
        this.requestsStore = requestsStore;
        this.topicsConfig = topicsConfig;
    }

    @Override
    public CompletableFuture<Integer> fireAllRules() {
        FireAllRulesCommand command = new FireAllRulesCommand();
        CompletableFuture callback = new CompletableFuture<>();
        requestsStore.put( command.getId(), callback );
        sender.sendCommand( command, topicsConfig.getEventsTopicName() );
        return callback;
    }

    @Override
    public void fireUntilHalt() {
        firingUntilHalt = true;
    }

    @Override
    public void halt() {
        firingUntilHalt = false;
    }

    public boolean isFiringUntilHalt() {
        return firingUntilHalt;
    }
}
