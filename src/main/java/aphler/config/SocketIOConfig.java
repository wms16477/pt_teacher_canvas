package aphler.config;

import com.corundumstudio.socketio.AckRequest;
// 使用全限定名避免与 Spring @Configuration 冲突
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.Transport;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.listener.ConnectListener;
import com.corundumstudio.socketio.listener.DataListener;
import com.corundumstudio.socketio.listener.DisconnectListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SocketIOConfig {

    private static final Logger log = LoggerFactory.getLogger(SocketIOConfig.class);
    @Value("${socketio.port:9092}")
    private int socketPort;


    @Bean
    public SocketIOServer socketIOServer() {
        com.corundumstudio.socketio.Configuration socketConfig = new com.corundumstudio.socketio.Configuration();
        socketConfig.setPort(socketPort);
        socketConfig.setOrigin("*");
        socketConfig.setHostname("0.0.0.0");
        socketConfig.setTransports(Transport.POLLING, Transport.WEBSOCKET);
        SocketIOServer server = new SocketIOServer(socketConfig);
        server.addConnectListener(new ConnectListener() {
            @Override
            public void onConnect(SocketIOClient client) {
                System.out.println("连接了");
                // 可根据需要进行认证
            }
        });
        server.addDisconnectListener(new DisconnectListener() {
            @Override
            public void onDisconnect(SocketIOClient client) {
            }
        });
        server.addEventListener("ping", String.class, new DataListener<String>() {
            @Override
            public void onData(SocketIOClient client, String data, AckRequest ackSender) throws Exception {
                client.sendEvent("pong", data);
            }
        });
        server.start();
        return server;
    }


    /**
     * 用于扫描 netty-socketio 注解 比如 @OnConnect、@OnEvent
     */
    @Bean
    public SpringAnnotationScanner springAnnotationScanner(SocketIOServer socketServer) {
        return new SpringAnnotationScanner(socketServer);
    }
}
