package com.genersoft.iot.vmp.vmanager.customApi.record;

import com.genersoft.iot.vmp.vmanager.customApi.common.MyPageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cloudRecord")
public class CloudRecordController {

    @Autowired
    private CloudRecordService cloudRecordService;

    @PostMapping("/resetRecords")
    @ResponseBody
    public MyPageInfo<Map<String, Object>> resetRecords(@RequestBody Map<String, Object> params) {
        return cloudRecordService.resetRecords(params);
    }
}
