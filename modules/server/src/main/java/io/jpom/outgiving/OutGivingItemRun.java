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
package io.jpom.outgiving;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.BetweenFormatter;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.SystemClock;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.EnumUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpStatus;
import com.alibaba.fastjson2.JSONObject;
import io.jpom.common.BaseServerController;
import io.jpom.common.Const;
import io.jpom.common.JsonMessage;
import io.jpom.model.AfterOpt;
import io.jpom.model.data.NodeModel;
import io.jpom.model.log.OutGivingLog;
import io.jpom.model.outgiving.OutGivingModel;
import io.jpom.model.outgiving.OutGivingNodeProject;
import io.jpom.model.user.UserModel;
import io.jpom.service.node.NodeService;
import io.jpom.service.outgiving.DbOutGivingLogService;
import io.jpom.service.outgiving.OutGivingServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * @author bwcx_jzy
 * @since 2021/12/10
 */
@Slf4j
public class OutGivingItemRun implements Callable<OutGivingNodeProject.Status> {

    private final String outGivingId;
    private final OutGivingNodeProject outGivingNodeProject;
    private final NodeModel nodeModel;
    private final File file;
    private final AfterOpt afterOpt;
    private final UserModel userModel;
    private final boolean unzip;
    private final boolean clearOld;
    private final Integer sleepTime;
    private final String secondaryDirectory;
    private final Boolean closeFirst;
    /**
     * 数据库记录id
     */
    private final String logId;

    public OutGivingItemRun(OutGivingModel item,
                            OutGivingNodeProject outGivingNodeProject,
                            File file,
                            UserModel userModel,
                            boolean unzip,
                            Integer sleepTime) {
        this.outGivingId = item.getId();
        this.secondaryDirectory = item.getSecondaryDirectory();
        this.clearOld = item.clearOld();
        this.closeFirst = item.getUploadCloseFirst();
        this.unzip = unzip;
        this.outGivingNodeProject = outGivingNodeProject;
        this.file = file;
        this.afterOpt = ObjectUtil.defaultIfNull(EnumUtil.likeValueOf(AfterOpt.class, item.getAfterOpt()), AfterOpt.No);
        //
        NodeService nodeService = SpringUtil.getBean(NodeService.class);
        this.nodeModel = nodeService.getByKey(outGivingNodeProject.getNodeId());
        //
        this.userModel = userModel;
        this.logId = IdUtil.fastSimpleUUID();
        this.sleepTime = sleepTime;
    }

    @Override
    public OutGivingNodeProject.Status call() {
        OutGivingNodeProject.Status result;
        long time = SystemClock.now();
        String fileSize = FileUtil.readableFileSize(file);
        try {
            this.updateStatus(this.outGivingId, this.outGivingNodeProject,
                OutGivingNodeProject.Status.Ing, "开始分发");
            //
            JsonMessage<String> jsonMessage = OutGivingRun.fileUpload(file, this.secondaryDirectory,
                this.outGivingNodeProject.getProjectId(),
                unzip,
                afterOpt,
                this.nodeModel, this.userModel, this.clearOld,
                this.sleepTime, this.closeFirst);
            if (jsonMessage.getCode() == HttpStatus.HTTP_OK) {
                result = OutGivingNodeProject.Status.Ok;
            } else {
                result = OutGivingNodeProject.Status.Fail;
            }
            JSONObject jsonObject = jsonMessage.toJson();
            jsonObject.put("upload_duration", new BetweenFormatter(SystemClock.now() - time, BetweenFormatter.Level.MILLISECOND, 2).format());
            jsonObject.put("upload_file_size", fileSize);
            this.updateStatus(this.outGivingId, this.outGivingNodeProject, result, jsonObject.toString());
        } catch (Exception e) {
            log.error("{} {} 分发异常保存", this.outGivingNodeProject.getNodeId(), this.outGivingNodeProject.getProjectId(), e);
            result = OutGivingNodeProject.Status.Fail;
            JSONObject jsonObject = JsonMessage.toJson(500, e.getMessage());
            jsonObject.put("upload_duration", new BetweenFormatter(SystemClock.now() - time, BetweenFormatter.Level.MILLISECOND, 2).format());
            jsonObject.put("upload_file_size", fileSize);
            this.updateStatus(this.outGivingId, this.outGivingNodeProject, result, jsonObject.toString());
        }
        return result;
    }

    /**
     * 更新状态
     *
     * @param outGivingId              分发id
     * @param outGivingNodeProjectItem 分发项
     * @param status                   状态
     * @param msg                      消息描述
     */
    public void updateStatus(String outGivingId,
                             OutGivingNodeProject outGivingNodeProjectItem,
                             OutGivingNodeProject.Status status,
                             String msg) {
        String userId = this.userModel == null ? Const.SYSTEM_ID : this.userModel.getId();
        updateStatus(this.logId, outGivingId, outGivingNodeProjectItem, status, msg, userId);
    }

    /**
     * 更新状态
     *
     * @param logId                    日志ID
     * @param outGivingId              分发id
     * @param outGivingNodeProjectItem 分发项
     * @param status                   状态
     * @param msg                      消息描述
     */
    public static void updateStatus(String logId,
                                    String outGivingId,
                                    OutGivingNodeProject outGivingNodeProjectItem,
                                    OutGivingNodeProject.Status status,
                                    String msg,
                                    String userId) {
        OutGivingNodeProject finOutGivingNodeProject = null;
        OutGivingModel outGivingModel;
        OutGivingServer outGivingServer = SpringUtil.getBean(OutGivingServer.class);
        synchronized (outGivingId.intern()) {
            outGivingModel = outGivingServer.getByKey(outGivingId);

            List<OutGivingNodeProject> outGivingNodeProjects = outGivingModel.outGivingNodeProjectList();
            Assert.notEmpty(outGivingNodeProjects, "没有分发项目");
            //
            for (OutGivingNodeProject outGivingNodeProject : outGivingNodeProjects) {
                if (!outGivingNodeProject.getProjectId().equalsIgnoreCase(outGivingNodeProjectItem.getProjectId()) ||
                    !outGivingNodeProject.getNodeId().equalsIgnoreCase(outGivingNodeProjectItem.getNodeId())) {
                    continue;
                }
                outGivingNodeProject.setStatus(status.getCode());
                outGivingNodeProject.setResult(msg);
                outGivingNodeProject.setLastOutGivingTime(DateUtil.now());
                //
                finOutGivingNodeProject = outGivingNodeProject;
            }
            {
                List<OutGivingNodeProject.Status> collect = outGivingNodeProjects
                    .stream()
                    .map(outGivingNodeProject -> EnumUtil.likeValueOf(OutGivingNodeProject.Status.class, outGivingNodeProject.getStatus()))
                    .collect(Collectors.toList());
                OutGivingModel.Status outGivingStatus = CollUtil.contains(collect, OutGivingNodeProject.Status.Ing)
                    ? OutGivingModel.Status.ING : OutGivingModel.Status.DONE;
                // 更新分发数据
                OutGivingModel outGivingModel1 = new OutGivingModel();
                outGivingModel1.setId(outGivingId);
                outGivingModel1.setStatus(outGivingStatus.getCode());
                outGivingModel1.outGivingNodeProjectList(outGivingNodeProjects);
                outGivingServer.update(outGivingModel1);
            }
        }
        // 更新日志数据
        OutGivingLog outGivingLog = new OutGivingLog();
        outGivingLog.setWorkspaceId(outGivingModel.getWorkspaceId());
        outGivingLog.setId(StrUtil.emptyToDefault(logId, IdUtil.fastSimpleUUID()));

        if (finOutGivingNodeProject != null) {
            outGivingLog.setNodeId(finOutGivingNodeProject.getNodeId());
            outGivingLog.setProjectId(finOutGivingNodeProject.getProjectId());
        }
        outGivingLog.setModifyUser(userId);
        outGivingLog.setOutGivingId(outGivingId);
        outGivingLog.setResult(msg);
        outGivingLog.setStatus(status.getCode());
        try {
            BaseServerController.resetInfo(UserModel.EMPTY);
            DbOutGivingLogService dbOutGivingLogService = SpringUtil.getBean(DbOutGivingLogService.class);
            if (status == OutGivingNodeProject.Status.Ing || status == OutGivingNodeProject.Status.Cancel) {
                // 开始或者 取消都还没有记录
                dbOutGivingLogService.insert(outGivingLog);
            } else {
                outGivingLog.setEndTime(SystemClock.now());
                dbOutGivingLogService.update(outGivingLog);
            }
        } finally {
            BaseServerController.removeEmpty();
        }
    }
}
