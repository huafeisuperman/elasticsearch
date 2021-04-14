/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.dynamic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionModule;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.NamedRegistry;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Guice;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.Key;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.inject.multibindings.MapBinder;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.SettingUpgrader;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.node.Node;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.PluginInfo;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestHeaderDefinition;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ExecutorBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.watcher.FileChangesListener;
import org.elasticsearch.watcher.FileWatcher;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * Description:
 *
 * @author: huafei
 * @date: 2021.03.25
 */
public class PluginDiscoveryService extends AbstractLifecycleComponent {

    private ResourceWatcherService resourceWatcherService;

    private Injector injector;

    private Logger logger = LogManager.getLogger(PluginDiscoveryService.class);

    public PluginDiscoveryService(Injector injector) {
        this.injector = injector;
    }

    @Override
    protected void doStart() {
        try {
            Environment environment = injector.getInstance(Environment.class);
            resourceWatcherService = injector.getInstance(ResourceWatcherService.class);
            PluginsService pluginsService = injector.getInstance(PluginsService.class);
            ActionModule actionModule = injector.getInstance(ActionModule.class);
            ModulesBuilder modules = injector.getInstance(ModulesBuilder.class);
            Client client = injector.getInstance(Client.class);
            ClusterService clusterService = injector.getInstance(ClusterService.class);
            ThreadPool threadPool = injector.getInstance(ThreadPool.class);
            ScriptService scriptService = injector.getInstance(ScriptService.class);
            NamedXContentRegistry xContentRegistry = injector.getInstance(NamedXContentRegistry.class);
            NodeEnvironment nodeEnvironment = injector.getInstance(NodeEnvironment.class);
            NamedWriteableRegistry namedWriteableRegistry = injector.getInstance(NamedWriteableRegistry.class);
            Node node= injector.getInstance(Node.class);
            List<Setting<?>> additionalSettings = node.getAdditionalSettings();
            List<String> additionalSettingsFilter = node.getAdditionalSettingsFilter();
            Set<SettingUpgrader<?>> settingsUpgraders = node.getSettingsUpgraders();
            Collection<LifecycleComponent> pluginLifecycleComponents = node.getPluginLifecycleComponents();
            TransportService transportService = injector.getInstance(TransportService.class);
            NodeClient nodeClient = injector.getInstance(NodeClient.class);







            final FileWatcher watcher = new FileWatcher(environment.pluginsFile());
            FileChangesListener fileChangesListener = new FileChangesListener() {
                @Override
                public void onDirectoryCreated(Path file) {
                    logger.info("new plugin directory[{}]", file.toAbsolutePath());
                    try {
                        Tuple<PluginInfo, Plugin> tuples = pluginsService.loadPlugin(file);
                        if (!alreadyContainPlugin(tuples.v1(), pluginsService)) {
                            pluginsService.addDynamicPlugin(tuples);

                            //reload getFeature()
                            environment.settings().setSettings(pluginsService.updatedSettings().getSettings());


                            ModulesBuilder newModulesBuilder = new ModulesBuilder();
                            //reload createGuiceModules()
                            for (Module pluginModule : tuples.v2().createGuiceModules()) {
                                modules.add(pluginModule);
                                newModulesBuilder.add(pluginModule);
                            }

                            //reload getGuiceServiceClasses()
                            List<LifecycleComponent> newLifecycleComponent = new ArrayList<>();

                            List<LifecycleComponent> lfComponent = tuples.v2().getGuiceServiceClasses().stream().map(injector::getInstance).collect(Collectors.toList());
                            pluginLifecycleComponents.addAll(lfComponent);
                            newLifecycleComponent.addAll(lfComponent);

                            //reload createComponents
                            Collection<Object> components = tuples.v2().createComponents(client, clusterService, threadPool, resourceWatcherService,
                                scriptService, xContentRegistry, environment, nodeEnvironment,
                                namedWriteableRegistry);

                            modules.add(b -> components.stream().forEach(p -> b.bind((Class) p.getClass()).toInstance(p)));
                            newModulesBuilder.add(b -> components.stream().forEach(p -> b.bind((Class) p.getClass()).toInstance(p)));

                            pluginLifecycleComponents.addAll(components.stream().filter(p -> p instanceof LifecycleComponent).
                                map(p -> (LifecycleComponent) p).collect(Collectors.toList()));
                            newLifecycleComponent.addAll(components.stream().filter(p -> p instanceof LifecycleComponent).
                                map(p -> (LifecycleComponent) p).collect(Collectors.toList()));


                            //reload name writer
                            xContentRegistry.getRegistry().putAll(new NamedXContentRegistry(Stream.of(
                                pluginsService.filterPlugins(Plugin.class).stream()
                                    .flatMap(p -> p.getNamedXContent().stream()))
                                .flatMap(Function.identity()).collect(toList())).getRegistry());

                            //reload registry
                            namedWriteableRegistry.getRegistry().putAll(new NamedWriteableRegistry(Stream.of(
                                pluginsService.filterPlugins(Plugin.class).stream()
                                    .flatMap(p -> p.getNamedWriteables().stream()))
                                .flatMap(Function.identity()).collect(toList())).getRegistry());


                            additionalSettings.addAll(tuples.v2().getSettings());
                            additionalSettingsFilter.addAll(tuples.v2().getSettingsFilter());
                            settingsUpgraders.addAll(tuples.v2().getSettingUpgraders());


                            Map<String, ExecutorBuilder> newExecutorBuilder = new HashMap();
                            Map<String, ThreadPool.ExecutorHolder> newExecutorHolder = new HashMap();
                            for (final ExecutorBuilder<?> builder : tuples.v2().getExecutorBuilders(environment.settings())) {
                                if (threadPool.getBuilders().containsKey(builder)) {
                                    throw new IllegalArgumentException("builder with name [" + builder.name() + "] already exists");
                                }
                                threadPool.getBuilders().put(builder.name(), builder);
                                newExecutorBuilder.put(builder.name(), builder);
                            }

                            for (final Map.Entry<String, ExecutorBuilder> entry : newExecutorBuilder.entrySet()) {
                                final ExecutorBuilder.ExecutorSettings executorSettings = entry.getValue().getSettings(environment.settings());
                                final ThreadPool.ExecutorHolder executorHolder = entry.getValue().build(executorSettings, threadPool.getThreadContext());
                                if (threadPool.getExecutors().containsKey(executorHolder.info.getName())) {
                                    throw new IllegalStateException("duplicate executors with name [" + executorHolder.info.getName() + "] registered");
                                }
                                logger.debug("created thread pool: {}", entry.getValue().formatInfo(executorHolder.info));
                                threadPool.getExecutors().put(entry.getKey(), executorHolder);
                                newExecutorHolder.put(entry.getKey(), executorHolder);
                            }


                            List<ThreadPool.Info> infos =
                                newExecutorHolder
                                    .values()
                                    .stream()
                                    .filter(holder -> holder.info.getName().equals("same") == false)
                                    .map(holder -> holder.info)
                                    .collect(Collectors.toList());

                            threadPool.getThreadPoolInfo().getInfos().addAll(infos);


                            if (ActionPlugin.class.isAssignableFrom(tuples.v2().getClass())) {
                                actionModule.reloadPlugins(Collections.singletonList((ActionPlugin) tuples.v2()));

                                class ActionRegistry extends NamedRegistry<ActionPlugin.ActionHandler<?, ?>> {
                                    ActionRegistry() {
                                        super("action");
                                    }

                                    public void register(ActionPlugin.ActionHandler<?, ?> handler) {
                                        register(handler.getAction().name(), handler);
                                    }

                                    public <Request extends ActionRequest, Response extends ActionResponse> void register(
                                        ActionType<Response> action, Class<? extends TransportAction<Request, Response>> transportAction,
                                        Class<?>... supportTransportActions) {
                                        register(new ActionPlugin.ActionHandler<>(action, transportAction, supportTransportActions));
                                    }
                                }
                                ActionRegistry actions = new ActionRegistry();
                                ((ActionPlugin) tuples.v2()).getActions().stream().forEach(actions::register);
                                actionModule.getActions().putAll(actions.getRegistry());

                                actionModule.getActionFilters().addFilters(((ActionPlugin) tuples.v2()).getActionFilters().stream().collect(Collectors.toSet()));

                                actionModule.getRestController().getHeadersToCopy().addAll(((ActionPlugin) tuples.v2()).getRestHeaders().stream().collect(Collectors.toSet()));

                                transportService.getTaskManager().getTaskHeaders().addAll(((ActionPlugin) tuples.v2()).getTaskHeaders());


                                for (ActionPlugin.ActionHandler<?, ?> value : actions.getRegistry().values()) {
                                    Constructor constructor = value.getTransportAction().getConstructors()[0];
                                    Class<?>[] classes = constructor.getParameterTypes();
                                    Object[] objects = new Object[classes.length];
                                    for (int i = 0; i < classes.length; i++) {
                                        objects[i] = injector.getInstance(classes[i]);
                                    }
                                    nodeClient.getActions().put(value.getAction(), (TransportAction) constructor.newInstance(objects));
                                }



                                if (null == actionModule.getRestController().getHandlerWrapper()) {
                                    UnaryOperator<RestHandler> unaryOperator =  ((ActionPlugin) tuples.v2()).getRestHandlerWrapper(threadPool.getThreadContext());
                                    if (null != unaryOperator) {
                                        actionModule.getRestController().setHandlerWrapper(unaryOperator);
                                    }

                                }

                            }


                            newModulesBuilder.createInjector();

                            newLifecycleComponent.forEach(LifecycleComponent::start);
                        }
                    } catch (Exception e) {
                        logger.error("load plugin[{}] error", file.getFileName(), e);
                    }

                }
            };
            watcher.addListener(fileChangesListener);
            resourceWatcherService.add(watcher, ResourceWatcherService.Frequency.MEDIUM);
        } catch (Exception e) {
            logger.info("file change plugins initial error", e);
        }
    }

    @Override
    protected void doStop() {

    }

    @Override
    protected void doClose() throws IOException {

    }

    public boolean alreadyContainPlugin(PluginInfo pluginInfo, PluginsService ps) {
        for (Tuple<PluginInfo, Plugin> plugin : ps.getPlugins()) {
            if (pluginInfo.getName().equals(plugin.v1().getName())) {
                return true;
            }
        }
        return false;
    }
}
