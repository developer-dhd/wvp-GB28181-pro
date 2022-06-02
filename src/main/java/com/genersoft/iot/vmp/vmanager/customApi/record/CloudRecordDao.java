package com.genersoft.iot.vmp.vmanager.customApi.record;

import com.genersoft.iot.vmp.vmanager.gb28181.platform.bean.ChannelReduce;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface CloudRecordDao {
    @Select(value = {"<script> SELECT dc.channelId, dc.deviceId, dc.name, de.manufacturer, de.hostAddress from device_channel dc " +
            "LEFT JOIN device de ON dc.deviceId = de.deviceId where 1=1" +
            "<if test=\"manufacturer != null and manufacturer!=''\"> AND de.manufacturer LIKE '%${manufacturer}%'</if>" +
            "<if test=\"name != null and name!=''\"> AND dc.name LIKE '%${name}%'</if>" +
            "<if test=\"deviceId != null and deviceId!=''\"> AND de.deviceId LIKE '%${deviceId}%'</if>" +
            "</script>"})
    List<ChannelReduce> selectAllChannel(ChannelReduce channelReduce);
}
