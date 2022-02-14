/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Code Technology Studio
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.jpom;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.EnumUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.RemoveSwarmNodeCmdImpl;
import io.jpom.plugin.IDefaultPlugin;
import io.jpom.plugin.PluginConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * docker swarm
 *
 * @author bwcx_jzy
 * @since 2022/2/13
 */
@PluginConfig(name = "docker-cli:swarm")
@Slf4j
public class DefaultDockerSwarmPluginImpl implements IDefaultPlugin {

	@Override
	public Object execute(Object main, Map<String, Object> parameter) {
		String type = main.toString();
		switch (type) {
			case "inSpectSwarm":
				return this.inSpectSwarmCmd(parameter);
			case "tryInitializeSwarm":
				return this.tryInitializeSwarmCmd(parameter);
			case "joinSwarm":
				this.joinSwarmCmd(parameter);
				return null;
			case "listSwarmNodes":
				return this.listSwarmNodesCmd(parameter);
			case "leaveSwarm":
				this.leaveSwarmCmd(parameter);
				return null;
			case "updateSwarmNode":
				this.updateSwarmNodeCmd(parameter);
				return null;
			case "removeSwarmNode":
				this.removeSwarmNodeCmd(parameter);
				return null;
			case "listServices":
				return this.listServicesCmd(parameter);
			case "listTasks":
				return this.listTasksCmd(parameter);
			case "removeService":
				this.removeServiceCmd(parameter);
				return null;
			case "updateService":
				this.updateServiceCmd(parameter);
				return null;
			default:
				break;
		}
		return null;
	}


	public void updateServiceCmd(Map<String, Object> parameter) {
		DockerClient dockerClient = DockerUtil.build(parameter);
		try {
			ServiceSpec serviceSpec = new ServiceSpec();
			String name = (String) parameter.get("name");
			serviceSpec.withName(name);
			{
				String mode = (String) parameter.get("mode");
				ServiceMode serviceMode = EnumUtil.fromString(ServiceMode.class, mode);
				ServiceModeConfig serviceModeConfig = new ServiceModeConfig();
				if (serviceMode == ServiceMode.GLOBAL) {
					serviceModeConfig.withGlobal(new ServiceGlobalModeOptions());
				} else if (serviceMode == ServiceMode.REPLICATED) {
					Object replicas = parameter.get("replicas");
					ServiceReplicatedModeOptions serviceReplicatedModeOptions = new ServiceReplicatedModeOptions();
					serviceReplicatedModeOptions.withReplicas(Convert.toInt(replicas, 1));
					serviceModeConfig.withReplicated(serviceReplicatedModeOptions);
				}
				serviceSpec.withMode(serviceModeConfig);
			}
//			{
//				UpdateConfig updateConfig = new UpdateConfig();
//
//				serviceSpec.withUpdateConfig(updateConfig);
//			}
			{
				String image = (String) parameter.get("image");
				TaskSpec taskSpec = new TaskSpec();
				ContainerSpec containerSpec = new ContainerSpec();
				containerSpec.withImage(image);
				//
				Collection<Map<String, String>> args = (Collection) parameter.get("args");
				if (CollUtil.isNotEmpty(args)) {
					List<String> value = args.stream()
							.map(stringStringMap -> stringStringMap.get("value"))
							.filter(StrUtil::isNotEmpty)
							.collect(Collectors.toList());
					containerSpec.withArgs(value);
				}
				Collection<Map<String, String>> envs = (Collection) parameter.get("envs");
				if (CollUtil.isNotEmpty(envs)) {
					List<String> value = envs.stream()
							.map(stringStringMap -> {
								String name1 = stringStringMap.get("name");
								String value1 = stringStringMap.get("value");
								if (StrUtil.isEmpty(name1)) {
									return null;
								}
								return StrUtil.format("{}={}", name1, value1);
							})
							.filter(StrUtil::isNotEmpty)
							.collect(Collectors.toList());
					containerSpec.withEnv(value);
				}
				Collection<Map<String, String>> commands = (Collection) parameter.get("commands");
				if (CollUtil.isNotEmpty(commands)) {
					List<String> value = commands.stream()
							.map(stringStringMap -> stringStringMap.get("value"))
							.filter(StrUtil::isNotEmpty)
							.collect(Collectors.toList());
					containerSpec.withCommand(value);
				}
				//
				Collection<Map<String, String>> volumes = (Collection<Map<String, String>>) parameter.get("volumes");
				if (CollUtil.isNotEmpty(volumes)) {
					List<Mount> value = volumes.stream()
							.map(stringStringMap -> {
								String source = stringStringMap.get("source");
								String target = stringStringMap.get("target");
								if (StrUtil.hasBlank(source, target)) {
									return null;
								}
								String type = stringStringMap.get("type");
								MountType mountType = EnumUtil.fromString(MountType.class, type);
								Mount mount = new Mount();
								mount.withSource(source);
								mount.withTarget(target);
								mount.withType(mountType);
								return mount;
							})
							.filter(Objects::nonNull)
							.collect(Collectors.toList());
					containerSpec.withMounts(value);
				}
				taskSpec.withContainerSpec(containerSpec);
				//
				serviceSpec.withTaskTemplate(taskSpec);
			}
			{
				String endpointResolutionModeStr = (String) parameter.get("endpointResolutionMode");
				EndpointResolutionMode endpointResolutionMode = EnumUtil.fromString(EndpointResolutionMode.class, endpointResolutionModeStr);
				EndpointSpec endpointSpec = new EndpointSpec();
				endpointSpec.withMode(endpointResolutionMode);
				Collection<Map<String, Object>> exposedPorts = (Collection) parameter.get("exposedPorts");
				if (CollUtil.isNotEmpty(exposedPorts)) {
					List<PortConfig> portConfigs = exposedPorts.stream()
							.map(stringStringMap -> {
								Object port = stringStringMap.get("targetPort");
								Object publicPort = stringStringMap.get("publishedPort");
								if (ObjectUtil.hasEmpty(port, publicPort)) {
									return null;
								}
								PortConfig portConfig = new PortConfig();
								String mode = (String) stringStringMap.get("publishMode");
								PortConfig.PublishMode publishMode = EnumUtil.fromString(PortConfig.PublishMode.class, mode);
								portConfig.withPublishMode(publishMode);
								String scheme = (String) stringStringMap.get("protocol");
								PortConfigProtocol protocol = EnumUtil.fromString(PortConfigProtocol.class, scheme);
								portConfig.withProtocol(protocol);
								portConfig.withTargetPort(Convert.toInt(port, 0));
								portConfig.withPublishedPort(Convert.toInt(port, 0));
								return portConfig;
							})
							.filter(Objects::nonNull)
							.collect(Collectors.toList());
					endpointSpec.withPorts(portConfigs);
				}
				serviceSpec.withEndpointSpec(endpointSpec);
			}
			String serviceId = (String) parameter.get("serviceId");
			String version = (String) parameter.get("version");
			if (StrUtil.isNotEmpty(serviceId)) {
				UpdateServiceCmd updateServiceCmd = dockerClient.updateServiceCmd(serviceId, serviceSpec);
				updateServiceCmd.withVersion(Convert.toLong(version, 0L));
				updateServiceCmd.exec();
			} else {
				CreateServiceCmd createServiceCmd = dockerClient.createServiceCmd(serviceSpec);
				createServiceCmd.exec();
			}
		} finally {
			IoUtil.close(dockerClient);
		}
	}

	public void removeServiceCmd(Map<String, Object> parameter) {
		DockerClient dockerClient = DockerUtil.build(parameter);
		try {
			String serviceId = (String) parameter.get("serviceId");
			RemoveServiceCmd removeServiceCmd = dockerClient.removeServiceCmd(serviceId);
			removeServiceCmd.exec();
		} finally {
			IoUtil.close(dockerClient);
		}
	}

	private List<JSONObject> listTasksCmd(Map<String, Object> parameter) {
		DockerClient dockerClient = DockerUtil.build(parameter);
		try {
			ListTasksCmd listTasksCmd = dockerClient.listTasksCmd();
			String serviceId = (String) parameter.get("serviceId");
			String id = (String) parameter.get("id");
			if (StrUtil.isNotEmpty(serviceId)) {
				listTasksCmd.withServiceFilter(serviceId);
			}
			if (StrUtil.isNotEmpty(id)) {
				listTasksCmd.withIdFilter(id);
			}
			String name = (String) parameter.get("name");
			if (StrUtil.isNotEmpty(name)) {
				listTasksCmd.withNameFilter(name);
			}
			String node = (String) parameter.get("node");
			if (StrUtil.isNotEmpty(node)) {
				listTasksCmd.withNodeFilter(node);
			}
			String state = (String) parameter.get("state");
			if (StrUtil.isNotEmpty(state)) {
				TaskState taskState = EnumUtil.fromString(TaskState.class, state);
				listTasksCmd.withStateFilter(taskState);
			}
			List<Task> exec = listTasksCmd.exec();
			return exec.stream().map(swarmNode -> (JSONObject) JSONObject.toJSON(swarmNode)).collect(Collectors.toList());
		} finally {
			IoUtil.close(dockerClient);
		}
	}

	public List<JSONObject> listServicesCmd(Map<String, Object> parameter) {
		DockerClient dockerClient = DockerUtil.build(parameter);
		try {
			ListServicesCmd listServicesCmd = dockerClient.listServicesCmd();
			String id = (String) parameter.get("id");
			String name = (String) parameter.get("name");
			if (StrUtil.isNotEmpty(id)) {
				listServicesCmd.withIdFilter(CollUtil.newArrayList(id));
			}
			if (StrUtil.isNotEmpty(name)) {
				listServicesCmd.withNameFilter(CollUtil.newArrayList(name));
			}
			List<Service> exec = listServicesCmd.exec();
			return exec.stream().map(swarmNode -> (JSONObject) JSONObject.toJSON(swarmNode)).collect(Collectors.toList());
		} finally {
			IoUtil.close(dockerClient);
		}
	}

	private void removeSwarmNodeCmd(Map<String, Object> parameter) {
		DockerClient dockerClient = DockerUtil.build(parameter);
		try {
			DockerCmdExecFactory dockerCmdExecFactory = (DockerCmdExecFactory) ReflectUtil.getFieldValue(dockerClient, "dockerCmdExecFactory");
			Assert.notNull(dockerCmdExecFactory, "当前方法不被支持，暂时不能使用");
			String nodeId = (String) parameter.get("nodeId");
			RemoveSwarmNodeCmdImpl removeSwarmNodeCmd = new RemoveSwarmNodeCmdImpl(
					dockerCmdExecFactory.removeSwarmNodeCmdExec(), nodeId);
			removeSwarmNodeCmd.withForce(true);
			removeSwarmNodeCmd.exec();
		} finally {
			IoUtil.close(dockerClient);
		}
	}

	private void updateSwarmNodeCmd(Map<String, Object> parameter) {
		DockerClient dockerClient = DockerUtil.build(parameter);
		try {
			String nodeId = (String) parameter.get("nodeId");
			List<SwarmNode> nodes = dockerClient.listSwarmNodesCmd()
					.withIdFilter(CollUtil.newArrayList(nodeId)).exec();
			SwarmNode swarmNode = CollUtil.getFirst(nodes);
			Assert.notNull(swarmNode, "没有对应的节点");
			ObjectVersion version = swarmNode.getVersion();
			Assert.notNull(version, "对应的节点信息不完整不能继续");
			//
			String availabilityStr = (String) parameter.get("availability");
			String roleStr = (String) parameter.get("role");
			//
			SwarmNodeAvailability availability = EnumUtil.fromString(SwarmNodeAvailability.class, availabilityStr);
			SwarmNodeRole role = EnumUtil.fromString(SwarmNodeRole.class, roleStr);
			UpdateSwarmNodeCmd swarmNodeCmd = dockerClient.updateSwarmNodeCmd();
			swarmNodeCmd.withSwarmNodeId(nodeId);
			SwarmNodeSpec swarmNodeSpec = new SwarmNodeSpec();
			swarmNodeSpec.withAvailability(availability);
			swarmNodeSpec.withRole(role);
			swarmNodeCmd.withSwarmNodeSpec(swarmNodeSpec);
			swarmNodeCmd.withVersion(version.getIndex());
			swarmNodeCmd.exec();
		} finally {
			IoUtil.close(dockerClient);
		}
	}


	private void leaveSwarmCmd(Map<String, Object> parameter) {
		DockerClient dockerClient = DockerUtil.build(parameter);
		try {
			Object forceStr = parameter.get("force");
			boolean force = Convert.toBool(forceStr, false);
			LeaveSwarmCmd leaveSwarmCmd = dockerClient.leaveSwarmCmd();
			if (force) {
				leaveSwarmCmd.withForceEnabled(true);
			}
			leaveSwarmCmd.exec();
		} finally {
			IoUtil.close(dockerClient);
		}
	}


//	private List<JSONObject> listSwarmNodesCmd(Map<String, Object> parameter) {
//		DockerClient dockerClient = DockerUtil.build(parameter);
//		try {
//			LeaveSwarmCmd leaveSwarmCmd = dockerClient.leaveSwarmCmd();
//			leaveSwarmCmd.withForceEnabled(true)
//		} finally {
//			IoUtil.close(dockerClient);
//		}
//	}

	private List<JSONObject> listSwarmNodesCmd(Map<String, Object> parameter) {
		DockerClient dockerClient = DockerUtil.build(parameter);
		try {
			ListSwarmNodesCmd listSwarmNodesCmd = dockerClient.listSwarmNodesCmd();
			String id = (String) parameter.get("id");
			if (StrUtil.isNotEmpty(id)) {
				listSwarmNodesCmd.withIdFilter(StrUtil.splitTrim(id, StrUtil.COMMA));
			}
			String role = (String) parameter.get("role");
			if (StrUtil.isNotEmpty(role)) {
				listSwarmNodesCmd.withRoleFilter(StrUtil.splitTrim(role, StrUtil.COMMA));
			}
			String name = (String) parameter.get("name");
			if (StrUtil.isNotEmpty(name)) {
				listSwarmNodesCmd.withNameFilter(StrUtil.splitTrim(name, StrUtil.COMMA));
			}
			List<SwarmNode> exec = listSwarmNodesCmd.exec();
			return exec.stream().map(swarmNode -> {
				JSONObject jsonObject = (JSONObject) JSONObject.toJSON(swarmNode);
				jsonObject.remove("rawValues");
				return jsonObject;
			}).collect(Collectors.toList());
		} finally {
			IoUtil.close(dockerClient);
		}
	}


	private void joinSwarmCmd(Map<String, Object> parameter) {
		DockerClient dockerClient = DockerUtil.build(parameter);
		try {
			String token = (String) parameter.get("token");
			String remoteAddrs = (String) parameter.get("remoteAddrs");
			JoinSwarmCmd joinSwarmCmd = dockerClient.joinSwarmCmd()
					.withRemoteAddrs(StrUtil.splitTrim(remoteAddrs, StrUtil.COMMA))
					.withJoinToken(token);
			joinSwarmCmd.exec();
		} finally {
			IoUtil.close(dockerClient);
		}
	}

	private JSONObject tryInitializeSwarmCmd(Map<String, Object> parameter) {
		DockerClient dockerClient = DockerUtil.build(parameter);
		try {
			// 先尝试获取
			try {
				Swarm exec = dockerClient.inspectSwarmCmd().exec();
				JSONObject jsonObject = (JSONObject) JSONObject.toJSON(exec);
				if (jsonObject != null) {
					return jsonObject;
				}
			} catch (Exception ignored) {
				//
			}
			// 尝试初始化
			SwarmSpec swarmSpec = new SwarmSpec();
			swarmSpec.withName("default");
			dockerClient.initializeSwarmCmd(swarmSpec).exec();
			// 获取信息
			Swarm exec = dockerClient.inspectSwarmCmd().exec();
			return (JSONObject) JSONObject.toJSON(exec);
		} finally {
			IoUtil.close(dockerClient);
		}
	}

	private JSONObject inSpectSwarmCmd(Map<String, Object> parameter) {
		DockerClient dockerClient = DockerUtil.build(parameter);
		try {
			Swarm exec = dockerClient.inspectSwarmCmd().exec();
			return (JSONObject) JSONObject.toJSON(exec);
		} finally {
			IoUtil.close(dockerClient);
		}
	}
}