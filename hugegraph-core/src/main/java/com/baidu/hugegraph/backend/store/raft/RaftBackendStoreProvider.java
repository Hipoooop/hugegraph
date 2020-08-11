/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.backend.store.raft;

import java.util.Set;
import java.util.concurrent.Future;

import org.slf4j.Logger;

import com.alipay.sofa.jraft.rpc.RpcServer;
import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.HugeGraphParams;
import com.baidu.hugegraph.backend.store.BackendStore;
import com.baidu.hugegraph.backend.store.BackendStoreProvider;
import com.baidu.hugegraph.event.EventHub;
import com.baidu.hugegraph.event.EventListener;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.Events;
import com.baidu.hugegraph.util.Log;
import com.google.common.collect.ImmutableSet;

public class RaftBackendStoreProvider implements BackendStoreProvider {

    private static final Logger LOG = Log.logger(RaftBackendStoreProvider.class);

    private final BackendStoreProvider provider;
    private final RaftSharedContext context;
    private RaftBackendStore schemaStore;
    private RaftBackendStore graphStore;
    private RaftBackendStore systemStore;
    private EventHub storeEventHub;

    public RaftBackendStoreProvider(BackendStoreProvider provider,
                                    HugeGraphParams params) {
        this.provider = provider;
        this.context = new RaftSharedContext(params);
        this.schemaStore = null;
        this.graphStore = null;
        this.systemStore = null;
        this.storeEventHub = new EventHub("raft-store");
        this.registerRpcRequestProcessors();
    }

    private Set<RaftBackendStore> stores() {
        return ImmutableSet.of(this.schemaStore, this.graphStore,
                               this.systemStore);
    }

    private void registerRpcRequestProcessors() {
        RpcServer rpcServer = this.context.rpcServer();
        rpcServer.registerProcessor(new StoreCommandRequestProcessor(
                                    this.context));
    }

    private void checkOpened() {
        E.checkState(this.graph() != null &&
                     this.schemaStore != null &&
                     this.graphStore != null &&
                     this.systemStore != null,
                     "The RaftBackendStoreProvider has not been opened");
    }

    private final void notifyAndWaitEvent(String event) {
        Future<?> future = this.storeEventHub.notify(event, this);
        try {
            future.get();
        } catch (Throwable e) {
            LOG.warn("Error when waiting for event execution: {}", event, e);
        }
    }

    @Override
    public String type() {
        return this.provider.type();
    }

    @Override
    public String version() {
        return this.provider.version();
    }

    @Override
    public String graph() {
        return this.provider.graph();
    }

    @Override
    public synchronized BackendStore loadSchemaStore(final String name) {
        if (this.schemaStore == null) {
            LOG.info("Init raft backend schema store");
            BackendStore store = this.provider.loadSchemaStore(name);
            this.schemaStore = new RaftBackendStore(store, this.context);
            this.context.addStore(name, this.schemaStore);
        }
        return this.schemaStore;
    }

    @Override
    public synchronized BackendStore loadGraphStore(String name) {
        if (this.graphStore == null) {
            LOG.info("Init raft backend graph store");
            BackendStore store = this.provider.loadGraphStore(name);
            this.graphStore = new RaftBackendStore(store, this.context);
            this.context.addStore(name, this.graphStore);
        }
        return this.graphStore;
    }

    @Override
    public synchronized BackendStore loadSystemStore(String name) {
        if (this.systemStore == null) {
            LOG.info("Init raft backend system store");
            BackendStore store = this.provider.loadSystemStore(name);
            this.systemStore = new RaftBackendStore(store, this.context);
            this.context.addStore(name, this.systemStore);
        }
        return this.systemStore;
    }

    @Override
    public void open(String name) {
        this.provider.open(name);
    }

    @Override
    public void waitStoreStarted() {
        this.context.initRaftNode();
        this.context.waitRaftNodeStarted();
    }

    @Override
    public void close() {
        this.provider.close();
        this.context.close();
    }

    @Override
    public void init() {
        this.checkOpened();
        for (RaftBackendStore store : this.stores()) {
            store.init();
        }
        this.notifyAndWaitEvent(Events.STORE_INIT);

        LOG.debug("Graph '{}' store has been initialized", this.graph());
    }

    @Override
    public void clear() {
        this.checkOpened();
        for (RaftBackendStore store : this.stores()) {
            // Just clear tables of store, not clear space
            store.clear(false);
        }
        for (RaftBackendStore store : this.stores()) {
            // Only clear space of store
            store.clear(true);
        }
        this.notifyAndWaitEvent(Events.STORE_CLEAR);

        LOG.debug("Graph '{}' store has been cleared", this.graph());
    }

    @Override
    public void truncate() {
        this.checkOpened();
        for (RaftBackendStore store : this.stores()) {
            store.truncate();
        }
        this.notifyAndWaitEvent(Events.STORE_TRUNCATE);

        LOG.debug("Graph '{}' store has been truncated", this.graph());
    }

    @Override
    public void initSystemInfo(HugeGraph graph) {
        this.provider.initSystemInfo(graph);
    }

    @Override
    public void listen(EventListener listener) {
        this.provider.listen(listener);
    }

    @Override
    public void unlisten(EventListener listener) {
        this.provider.unlisten(listener);
    }
}
