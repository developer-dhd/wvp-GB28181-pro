package com.genersoft.iot.vmp.vmanager.customApi.record;

import com.genersoft.iot.vmp.vmanager.customApi.common.MyPageInfo;

import java.util.Map;

public interface CloudRecordService {
    MyPageInfo<Map<String, Object>> resetRecords(Map<String, Object> params);
}
