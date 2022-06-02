package com.genersoft.iot.vmp.vmanager.customApi.record;

import com.genersoft.iot.vmp.vmanager.customApi.common.MyPageInfo;
import com.genersoft.iot.vmp.vmanager.gb28181.platform.bean.ChannelReduce;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CloudRecordServiceImpl implements CloudRecordService {

    @Autowired
    private CloudRecordDao cloudRecordDao;

    @Override
    public MyPageInfo<Map<String, Object>> resetRecords(Map<String, Object> params) {
        Object listObj = params.get("list");
        List<Map<String, Object>> list;
        if (ObjectUtils.isNotEmpty(listObj)) {
            list = (List<Map<String, Object>>) listObj;
        } else {
            return null;
        }
        int pageNum = Integer.parseInt(params.get("page").toString());
        int pageSize = Integer.parseInt(params.get("count").toString());

        ChannelReduce channelReduce = new ChannelReduce();
        if (params.get("deviceId") != null) channelReduce.setDeviceId(params.get("deviceId").toString());
        if (params.get("manufacturer") != null) channelReduce.setManufacturer(params.get("manufacturer").toString());
        if (params.get("name") != null) channelReduce.setName(params.get("name").toString());

        List<Map<String, Object>> resultList = new ArrayList<>();
        List<ChannelReduce> channelReduces = cloudRecordDao.selectAllChannel(channelReduce);

        String isNVRRecord = params.get("NVR").toString();

        if ("NVR".equals(isNVRRecord)) {
            boolean stream = ObjectUtils.anyNotNull(params.get("stream"));
            boolean time = ObjectUtils.anyNotNull(params.get("time"));
            list.stream().filter(item -> {
                String streamId = item.get("stream").toString();
                String originalTime = item.get("time").toString();
                boolean isOK = !streamId.contains("_");
                if (isOK) {
                    if (stream && time) {
                        String paramStream = params.get("stream").toString();
                        String paramTime = params.get("time").toString();
                        return streamId.contains(paramStream) && originalTime.contains(paramTime);
                    } else if (stream) {
                        return streamId.contains(params.get("stream").toString());
                    } else if (time) {
                        return originalTime.contains(params.get("time").toString());
                    }
                    return true;
                }
                return false;
            }).forEach(resultList::add);
        } else {
            list.stream()
                    .filter(item -> {
                        String streamId = item.get("stream").toString();
                        return streamId.contains("_");
                    })
                    .forEach(item -> { //修改list内容
                        String streamId = item.get("stream").toString();
                        String[] streamIds = streamId.split("_");
                        String paramDeviceId = streamIds[0];
                        String paramChannelId = streamIds[1];
                        channelReduces.forEach(tempChannelReduce -> {
                            boolean isEq = tempChannelReduce.getDeviceId().equals(paramDeviceId) && tempChannelReduce.getChannelId().equals(paramChannelId);
                            if (isEq) {
                                item.put("manufacturer", tempChannelReduce.getManufacturer());
                                item.put("name", tempChannelReduce.getName());
                                item.put("channelId", tempChannelReduce.getChannelId());
                                item.put("deviceId", tempChannelReduce.getDeviceId());
                                item.put("hostAddress", tempChannelReduce.getHostAddress());
                                resultList.add(item);
                            }
                        });
                    });
        }
        MyPageInfo<Map<String, Object>> myPageInfo = new MyPageInfo<>(resultList);
        myPageInfo.startPage(pageNum, pageSize);
        return myPageInfo;
    }
}
