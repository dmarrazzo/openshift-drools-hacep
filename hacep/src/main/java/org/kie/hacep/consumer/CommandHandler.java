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
package org.kie.hacep.consumer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.drools.core.common.EventFactHandle;
import org.kie.api.definition.type.Role;
import org.kie.api.definition.type.Timestamp;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.hacep.EnvConfig;
import org.kie.hacep.core.KieSessionContext;
import org.kie.hacep.core.infra.SessionSnapshooter;
import org.kie.hacep.core.infra.consumer.ConsumerController;
import org.kie.hacep.core.infra.utils.ConsumerUtils;
import org.kie.hacep.message.ControlMessage;
import org.kie.hacep.message.FactCountMessage;
import org.kie.hacep.message.FireAllRuleMessage;
import org.kie.hacep.message.GetObjectMessage;
import org.kie.hacep.message.ListKieSessionObjectMessage;
import org.kie.remote.DroolsExecutor;
import org.kie.remote.RemoteFactHandle;
import org.kie.remote.command.DeleteCommand;
import org.kie.remote.command.EventInsertCommand;
import org.kie.remote.command.FactCountCommand;
import org.kie.remote.command.FireAllRulesCommand;
import org.kie.remote.command.FireUntilHaltCommand;
import org.kie.remote.command.GetObjectCommand;
import org.kie.remote.command.HaltCommand;
import org.kie.remote.command.InsertCommand;
import org.kie.remote.command.ListObjectsCommand;
import org.kie.remote.command.ListObjectsCommandClassType;
import org.kie.remote.command.ListObjectsCommandNamedQuery;
import org.kie.remote.command.SnapshotOnDemandCommand;
import org.kie.remote.command.UpdateCommand;
import org.kie.remote.command.VisitorCommand;
import org.kie.remote.command.WorkingMemoryActionCommand;
import org.kie.remote.impl.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandHandler implements VisitorCommand {

    private static final Logger logger = LoggerFactory.getLogger(CommandHandler.class);

    private KieSessionContext kieSessionContext;
    private EnvConfig envConfig;
    private Producer producer;
    private SessionSnapshooter sessionSnapshooter;
    private volatile boolean firingUntilHalt;

    public CommandHandler(KieSessionContext kieSessionContext,
                          EnvConfig envConfig,
                          Producer producer,
                          SessionSnapshooter sessionSnapshooter) {
        this.kieSessionContext = kieSessionContext;
        this.envConfig = envConfig;
        this.producer = producer;
        this.sessionSnapshooter = sessionSnapshooter;
    }

    @Override
    public void visit(FireAllRulesCommand command) {
        int fires = kieSessionContext.getKieSession().fireAllRules();
        FireAllRuleMessage msg = new FireAllRuleMessage(command.getId(), fires);

        // command.isPermittedForReplicas() is true but only Leader should produce a message
        if (DroolsExecutor.getInstance().isLeader()) {
            producer.produceSync(envConfig.getKieSessionInfosTopicName(),
                                 command.getId(),
                                 msg);
        }
    }

    @Override
    public void visit(FireUntilHaltCommand command) {
        firingUntilHalt = true;
    }

    @Override
    public void visit(HaltCommand command) {
        firingUntilHalt = false;
    }

    @Override
    public void visit(InsertCommand command) {
        RemoteFactHandle remoteFH = command.getFactHandle();
        FactHandle fh = internalInsert(command, remoteFH.getObject());
        kieSessionContext.getFhManager().registerHandle(remoteFH, fh);
    }

    @Override
    public void visit(EventInsertCommand command) {
        internalInsert(command, command.getObject());
    }

    private FactHandle internalInsert(WorkingMemoryActionCommand command, Object obj) {
        FactHandle fh = isEvent(obj) ? insertEvent(command, obj) : insertFact(command, obj);
        if (firingUntilHalt) {
            kieSessionContext.getKieSession().fireAllRules();
        }
        if(logger.isDebugEnabled()){
            logger.debug("firingUntilHalt:{}", firingUntilHalt);
        }
        return fh;
    }

    private FactHandle insertEvent(WorkingMemoryActionCommand command, Object obj) {
        FactHandle fh;
        if (hasTimestamp(obj)) {
            fh = insertFact(command, obj);
            kieSessionContext.setClockAt(((EventFactHandle) fh).getStartTimestamp());
        } else {
            // if the event doesn't have an its own timestamp, it has to use the command's one and then
            // advance the pseudo clock to the command timestamp before inserting the event
            if (logger.isDebugEnabled()) {
                logger.debug("Event class " + obj.getClass().getName() + " doesn't have a timestamp property. Consider adding one.");
            }
            kieSessionContext.setClockAt(command.getTimestamp());
            fh = insertFact(command, obj);
        }
        return fh;
    }

    private FactHandle insertFact(WorkingMemoryActionCommand command, Object obj) {
        return kieSessionContext.getKieSession().getEntryPoint(command.getEntryPoint()).insert(obj);
    }

    @Override
    public void visit(DeleteCommand command) {
        FactHandle factHandle = kieSessionContext.getFhManager().mapRemoteFactHandle(command.getFactHandle());
        kieSessionContext.getKieSession().getEntryPoint(command.getEntryPoint()).delete(factHandle);
        if (firingUntilHalt) {
            kieSessionContext.getKieSession().fireAllRules();
        }
    }

    @Override
    public void visit(UpdateCommand command) {
        FactHandle factHandle = kieSessionContext.getFhManager().mapRemoteFactHandle(command.getFactHandle());
        kieSessionContext.getKieSession().getEntryPoint(command.getEntryPoint()).update(factHandle,
                                                                                        command.getObject());
        if (firingUntilHalt) {
            kieSessionContext.getKieSession().fireAllRules();
        }
    }

    @Override
    public void visit(ListObjectsCommand command) {
        List serializableItems = getObjectList(command);
        ListKieSessionObjectMessage msg = new ListKieSessionObjectMessage(command.getId(),
                                                                          serializableItems);
        producer.produceSync(envConfig.getKieSessionInfosTopicName(),
                             command.getId(),
                             msg);
    }

    private List getObjectList(ListObjectsCommand command) {
        Collection<? extends Object> objects = kieSessionContext.getKieSession().getEntryPoint(command.getEntryPoint()).getObjects();
        return getListFromSerializableCollection(objects);
    }

    @Override
    public void visit(ListObjectsCommandClassType command) {
        List serializableItems = getSerializableItemsByClassType(command);
        ListKieSessionObjectMessage msg = new ListKieSessionObjectMessage(command.getId(),
                                                                          serializableItems);
        producer.produceSync(envConfig.getKieSessionInfosTopicName(),
                             command.getId(),
                             msg);
    }

    @Override
    public void visit(GetObjectCommand command) {
        FactHandle factHandle = kieSessionContext.getFhManager().mapRemoteFactHandle(command.getRemoteFactHandle());
        Object object = kieSessionContext.getKieSession().getObject(factHandle);
        GetObjectMessage msg = new GetObjectMessage(command.getId(), object);
        producer.produceSync(envConfig.getKieSessionInfosTopicName(), command.getId(), msg);
    }

    private List getSerializableItemsByClassType(ListObjectsCommandClassType command) {
        Collection<? extends Object> objects = ObjectFilterHelper.getObjectsFilterByClassType(command.getClazzType(),
                                                                                              kieSessionContext.getKieSession());
        return getListFromSerializableCollection(objects);
    }

    private List getListFromSerializableCollection(Collection<?> objects) {
        List serializableItems = new ArrayList<>(objects.size());
        Iterator<? extends Object> iterator = objects.iterator();
        while (iterator.hasNext()) {
            Object o = iterator.next();
            serializableItems.add(o);
        }
        return serializableItems;
    }

    @Override
    public void visit(ListObjectsCommandNamedQuery command) {
        List serializableItems = getSerializableItemsByNamedQuery(command);
        ListKieSessionObjectMessage msg = new ListKieSessionObjectMessage(command.getId(),
                                                                          serializableItems);
        producer.produceSync(envConfig.getKieSessionInfosTopicName(),
                             command.getId(),
                             msg);
    }

    private List getSerializableItemsByNamedQuery(ListObjectsCommandNamedQuery command) {
        Collection<? extends Object> objects = ObjectFilterHelper.getObjectsFilterByNamedQuery(command.getNamedQuery(),
                                                                                               command.getObjectName(),
                                                                                               command.getParams(),
                                                                                               kieSessionContext.getKieSession());
        return getListFromSerializableCollection(objects);
    }

    @Override
    public void visit(FactCountCommand command) {
        FactCountMessage msg = new FactCountMessage(command.getId(), kieSessionContext.getKieSession().getFactCount());
        producer.produceSync(envConfig.getKieSessionInfosTopicName(), command.getId(), msg);
    }

    @Override
    public void visit(SnapshotOnDemandCommand command) {
        LocalDateTime lastSnapshotTime = sessionSnapshooter.getLastSnapshotTime();

        //if the lastSnapshot time is after the the age we perform a snapshot
        if (lastSnapshotTime == null) {
            sessionSnapshooter.serialize(kieSessionContext, command.getId(), 0l);
        } else if (LocalDateTime.now().minusSeconds(envConfig.getMaxSnapshotAge()).isAfter(lastSnapshotTime)) {

            ControlMessage lastControlMessage = ConsumerUtils.getLastEvent(envConfig.getControlTopicName(), envConfig.getPollTimeout());
            if (lastControlMessage != null) {
                sessionSnapshooter.serialize(kieSessionContext, lastControlMessage.getId(), lastControlMessage.getOffset());
            } else {
                sessionSnapshooter.serialize(kieSessionContext, command.getId(), 0l);
            }
        }
    }

    public static boolean isEvent(Object obj) {
        Role role = obj.getClass().getAnnotation(Role.class);
        return role != null && role.value() == Role.Type.EVENT;
    }

    public static boolean hasTimestamp(Object obj) {
        return obj.getClass().getAnnotation(Timestamp.class) != null;
    }

    public boolean isFiringUntilHalt() {
        return firingUntilHalt;
    }

}
