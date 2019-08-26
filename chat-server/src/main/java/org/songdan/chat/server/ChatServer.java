package org.songdan.chat.server;

import lombok.extern.slf4j.Slf4j;
import org.songdan.chat.common.domain.Message;
import org.songdan.chat.common.util.ProtoStuffUtil;
import org.songdan.chat.server.exception.handler.InterruptedExceptionHandler;
import org.songdan.chat.server.handler.message.MessageHandler;
import org.songdan.chat.server.util.SpringContextUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 先做简单的登录功能
 * Slf4j可以在打印的字符串中添加占位符，以避免字符串的拼接
 */
@Slf4j
public class ChatServer {
    public static final int DEFAULT_BUFFER_SIZE = 1024;
    public static final int PORT = 9000;
    public static final String QUIT = "QUIT";
    private AtomicInteger onlineUsers;

    private ServerSocketChannel serverSocketChannel;
    private Selector selector;

    private ExecutorService readPool;

    private ListenerThread listenerThread;
    private InterruptedExceptionHandler exceptionHandler;

    public ChatServer() {
        log.info("服务器启动");
        initServer();
    }

    private void initServer() {
        try {
            serverSocketChannel = ServerSocketChannel.open();
            //切换为非阻塞模式
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.bind(new InetSocketAddress(PORT));
            //获得选择器
            selector = Selector.open();
            //将channel注册到selector上
            //第二个参数是选择键，用于说明selector监控channel的状态
            //可能的取值：SelectionKey.OP_READ OP_WRITE OP_CONNECT OP_ACCEPT
            //监控的是channel的接收状态
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            this.readPool = new ThreadPoolExecutor(5, 10, 1000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10), new ThreadPoolExecutor.CallerRunsPolicy());
            this.listenerThread = new ListenerThread();
            this.onlineUsers = new AtomicInteger(0);
            this.exceptionHandler = SpringContextUtil.getBean("interruptedExceptionHandler");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 启动方法，线程最好不要在构造函数中启动，应该作为一个单独方法，或者使用工厂方法来创建实例
     * 避免构造未完成就使用成员变量
     */
    public void launch() {
        new Thread(listenerThread).start();
    }

    /**
     * 推荐的结束线程的方式是使用中断
     * 在while循环开始处检查是否中断，并提供一个方法来将自己中断
     * 不要在外部将线程中断
     * <p>
     * 另外，如果要中断一个阻塞在某个地方的线程，最好是继承自Thread，先关闭所依赖的资源，再关闭当前线程
     */
    private class ListenerThread extends Thread {

        @Override
        public void interrupt() {
            try {
                try {
                    selector.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } finally {
                super.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                //如果有一个及以上的客户端的数据准备就绪
                while (!Thread.currentThread().isInterrupted()) {
                    //当注册的事件到达时，方法返回；否则,该方法会一直阻塞  
                    selector.select();
                    Set<SelectionKey> selectionKeys = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = selectionKeys.iterator();
                    while (iterator.hasNext()) {
                        SelectionKey selectionKey = iterator.next();
                        //判断是Accept还是READ
                        if (selectionKey.isAcceptable()) {
                            //处理连接事件
                            handleAcceptRequest(selectionKey);
                        } else if (selectionKey.isReadable()) {
                            //处理READ事件
                            handleReadRequest(selectionKey);
                        }

                        iterator.remove();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void shutdown() {
            Thread.currentThread().interrupt();
        }
    }

    private void handleReadRequest(SelectionKey key) {
        key.interestOps(key.interestOps() & (~SelectionKey.OP_READ));
        readPool.execute(new ReadEventHandler(key));
    }

    /**
     * 关闭服务器
     */
    public void shutdownServer() {
        try {
            listenerThread.shutdown();
            readPool.shutdown();
            serverSocketChannel.close();
            System.exit(0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 处理客户端的连接请求
     *
     * @param selectionKey
     */
    private void handleAcceptRequest(SelectionKey selectionKey) {
        try {
            ServerSocketChannel serverSocketChannel = (ServerSocketChannel) selectionKey.channel();
            SocketChannel clientChannel = serverSocketChannel.accept();
            clientChannel.configureBlocking(false);
            //将clientChannel注册到selector上面，监听读事件
            clientChannel.register(selector, SelectionKey.OP_READ);
            log.info("服务器连接客户端:{}", clientChannel);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 处于线程池中的线程会随着线程池的shutdown方法而关闭
     */
    private class ReadEventHandler implements Runnable {

        private ByteBuffer buf;
        private SocketChannel client;
        private ByteArrayOutputStream baos;
        private SelectionKey key;

        public ReadEventHandler(SelectionKey key) {
            this.key = key;
            this.client = (SocketChannel) key.channel();
            this.buf = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
            this.baos = new ByteArrayOutputStream();
        }

        @Override
        public void run() {
            try {
                int size;
                while ((size = client.read(buf)) >0) {
                    buf.flip();
                    baos.write(buf.array(), 0, size);
                    buf.clear();
                }
                if (size == -1) {
                    return;
                }
                log.info("读取完毕，继续监听");
                //继续监听读取事件
                key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                //TODO 这个地方为什么要再次wakeup，不应该是等到事件来的时候吗？，这行代码注释以后只能接收一次消息
                key.selector().wakeup();
                byte[] bytes = baos.toByteArray();
                baos.close();
                Message message = ProtoStuffUtil.deserialize(bytes, Message.class);
                MessageHandler messageHandler = SpringContextUtil.getBean("MessageHandler", message.getHeader().getType().toString().toLowerCase());
                try {
                    messageHandler.handle(message, selector, key, onlineUsers);
                } catch (InterruptedException e) {
                    log.error("服务器线程被中断");
                    exceptionHandler.handle(client, message);
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    public static void main(String[] args) {
        System.out.println("Initialing...");
        ChatServer chatServer = new ChatServer();
        chatServer.launch();
        Scanner scanner = new Scanner(System.in, "UTF-8");
        while (scanner.hasNext()) {
            String next = scanner.next();
            if (next.equalsIgnoreCase(QUIT)) {
                System.out.println("服务器准备关闭");
                chatServer.shutdownServer();
                System.out.println("服务器已关闭");
            }
        }
    }
}
