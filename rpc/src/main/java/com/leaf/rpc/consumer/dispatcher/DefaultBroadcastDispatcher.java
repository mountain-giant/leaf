package com.leaf.rpc.consumer.dispatcher;

import com.leaf.common.context.RpcContext;
import com.leaf.remoting.api.ProtocolHead;
import com.leaf.rpc.consumer.LeafClient;
import com.leaf.rpc.provider.process.RequestWrapper;
import com.leaf.remoting.api.channel.ChannelGroup;
import com.leaf.remoting.api.payload.RequestCommand;
import com.leaf.rpc.consumer.InvokeType;
import com.leaf.rpc.consumer.future.InvokeFuture;
import com.leaf.serialization.api.Serializer;
import com.leaf.serialization.api.SerializerType;

/**
 * 广播调用
 * @author yefei
 */
public class DefaultBroadcastDispatcher extends AbstractDispatcher {

    public DefaultBroadcastDispatcher(LeafClient leafClient, SerializerType serializerType) {
        super(leafClient, serializerType);
    }

    @Override
    public <T> InvokeFuture<T> dispatch(RequestWrapper request, Class<T> returnType, InvokeType invokeType) throws Throwable {
        final RequestWrapper requestWrapper = request;
        requestWrapper.setAttachment(RpcContext.getAttachments());

        Serializer serializer = getSerializer();

        byte[] bytes = serializer.serialize(requestWrapper);
        RequestCommand requestCommand = new RequestCommand(ProtocolHead.RPC_REQUEST, getSerializerCode(), bytes);

        ChannelGroup[] groups = groups(requestWrapper.getServiceMeta());
        InvokeFuture<T> invoke = invoke(requestCommand, DispatchType.BROADCAST, returnType, invokeType, groups);

        return invoke;
    }
}
