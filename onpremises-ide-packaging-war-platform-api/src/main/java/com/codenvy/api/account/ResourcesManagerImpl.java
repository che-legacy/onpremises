/*
 *  2012-2016 Codenvy, S.A.
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.api.account;


import com.codenvy.api.metrics.server.ResourcesChangesNotifier;
import com.codenvy.api.metrics.server.WorkspaceLockEvent;
import com.codenvy.api.metrics.server.dao.MeterBasedStorage;
import com.codenvy.api.metrics.server.limit.ActiveTasksHolder;
import com.codenvy.api.metrics.server.limit.MeteredTask;
import com.codenvy.api.metrics.server.limit.WorkspaceResourcesUsageLimitChangedEvent;
import com.codenvy.api.metrics.server.period.MetricPeriod;
import com.codenvy.api.resources.server.ResourcesManager;
import com.codenvy.api.resources.shared.dto.UpdateResourcesDescriptor;

import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ForbiddenException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.notification.EventService;
import org.eclipse.che.api.runner.internal.Constants;
import org.eclipse.che.api.workspace.server.dao.Workspace;
import org.eclipse.che.api.workspace.server.dao.WorkspaceDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;
import static org.eclipse.che.api.account.server.Constants.RESOURCES_LOCKED_PROPERTY;
import static org.eclipse.che.api.workspace.server.Constants.RESOURCES_USAGE_LIMIT_PROPERTY;

/**
 * Implementation of ResourcesManager for OnPremises packaging
 *
 * @author Sergii Leschenko
 */
public class ResourcesManagerImpl implements ResourcesManager {
    private static final Logger LOG = LoggerFactory.getLogger(ResourcesManagerImpl.class);

    private final WorkspaceDao             workspaceDao;
    private final ResourcesChangesNotifier resourcesChangesNotifier;
    private final EventService             eventService;
    private final MetricPeriod             metricPeriod;
    private final MeterBasedStorage        meterBasedStorage;
    private final ActiveTasksHolder        activeTasksHolder;

    @Inject
    public ResourcesManagerImpl(WorkspaceDao workspaceDao,
                                ResourcesChangesNotifier resourcesChangesNotifier,
                                EventService eventService,
                                MetricPeriod metricPeriod,
                                MeterBasedStorage meterBasedStorage,
                                ActiveTasksHolder activeTasksHolder) {
        this.workspaceDao = workspaceDao;
        this.resourcesChangesNotifier = resourcesChangesNotifier;
        this.eventService = eventService;
        this.metricPeriod = metricPeriod;
        this.meterBasedStorage = meterBasedStorage;
        this.activeTasksHolder = activeTasksHolder;
    }

    @Override
    public void redistributeResources(String accountId, List<UpdateResourcesDescriptor> updates) throws NotFoundException,
                                                                                                        ServerException,
                                                                                                        ConflictException,
                                                                                                        ForbiddenException {
        final Map<String, Workspace> ownWorkspaces = new HashMap<>();
        for (Workspace workspace : workspaceDao.getByAccount(accountId)) {
            ownWorkspaces.put(workspace.getId(), workspace);
        }
        validateUpdates(accountId, updates, ownWorkspaces);

        for (UpdateResourcesDescriptor resourcesDescriptor : updates) {
            Workspace workspace = ownWorkspaces.get(resourcesDescriptor.getWorkspaceId());

            if (resourcesDescriptor.getRunnerRam() != null) {
                workspace.getAttributes().put(Constants.RUNNER_MAX_MEMORY_SIZE, Integer.toString(resourcesDescriptor.getRunnerRam()));
            }

            if (resourcesDescriptor.getBuilderTimeout() != null) {
                workspace.getAttributes().put(org.eclipse.che.api.builder.internal.Constants.BUILDER_EXECUTION_TIME,
                                              Integer.toString(resourcesDescriptor.getBuilderTimeout()));
            }

            if (resourcesDescriptor.getRunnerTimeout() != null) {
                workspace.getAttributes().put(Constants.RUNNER_LIFETIME, Integer.toString(resourcesDescriptor.getRunnerTimeout()));
            }

            boolean changedWorkspaceLock = false;
            if (resourcesDescriptor.getResourcesUsageLimit() != null) {
                if (resourcesDescriptor.getResourcesUsageLimit() == -1) {
                    workspace.getAttributes().remove(RESOURCES_USAGE_LIMIT_PROPERTY);
                    if (workspace.getAttributes().remove(RESOURCES_LOCKED_PROPERTY) != null) {
                        changedWorkspaceLock = true;
                    }
                } else {
                    workspace.getAttributes().put(RESOURCES_USAGE_LIMIT_PROPERTY,
                                                  Double.toString(resourcesDescriptor.getResourcesUsageLimit()));

                    long billingPeriodStart = metricPeriod.getCurrent().getStartDate().getTime();
                    Double usedMemory = meterBasedStorage.getUsedMemoryByWorkspace(workspace.getId(),
                                                                                   billingPeriodStart,
                                                                                   System.currentTimeMillis());
                    if (usedMemory < resourcesDescriptor.getResourcesUsageLimit()) {
                        if (workspace.getAttributes().remove(RESOURCES_LOCKED_PROPERTY) != null) {
                            changedWorkspaceLock = true;
                        }
                    } else {
                        workspace.getAttributes().put(RESOURCES_LOCKED_PROPERTY, "true");
                        changedWorkspaceLock = true;
                    }
                }
            }

            workspaceDao.update(workspace);

            if (resourcesDescriptor.getRunnerRam() != null) {
                resourcesChangesNotifier.publishTotalMemoryChangedEvent(resourcesDescriptor.getWorkspaceId(),
                                                                        Integer.toString(resourcesDescriptor.getRunnerRam()));
            }

            if (changedWorkspaceLock) {
                if (workspace.getAttributes().containsKey(RESOURCES_LOCKED_PROPERTY)) {
                    eventService.publish(WorkspaceLockEvent.workspaceLockedEvent(workspace.getId()));
                    for (MeteredTask meteredTask : activeTasksHolder.getActiveTasks(workspace.getId())) {
                        try {
                            meteredTask.interrupt();
                        } catch (Exception e) {
                            LOG.error("Can't interrupt task with id " + meteredTask.getId(), e);
                        }
                    }
                } else {
                    eventService.publish(WorkspaceLockEvent.workspaceUnlockedEvent(workspace.getId()));
                }
            }

            if (resourcesDescriptor.getResourcesUsageLimit() != null) {
                eventService.publish(new WorkspaceResourcesUsageLimitChangedEvent(workspace.getId()));
            }
        }
    }

    private void validateUpdates(String accountId, List<UpdateResourcesDescriptor> updates, Map<String, Workspace> ownWorkspaces)
            throws ForbiddenException, ConflictException, NotFoundException, ServerException {

        for (UpdateResourcesDescriptor resourcesDescriptor : updates) {
            if (!ownWorkspaces.containsKey(resourcesDescriptor.getWorkspaceId())) {
                throw new ForbiddenException(
                        format("Workspace %s is not related to account %s", resourcesDescriptor.getWorkspaceId(), accountId));
            }

            if (resourcesDescriptor.getRunnerTimeout() == null && resourcesDescriptor.getRunnerRam() == null
                && resourcesDescriptor.getBuilderTimeout() == null && resourcesDescriptor.getResourcesUsageLimit() == null) {
                throw new ConflictException(
                        format("Missed description of resources for workspace %s", resourcesDescriptor.getWorkspaceId()));
            }

            Integer runnerRam = resourcesDescriptor.getRunnerRam();
            if (runnerRam != null) {
                if (runnerRam < 0) {
                    throw new ConflictException(format("Size of RAM for workspace %s is a negative number",
                                                       resourcesDescriptor.getWorkspaceId()));
                }
            }

            if (resourcesDescriptor.getBuilderTimeout() != null && resourcesDescriptor.getBuilderTimeout() < 0) {
                throw new ConflictException(format("Builder timeout for workspace %s is a negative number",
                                                   resourcesDescriptor.getWorkspaceId()));
            }

            if (resourcesDescriptor.getRunnerTimeout() != null && resourcesDescriptor.getRunnerTimeout() < -1) {// we allow -1 here
                throw new ConflictException(format("Runner timeout for workspace %s is a negative number",
                                                   resourcesDescriptor.getWorkspaceId()));
            }

            if (resourcesDescriptor.getResourcesUsageLimit() != null && resourcesDescriptor.getResourcesUsageLimit() < -1) {
                throw new ConflictException(format("Resources usage limit for workspace %s is a negative number",
                                                   resourcesDescriptor.getWorkspaceId()));
            }
        }
    }
}
