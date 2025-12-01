package aphler.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnConnect;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//SocketIOServer自定义事件（功能实现）
@Component
public class MessageEventHandler {


    private final SocketIOServer socketIoServer;

    private static final Map<String, UUID> onlineUsers = new ConcurrentHashMap<>();  //存储已登录的<用户id,该用户的sessionId>对应关系

    private static final Map<String, String> userIdNameMap = new ConcurrentHashMap<>();   //存储已登录的<用户id,昵称>。（仅为方便查询，可直接查询数据库得到值，但直接数据库读取开销大，尽量做成缓存）

    private static final HashSet<String> crowdedUser = new HashSet<>();//是否同时登录的用户

    public MessageEventHandler(SocketIOServer socketIoServer) {
        this.socketIoServer = socketIoServer;
    }


    //服务端监听事件。（客户端触发服务端事件）
    @OnConnect
    public void connect(SocketIOClient client) {
        System.out.println("连接了");
    }


    @OnDisconnect
    public void disconnect(SocketIOClient client) {
        System.out.println("断开连接了");

    }








    //服务端发送事件。（服务端触发客户端事件）
    //广播在线用户列表
    private void broadcastUserList(String eventName) {
        socketIoServer.getBroadcastOperations().sendEvent(eventName, userIdNameMap);
    }


    //将对象转为Map，并将对象的指定字段转为String类型
    public static Map transObject2MapAndFiled2String(Object obj, String... fileds) {
        ObjectMapper mapper = new ObjectMapper();
        Map map = null;
        try {
            String s = mapper.writeValueAsString(obj);
            map = mapper.readValue(s, Map.class);
            for (String filed : fileds) {
                if (map.get(filed) != null)
                    map.put(filed, String.valueOf(map.get(filed)));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return map;
    }

}
