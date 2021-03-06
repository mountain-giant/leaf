package com.leaf.register;

import com.leaf.common.UnresolvedAddress;
import com.leaf.common.concurrent.ConcurrentSet;
import com.leaf.common.constants.Constants;
import com.leaf.common.utils.AnyThrow;
import com.leaf.common.utils.Collections;
import com.leaf.register.api.AbstractRegisterService;
import com.leaf.register.api.model.Message;
import com.leaf.register.api.model.RegisterMeta;
import com.leaf.register.api.model.SubscribeMeta;
import com.leaf.remoting.api.*;
import com.leaf.remoting.api.future.ResponseFuture;
import com.leaf.remoting.api.payload.RequestCommand;
import com.leaf.remoting.api.payload.ResponseCommand;
import com.leaf.remoting.netty.NettyClient;
import com.leaf.remoting.netty.NettyClientConfig;
import com.leaf.serialization.api.Serializer;
import com.leaf.serialization.api.SerializerFactory;
import com.leaf.serialization.api.SerializerType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import io.netty.util.internal.SystemPropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.*;

import static com.leaf.remoting.api.ProtocolHead.ACK;
import static com.leaf.remoting.api.ProtocolHead.SUBSCRIBE_RECEIVE;

/**
 * 默认的注册中心客户端
 *
 * @author yefei
 */
public class DefaultRegisterClient {

    private static final Logger logger = LoggerFactory.getLogger(DefaultRegisterClient.class);

    private static final AttributeKey<ConcurrentSet<RegisterMeta>> REGISTER_KEY = AttributeKey.valueOf("register.key");

    private static final AttributeKey<ConcurrentSet<SubscribeMeta>> SUBSCRIBE_KEY = AttributeKey.valueOf("subscribe.key");
    private static final SerializerType serializerType;

    static {
        serializerType = SerializerType.parse(
                (byte) SystemPropertyUtil.getInt("serializer.serializerType", SerializerType.PROTO_STUFF.value()));
    }

    private final RemotingClient rpcClient;
    private final NettyClientConfig config = new NettyClientConfig();
    private final AbstractRegisterService registerService;
    private final ConcurrentHashMap<Long, ResendMessage> resendMessages = new ConcurrentHashMap<>();
    private final ScheduledExecutorService resendMessageTimer;
    private volatile UnresolvedAddress address;

    public DefaultRegisterClient(AbstractRegisterService registerService) {
        this.registerService = registerService;
        this.rpcClient = new NettyClient(config, new RegisterClientChannelEventProcess());
        this.rpcClient.start();

        this.resendMessageTimer = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("resend-message-timer");
                return thread;
            }
        });
    }

    public void connect(UnresolvedAddress unresolvedAddress) {
        try {
            this.address = unresolvedAddress;
            rpcClient.registerRequestProcess(new RegisterClientProcess(), Executors.newCachedThreadPool());
            rpcClient.connect(unresolvedAddress);

            this.resendMessageTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    for (ResendMessage resendMessage : resendMessages.values()) {
                        if (System.currentTimeMillis() - resendMessage.getTimestamp() > 100) {
                            try {
                                rpcClient.invokeAsync(
                                        unresolvedAddress,
                                        resendMessage.getRequestCommand(),
                                        config.getInvokeTimeoutMillis(),
                                        resendMessage.getRegisterInvokeCallback()
                                );
                            } catch (Exception e) {
                                logger.error("resend no ack message error! {}", e.getMessage(), e);
                            }
                        }
                    }
                }
            }, 1000, Constants.DEFAULT_RESNED_INTERVAL, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            AnyThrow.throwUnchecked(e);
        }
    }

    private void sendMessageToRegister(RequestCommand requestCommand) {
        RegisterInvokeCallback registerInvokeCallback = new RegisterInvokeCallback();
        try {
            // 如果 channel 还是 isActive == false , 发送失败异常，会重发消息
            rpcClient.invokeAsync(
                    address,
                    requestCommand,
                    config.getInvokeTimeoutMillis(),
                    registerInvokeCallback
            );
        } catch (Exception e) {
            logger.error("send message to register error! {}", e.getMessage(), e);
        } finally {
            // 悲观策略 默认失败注册失败，重新注册 直到收到成功ACK
            ResendMessage resendMessage = new ResendMessage(requestCommand, registerInvokeCallback);
            resendMessages.put(requestCommand.getInvokeId(), resendMessage);
        }
    }

    public void register(RegisterMeta registerMeta) {
        if (attachRegisterEvent(registerMeta, rpcClient.group(address).next())) {
            Serializer serializer = SerializerFactory.serializer(serializerType);
            RequestCommand requestCommand = RemotingCommandFactory.createRequestCommand(
                    ProtocolHead.REGISTER_SERVICE,
                    serializerType.value(),
                    serializer.serialize(registerMeta));

            sendMessageToRegister(requestCommand);
        }
    }

    public void unRegister(RegisterMeta registerMeta) {
        if (attachCancelRegisterEvent(registerMeta, rpcClient.group(address).next())) {
            Serializer serializer = SerializerFactory.serializer(serializerType);
            RequestCommand requestCommand = RemotingCommandFactory.createRequestCommand(
                    ProtocolHead.CANCEL_REGISTER_SERVICE,
                    serializerType.value(),
                    serializer.serialize(registerMeta));
            sendMessageToRegister(requestCommand);
        }
    }


    public void subscribe(SubscribeMeta subscribeMeta) {
        if (attachSubscribeEvent(subscribeMeta, rpcClient.group(address).next())) {
            Serializer serializer = SerializerFactory.serializer(serializerType);
            RequestCommand requestCommand = RemotingCommandFactory.createRequestCommand(
                    ProtocolHead.SUBSCRIBE_SERVICE,
                    serializerType.value(),
                    serializer.serialize(subscribeMeta));
            sendMessageToRegister(requestCommand);
        }
    }

    // channel 附着注册的服务，忽略重复注册
    private boolean attachRegisterEvent(RegisterMeta registerMeta, Channel channel) {
        ConcurrentSet<RegisterMeta> registerMetas = channel.attr(REGISTER_KEY).get();
        if (registerMetas == null) {
            ConcurrentSet<RegisterMeta> newRegisterMetas = new ConcurrentSet<>();
            registerMetas = channel.attr(REGISTER_KEY).setIfAbsent(newRegisterMetas);
            if (registerMetas == null) {
                registerMetas = newRegisterMetas;
            }
        }
        return registerMetas.add(registerMeta);
    }

    private boolean attachCancelRegisterEvent(RegisterMeta registerMeta, Channel channel) {
        ConcurrentSet<RegisterMeta> registerMetas = channel.attr(REGISTER_KEY).get();
        if (registerMetas == null) {
            return false;
        }
        return registerMetas.remove(registerMeta);
    }

    // channel 附着订阅的服务，忽略重复订阅
    private boolean attachSubscribeEvent(SubscribeMeta subscribeMeta, Channel channel) {
        ConcurrentSet<SubscribeMeta> subscribeMetas = channel.attr(SUBSCRIBE_KEY).get();
        if (subscribeMetas == null) {
            ConcurrentSet<SubscribeMeta> newSubscribeMetas = new ConcurrentSet<>();
            subscribeMetas = channel.attr(SUBSCRIBE_KEY).setIfAbsent(newSubscribeMetas);
            if (subscribeMetas == null) {
                subscribeMetas = newSubscribeMetas;
            }
        }
        return subscribeMetas.add(subscribeMeta);
    }

    class RegisterClientChannelEventProcess extends ChannelEventAdapter {

        @Override
        public void onChannelActive(String remoteAddr, Channel channel) {
            // 重新连接 重新发布 订阅服务
            List<RegisterMeta> providers = registerService.getRegisterMetas();
            if (Collections.isNotEmpty(providers)) {
                for (RegisterMeta registerMeta : providers) {
                    register(registerMeta);
                }
            }
            List<SubscribeMeta> consumers = registerService.getServiceMetas();
            if (Collections.isEmpty(consumers)) {
                for (SubscribeMeta subscribeMeta : consumers) {
                    subscribe(subscribeMeta);
                }
            }
        }
    }

    class RegisterClientProcess implements RequestCommandProcessor {

        @Override
        public ResponseCommand process(ChannelHandlerContext context, RequestCommand request) {
            Serializer serializer = SerializerFactory.serializer(SerializerType.parse(request.getSerializerCode()));
            Message messageData = serializer.deserialize(request.getBody(), Message.class);
            switch (request.getMessageCode()) {
                case ProtocolHead.SUBSCRIBE_SERVICE: {
                    registerService.notify(messageData.getEvent(), messageData.getRegisterMetas());
                    break;
                }
                case ProtocolHead.OFFLINE_SERVICE: {
                    UnresolvedAddress address = messageData.getAddress();
                    registerService.offline(address);
                    break;
                }
                default:
                    throw new UnsupportedOperationException("RegisterClientProcess Unsupported MessageCode: " + request.getMessageCode());
            }
            ResponseCommand responseCommand = RemotingCommandFactory.createResponseCommand(
                    ProtocolHead.ACK,
                    serializerType.value(),
                    null,
                    request.getInvokeId()
            );
            return responseCommand;
        }

        @Override
        public ResponseCommand process(ChannelHandlerContext context, RequestCommand request, Throwable e) {
            return null;
        }
    }

    class RegisterInvokeCallback implements InvokeCallback<ResponseCommand> {

        public RegisterInvokeCallback() {
        }

        @Override
        public void operationComplete(ResponseFuture<ResponseCommand> responseFuture) {
            Serializer serializer = SerializerFactory.serializer(serializerType);
            ResponseCommand responseCommand = responseFuture.result();
            if (responseCommand == null) {
                // 通常是客户端异常 或 等待服务端超时
                Throwable cause = responseFuture.cause();
                if (cause != null) {
                    logger.error(cause.getMessage(), cause);
                } else {
                    logger.warn("Not only not received any message from provider, but cause is null!");
                }
            } else {
                // 收到ack确认，删除重发消息
                if (responseCommand.getMessageCode() == ACK) {
                    resendMessages.remove(responseCommand.getInvokeId());
                }
                if (responseCommand.getMessageCode() == SUBSCRIBE_RECEIVE) {
                    if (responseCommand.getStatus() == ResponseStatus.SUCCESS.value()) {
                        resendMessages.remove(responseCommand.getInvokeId());

                        Message messageData = serializer.deserialize(responseCommand.getBody(), Message.class);
                        registerService.notify(messageData.getEvent(), messageData.getRegisterMetas());
                    } else {
                        logger.warn("[SUBSCRIBE] receive register message, but response status: {}",
                                responseCommand.getMessageCode());
                    }
                }
            }
        }
    }

    class ResendMessage {

        private RequestCommand requestCommand;

        private InvokeCallback<ResponseCommand> registerInvokeCallback;

        private long timestamp;

        public ResendMessage(RequestCommand requestCommand, InvokeCallback<ResponseCommand> registerInvokeCallback) {
            this.requestCommand = requestCommand;
            this.registerInvokeCallback = registerInvokeCallback;
            this.timestamp = System.currentTimeMillis();
        }

        public RequestCommand getRequestCommand() {
            return requestCommand;
        }

        public InvokeCallback<ResponseCommand> getRegisterInvokeCallback() {
            return registerInvokeCallback;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }


    public void shutdownGracefully() {
        if (resendMessageTimer != null) {
            resendMessageTimer.shutdown();
        }
    }
}
