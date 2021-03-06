package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.alibaba.fastjson.JSONObject;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.event.SipSubscribe;
import com.genersoft.iot.vmp.gb28181.session.VideoStreamSessionManager;
import com.genersoft.iot.vmp.gb28181.transmit.SIPProcessorObserver;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.impl.SIPCommander;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.impl.SIPCommanderFroPlatform;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.ISIPRequestProcessor;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.SIPRequestProcessorParent;
import com.genersoft.iot.vmp.gb28181.utils.SipUtils;
import com.genersoft.iot.vmp.media.zlm.ZLMHttpHookSubscribe;
import com.genersoft.iot.vmp.media.zlm.ZLMMediaListManager;
import com.genersoft.iot.vmp.media.zlm.ZLMRTPServerFactory;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import com.genersoft.iot.vmp.service.IMediaServerService;
import com.genersoft.iot.vmp.service.IPlayService;
import com.genersoft.iot.vmp.service.bean.MessageForPushChannel;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.IVideoManagerStorage;
import com.genersoft.iot.vmp.utils.DateUtil;
import com.genersoft.iot.vmp.utils.SerializeUtils;
import gov.nist.javax.sdp.TimeDescriptionImpl;
import gov.nist.javax.sdp.fields.TimeField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sdp.*;
import javax.sip.*;
import javax.sip.address.SipURI;
import javax.sip.header.CallIdHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;
import java.time.Instant;
import java.util.Vector;

/**
 * SIP??????????????? INVITE??????
 */
@SuppressWarnings("rawtypes")
@Component
public class InviteRequestProcessor extends SIPRequestProcessorParent implements InitializingBean, ISIPRequestProcessor {

	private final static Logger logger = LoggerFactory.getLogger(InviteRequestProcessor.class);

	private String method = "INVITE";

	@Autowired
	private SIPCommanderFroPlatform cmderFroPlatform;

	@Autowired
	private IVideoManagerStorage storager;

	@Autowired
	private IRedisCatchStorage  redisCatchStorage;

	@Autowired
	private DynamicTask dynamicTask;

	@Autowired
	private SIPCommander cmder;

	@Autowired
	private IPlayService playService;

	@Autowired
	private ISIPCommander commander;

	@Autowired
	private ZLMRTPServerFactory zlmrtpServerFactory;

	@Autowired
	private IMediaServerService mediaServerService;

	@Autowired
	private SIPProcessorObserver sipProcessorObserver;

	@Autowired
	private VideoStreamSessionManager sessionManager;

	@Autowired
	private UserSetting userSetting;

	@Autowired
	private ZLMMediaListManager mediaListManager;


	@Override
	public void afterPropertiesSet() throws Exception {
		// ???????????????????????????
		sipProcessorObserver.addRequestProcessor(method, this);
	}

	/**
	 * ??????invite??????
	 * 
	 * @param evt
	 *            ????????????
	 */ 
	@Override
	public void process(RequestEvent evt) {
		//  Invite Request???????????????????????????????????????????????????????????????????????????????????????
		try {
			Request request = evt.getRequest();
			SipURI sipURI = (SipURI) request.getRequestURI();
			//???subject??????channelId,?????????request-line????????? ????????????request-line???????????????????????????????????????????????????
			//String channelId = sipURI.getUser();
			String channelId = SipUtils.getChannelIdFromHeader(request);
			String requesterId = SipUtils.getUserIdFromFromHeader(request);
			CallIdHeader callIdHeader = (CallIdHeader)request.getHeader(CallIdHeader.NAME);
			if (requesterId == null || channelId == null) {
				logger.info("?????????FromHeader???Address??????????????????id?????????400");
				responseAck(evt, Response.BAD_REQUEST); // ??????????????? ???400???????????????
				return;
			}

			// ????????????????????????????????????\??????
			ParentPlatform platform = storager.queryParentPlatByServerGBId(requesterId);
			if (platform == null) {
				inviteFromDeviceHandle(evt, requesterId);
			}else {
				// ?????????????????????????????????
				DeviceChannel channel = storager.queryChannelInParentPlatform(requesterId, channelId);
				GbStream gbStream = storager.queryStreamInParentPlatform(requesterId, channelId);
				PlatformCatalog catalog = storager.getCatalog(channelId);
				MediaServerItem mediaServerItem = null;
				// ??????????????????????????????
				if (channel != null && gbStream == null ) {
					if (channel.getStatus() == 0) {
						logger.info("?????????????????????400");
						responseAck(evt, Response.BAD_REQUEST, "channel [" + channel.getChannelId() + "] offline");
						return;
					}
					responseAck(evt, Response.CALL_IS_BEING_FORWARDED); // ??????????????????181??????????????????
				}else if(channel == null && gbStream != null){
					String mediaServerId = gbStream.getMediaServerId();
					mediaServerItem = mediaServerService.getOne(mediaServerId);
					if (mediaServerItem == null) {
						logger.info("[ app={}, stream={} ]?????????zlm {}?????????410",gbStream.getApp(), gbStream.getStream(), mediaServerId);
						responseAck(evt, Response.GONE);
						return;
					}
					responseAck(evt, Response.CALL_IS_BEING_FORWARDED); // ??????????????????181??????????????????
				}else if (catalog != null) {
					responseAck(evt, Response.BAD_REQUEST, "catalog channel can not play"); // ?????????????????????
					return;
				} else {
					logger.info("????????????????????????404");
					responseAck(evt, Response.NOT_FOUND); // ?????????????????????404??????????????????
					return;
				}
				// ??????sdp??????, ??????jainsip ?????????sdp????????????
				String contentString = new String(request.getRawContent());

				// jainSip?????????y=????????? ??????????????????
				int ssrcIndex = contentString.indexOf("y=");
				// ???????????????y??????
				String ssrcDefault = "0000000000";
				String ssrc;
				SessionDescription sdp;
				if (ssrcIndex >= 0) {
					//ssrc???????????????10???????????????????????????????????????????????????f=?????????
					ssrc = contentString.substring(ssrcIndex + 2, ssrcIndex + 12);
					String substring = contentString.substring(0, contentString.indexOf("y="));
					sdp = SdpFactory.getInstance().createSessionDescription(substring);
				}else {
					ssrc = ssrcDefault;
					sdp = SdpFactory.getInstance().createSessionDescription(contentString);
				}
				String sessionName = sdp.getSessionName().getValue();

				Long startTime = null;
				Long stopTime = null;
				Instant start = null;
				Instant end = null;
				if (sdp.getTimeDescriptions(false) != null && sdp.getTimeDescriptions(false).size() > 0) {
					TimeDescriptionImpl timeDescription = (TimeDescriptionImpl)(sdp.getTimeDescriptions(false).get(0));
					TimeField startTimeFiled = (TimeField)timeDescription.getTime();
					startTime = startTimeFiled.getStartTime();
					stopTime = startTimeFiled.getStopTime();

					start = Instant.ofEpochMilli(startTime*1000);
					end = Instant.ofEpochMilli(stopTime*1000);
				}
				//  ?????????????????????
				Vector mediaDescriptions = sdp.getMediaDescriptions(true);
				// ??????????????????PS ??????96
				//String ip = null;
				int port = -1;
				boolean mediaTransmissionTCP = false;
				Boolean tcpActive = null;
				for (Object description : mediaDescriptions) {
					MediaDescription mediaDescription = (MediaDescription) description;
					Media media = mediaDescription.getMedia();

					Vector mediaFormats = media.getMediaFormats(false);
					if (mediaFormats.contains("96")) {
						port = media.getMediaPort();
						//String mediaType = media.getMediaType();
						String protocol = media.getProtocol();

						// ??????TCP????????????udp??? ????????????udp
						if ("TCP/RTP/AVP".equals(protocol)) {
							String setup = mediaDescription.getAttribute("setup");
							if (setup != null) {
								mediaTransmissionTCP = true;
								if ("active".equals(setup)) {
									tcpActive = true;
									// ?????????tcp??????
									responseAck(evt, Response.NOT_IMPLEMENTED, "tcp active not support"); // ?????????????????????
									return;
								} else if ("passive".equals(setup)) {
									tcpActive = false;
								}
							}
						}
						break;
					}
				}
				if (port == -1) {
					logger.info("?????????????????????????????????415");
					// ????????????????????????
					responseAck(evt, Response.UNSUPPORTED_MEDIA_TYPE); // ????????????????????????415
					return;
				}
				String username = sdp.getOrigin().getUsername();
				String addressStr = sdp.getOrigin().getAddress();

				logger.info("[????????????]?????????{}??? ?????????{}:{}??? ssrc???{}", username, addressStr, port, ssrc);
				Device device  = null;
				// ?????? channel ??? gbStream ?????????null ???????????????????????????????????????
				if (channel != null) {
					device = storager.queryVideoDeviceByPlatformIdAndChannelId(requesterId, channelId);
					if (device == null) {
						logger.warn("????????????{}?????????{}????????????????????????", requesterId, channel);
						responseAck(evt, Response.SERVER_INTERNAL_ERROR);
						return;
					}
					mediaServerItem = playService.getNewMediaServerItem(device);
					if (mediaServerItem == null) {
						logger.warn("??????????????????zlm");
						responseAck(evt, Response.BUSY_HERE);
						return;
					}
					SendRtpItem sendRtpItem = zlmrtpServerFactory.createSendRtpItem(mediaServerItem, addressStr, port, ssrc, requesterId,
							device.getDeviceId(), channelId,
							mediaTransmissionTCP);
					if (tcpActive != null) {
						sendRtpItem.setTcpActive(tcpActive);
					}
					if (sendRtpItem == null) {
						logger.warn("???????????????????????????");
						responseAck(evt, Response.BUSY_HERE);
						return;
					}
					sendRtpItem.setCallId(callIdHeader.getCallId());
					sendRtpItem.setPlayType("Play".equals(sessionName)?InviteStreamType.PLAY:InviteStreamType.PLAYBACK);
					byte[] dialogByteArray = SerializeUtils.serialize(evt.getDialog());
					sendRtpItem.setDialog(dialogByteArray);
					byte[] transactionByteArray = SerializeUtils.serialize(evt.getServerTransaction());
					sendRtpItem.setTransaction(transactionByteArray);
					Long finalStartTime = startTime;
					Long finalStopTime = stopTime;
					ZLMHttpHookSubscribe.Event hookEvent = (mediaServerItemInUSe, responseJSON)->{
						String app = responseJSON.getString("app");
						String stream = responseJSON.getString("stream");
						logger.info("[????????????]??????????????????????????? ??????200OK(SDP)??? {}/{}", app, stream);
						//     * 0 ????????????????????????
						//     * 1 ?????????????????????????????????????????????ack
						//     * 2 ?????????
						sendRtpItem.setStatus(1);
						redisCatchStorage.updateSendRTPSever(sendRtpItem);

						StringBuffer content = new StringBuffer(200);
						content.append("v=0\r\n");
						content.append("o="+ channelId +" 0 0 IN IP4 "+mediaServerItemInUSe.getSdpIp()+"\r\n");
						content.append("s=" + sessionName+"\r\n");
						content.append("c=IN IP4 "+mediaServerItemInUSe.getSdpIp()+"\r\n");
						if ("Playback".equals(sessionName)) {
							content.append("t=" + finalStartTime + " " + finalStopTime + "\r\n");
						}else {
							content.append("t=0 0\r\n");
						}
						content.append("m=video "+ sendRtpItem.getLocalPort()+" RTP/AVP 96\r\n");
						content.append("a=sendonly\r\n");
						content.append("a=rtpmap:96 PS/90000\r\n");
						content.append("y="+ ssrc + "\r\n");
						content.append("f=\r\n");

						try {
							// ???????????????Ack????????????bye,?????????????????????10???
							dynamicTask.startDelay(callIdHeader.getCallId(), ()->{
								logger.info("Ack ????????????");
								mediaServerService.releaseSsrc(mediaServerItemInUSe.getId(), ssrc);
								// ??????bye
								cmderFroPlatform.streamByeCmd(platform, callIdHeader.getCallId());
							}, 60*1000);
							responseSdpAck(evt, content.toString(), platform);

						} catch (SipException e) {
							e.printStackTrace();
						} catch (InvalidArgumentException e) {
							e.printStackTrace();
						} catch (ParseException e) {
							e.printStackTrace();
						}
					};
					SipSubscribe.Event errorEvent = ((event) -> {
						// ????????????????????????????????????????????????
						Response response = null;
						try {
							response = getMessageFactory().createResponse(event.statusCode, evt.getRequest());
							ServerTransaction serverTransaction = getServerTransaction(evt);
							serverTransaction.sendResponse(response);
							if (serverTransaction.getDialog() != null) {
								serverTransaction.getDialog().delete();
							}
						} catch (ParseException | SipException | InvalidArgumentException e) {
							e.printStackTrace();
						}
					});
					sendRtpItem.setApp("rtp");
					if ("Playback".equals(sessionName)) {
						sendRtpItem.setPlayType(InviteStreamType.PLAYBACK);
						SSRCInfo ssrcInfo = mediaServerService.openRTPServer(mediaServerItem, null, true, true);
						sendRtpItem.setStreamId(ssrcInfo.getStream());
						// ??????redis??? ???????????????
						redisCatchStorage.updateSendRTPSever(sendRtpItem);
						playService.playBack(mediaServerItem, ssrcInfo, device.getDeviceId(), channelId, DateUtil.formatter.format(start),
								DateUtil.formatter.format(end), null, result -> {
								if (result.getCode() != 0){
									logger.warn("??????????????????");
									if (result.getEvent() != null) {
										errorEvent.response(result.getEvent());
									}
									redisCatchStorage.deleteSendRTPServer(platform.getServerGBId(), channelId, callIdHeader.getCallId(), null);
									try {
										responseAck(evt, Response.REQUEST_TIMEOUT);
									} catch (SipException e) {
										e.printStackTrace();
									} catch (InvalidArgumentException e) {
										e.printStackTrace();
									} catch (ParseException e) {
										e.printStackTrace();
									}
								}else {
									if (result.getMediaServerItem() != null) {
										hookEvent.response(result.getMediaServerItem(), result.getResponse());
									}
								}
							});
					}else {
						sendRtpItem.setPlayType(InviteStreamType.PLAY);
						SsrcTransaction playTransaction = sessionManager.getSsrcTransaction(device.getDeviceId(), channelId, "play", null);
						if (playTransaction != null) {
							Boolean streamReady = zlmrtpServerFactory.isStreamReady(mediaServerItem, "rtp", playTransaction.getStream());
							if (!streamReady) {
								playTransaction = null;
							}
						}
						if (playTransaction == null) {
							String streamId = null;
							if (mediaServerItem.isRtpEnable()) {
								streamId = String.format("%s_%s", device.getDeviceId(), channelId);
							}
							SSRCInfo ssrcInfo = mediaServerService.openRTPServer(mediaServerItem, streamId, true, false);
							sendRtpItem.setStreamId(ssrcInfo.getStream());
							// ??????redis??? ???????????????
							redisCatchStorage.updateSendRTPSever(sendRtpItem);
							playService.play(mediaServerItem, ssrcInfo, device, channelId, hookEvent, errorEvent, (code, msg)->{
								redisCatchStorage.deleteSendRTPServer(platform.getServerGBId(), channelId, callIdHeader.getCallId(), null);
							}, null);
						}else {
							sendRtpItem.setStreamId(playTransaction.getStream());
							// ??????redis??? ???????????????
							redisCatchStorage.updateSendRTPSever(sendRtpItem);
							JSONObject jsonObject = new JSONObject();
							jsonObject.put("app", sendRtpItem.getApp());
							jsonObject.put("stream", sendRtpItem.getStreamId());
							hookEvent.response(mediaServerItem, jsonObject);
						}
					}
				}else if (gbStream != null) {

					Boolean streamReady = zlmrtpServerFactory.isStreamReady(mediaServerItem, gbStream.getApp(), gbStream.getStream());
					if (!streamReady ) {
						if ("proxy".equals(gbStream.getStreamType())) {
							// TODO ??????????????????????????????
							logger.info("[ app={}, stream={} ]???????????????????????????????????????",gbStream.getApp(), gbStream.getStream());
							responseAck(evt, Response.BAD_REQUEST, "channel [" + gbStream.getGbId() + "] offline");
						}else if ("push".equals(gbStream.getStreamType())) {
							if (!platform.isStartOfflinePush()) {
								responseAck(evt, Response.TEMPORARILY_UNAVAILABLE, "channel unavailable");
								return;
							}
							// ??????redis????????????????????????
							logger.info("[ app={}, stream={} ]?????????????????????redis??????????????????????????????",gbStream.getApp(), gbStream.getStream());
							MessageForPushChannel messageForPushChannel = new MessageForPushChannel();
							messageForPushChannel.setType(1);
							messageForPushChannel.setGbId(gbStream.getGbId());
							messageForPushChannel.setApp(gbStream.getApp());
							messageForPushChannel.setStream(gbStream.getStream());
							// TODO ????????????????????????
							messageForPushChannel.setMediaServerId(gbStream.getMediaServerId());
							messageForPushChannel.setPlatFormId(platform.getServerGBId());
							messageForPushChannel.setPlatFormName(platform.getName());
							redisCatchStorage.sendStreamPushRequestedMsg(messageForPushChannel);
							// ????????????
							dynamicTask.startDelay(callIdHeader.getCallId(), ()->{
								logger.info("[ app={}, stream={} ] ??????????????????????????????", gbStream.getApp(), gbStream.getStream());
								try {
									mediaListManager.removedChannelOnlineEventLister(gbStream.getGbId());
									responseAck(evt, Response.REQUEST_TIMEOUT); // ??????
								} catch (SipException e) {
									e.printStackTrace();
								} catch (InvalidArgumentException e) {
									e.printStackTrace();
								} catch (ParseException e) {
									e.printStackTrace();
								}
							}, userSetting.getPlatformPlayTimeout());
							// ????????????
							MediaServerItem finalMediaServerItem = mediaServerItem;
							int finalPort = port;
							boolean finalMediaTransmissionTCP = mediaTransmissionTCP;
							Boolean finalTcpActive = tcpActive;
							mediaListManager.addChannelOnlineEventLister(gbStream.getGbId(), (app, stream)->{
								SendRtpItem sendRtpItem = zlmrtpServerFactory.createSendRtpItem(finalMediaServerItem, addressStr, finalPort, ssrc, requesterId,
										app, stream, channelId, finalMediaTransmissionTCP);

								if (sendRtpItem == null) {
									logger.warn("???????????????????????????");
									try {
										responseAck(evt, Response.BUSY_HERE);
									} catch (SipException e) {
										e.printStackTrace();
									} catch (InvalidArgumentException e) {
										e.printStackTrace();
									} catch (ParseException e) {
										e.printStackTrace();
									}
									return;
								}
								if (finalTcpActive != null) {
									sendRtpItem.setTcpActive(finalTcpActive);
								}
								sendRtpItem.setPlayType(InviteStreamType.PUSH);
								// ??????redis??? ???????????????
								sendRtpItem.setStatus(1);
								sendRtpItem.setCallId(callIdHeader.getCallId());
								byte[] dialogByteArray = SerializeUtils.serialize(evt.getDialog());
								sendRtpItem.setDialog(dialogByteArray);
								byte[] transactionByteArray = SerializeUtils.serialize(evt.getServerTransaction());
								sendRtpItem.setTransaction(transactionByteArray);
								redisCatchStorage.updateSendRTPSever(sendRtpItem);
								sendStreamAck(finalMediaServerItem, sendRtpItem, platform, evt);

							});
						}
					}else {
						SendRtpItem sendRtpItem = zlmrtpServerFactory.createSendRtpItem(mediaServerItem, addressStr, port, ssrc, requesterId,
								gbStream.getApp(), gbStream.getStream(), channelId,
								mediaTransmissionTCP);


						if (sendRtpItem == null) {
							logger.warn("???????????????????????????");
							responseAck(evt, Response.BUSY_HERE);
							return;
						}
						if (tcpActive != null) {
							sendRtpItem.setTcpActive(tcpActive);
						}
						sendRtpItem.setPlayType(InviteStreamType.PUSH);
						// ??????redis??? ???????????????
						sendRtpItem.setStatus(1);
						sendRtpItem.setCallId(callIdHeader.getCallId());
						byte[] dialogByteArray = SerializeUtils.serialize(evt.getDialog());
						sendRtpItem.setDialog(dialogByteArray);
						byte[] transactionByteArray = SerializeUtils.serialize(evt.getServerTransaction());
						sendRtpItem.setTransaction(transactionByteArray);
						redisCatchStorage.updateSendRTPSever(sendRtpItem);
						sendStreamAck(mediaServerItem, sendRtpItem, platform, evt);
					}


				}

			}

		} catch (SipException | InvalidArgumentException | ParseException e) {
			e.printStackTrace();
			logger.warn("sdp????????????");
			e.printStackTrace();
		} catch (SdpParseException e) {
			e.printStackTrace();
		} catch (SdpException e) {
			e.printStackTrace();
		}
	}

	public void sendStreamAck(MediaServerItem mediaServerItem, SendRtpItem sendRtpItem, ParentPlatform platform, RequestEvent evt){

		StringBuffer content = new StringBuffer(200);
		content.append("v=0\r\n");
		content.append("o="+ sendRtpItem.getChannelId() +" 0 0 IN IP4 "+ mediaServerItem.getSdpIp()+"\r\n");
		content.append("s=Play\r\n");
		content.append("c=IN IP4 "+mediaServerItem.getSdpIp()+"\r\n");
		content.append("t=0 0\r\n");
		content.append("m=video "+ sendRtpItem.getLocalPort()+" RTP/AVP 96\r\n");
		content.append("a=sendonly\r\n");
		content.append("a=rtpmap:96 PS/90000\r\n");
		if (sendRtpItem.isTcp()) {
			content.append("a=connection:new\r\n");
			if (!sendRtpItem.isTcpActive()) {
				content.append("a=setup:active\r\n");
			}else {
				content.append("a=setup:passive\r\n");
			}
		}
		content.append("y="+ sendRtpItem.getSsrc() + "\r\n");
		content.append("f=\r\n");

		try {
			responseSdpAck(evt, content.toString(), platform);
		} catch (SipException e) {
			e.printStackTrace();
		} catch (InvalidArgumentException e) {
			e.printStackTrace();
		} catch (ParseException e) {
			e.printStackTrace();
		}
	}

	public void inviteFromDeviceHandle(RequestEvent evt, String requesterId) throws InvalidArgumentException, ParseException, SipException, SdpException {

		// ??????????????????????????????????????????????????????????????????????????????????????????
		Device device = redisCatchStorage.getDevice(requesterId);
		Request request = evt.getRequest();
		if (device != null) {
			logger.info("????????????" + requesterId + "???????????????Invite??????");
			responseAck(evt, Response.TRYING);

			String contentString = new String(request.getRawContent());
			// jainSip?????????y=????????? ????????????????????????
			String substring = contentString;
			String ssrc = "0000000404";
			int ssrcIndex = contentString.indexOf("y=");
			if (ssrcIndex > 0) {
				substring = contentString.substring(0, ssrcIndex);
				ssrc = contentString.substring(ssrcIndex + 2, ssrcIndex + 12);
			}
			ssrcIndex = substring.indexOf("f=");
			if (ssrcIndex > 0) {
				substring = contentString.substring(0, ssrcIndex);
			}
			SessionDescription sdp = SdpFactory.getInstance().createSessionDescription(substring);

			//  ?????????????????????
			Vector mediaDescriptions = sdp.getMediaDescriptions(true);
			// ??????????????????PS ??????96
			int port = -1;
			//boolean recvonly = false;
			boolean mediaTransmissionTCP = false;
			Boolean tcpActive = null;
			for (int i = 0; i < mediaDescriptions.size(); i++) {
				MediaDescription mediaDescription = (MediaDescription)mediaDescriptions.get(i);
				Media media = mediaDescription.getMedia();

				Vector mediaFormats = media.getMediaFormats(false);
				if (mediaFormats.contains("8")) {
					port = media.getMediaPort();
					String protocol = media.getProtocol();
					// ??????TCP????????????udp??? ????????????udp
					if ("TCP/RTP/AVP".equals(protocol)) {
						String setup = mediaDescription.getAttribute("setup");
						if (setup != null) {
							mediaTransmissionTCP = true;
							if ("active".equals(setup)) {
								tcpActive = true;
							} else if ("passive".equals(setup)) {
								tcpActive = false;
							}
						}
					}
					break;
				}
			}
			if (port == -1) {
				logger.info("?????????????????????????????????415");
				// ????????????????????????
				responseAck(evt, Response.UNSUPPORTED_MEDIA_TYPE); // ????????????????????????415
				return;
			}
			String username = sdp.getOrigin().getUsername();
			String addressStr = sdp.getOrigin().getAddress();
			logger.info("??????{}???????????????????????????{}:{}???ssrc???{}", username, addressStr, port, ssrc);

		} else {
			logger.warn("??????????????????/???????????????");
			responseAck(evt, Response.BAD_REQUEST);
		}
	}
}
