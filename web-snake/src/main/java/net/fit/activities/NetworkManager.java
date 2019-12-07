package net.fit.activities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.fit.GameModel;
import net.fit.proto.SnakesProto;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
public class NetworkManager implements Runnable {
    @AllArgsConstructor
    private static class Message {
        private SnakesProto.GameMessage message;
        private SocketAddress address;
        private long sequence;
        private Date timestamp;
    }

    private long sequenceNum = 0;
    private BlockingQueue<Message> messageQueue = new LinkedBlockingQueue<>();
    @Getter private PingActivity pingActivity = new PingActivity();
    private final DatagramSocket socket;
    private final GameModel model;

    public synchronized long getSequenceNum() {
        return sequenceNum++;
    }

    public void commit(SnakesProto.GameMessage message, SocketAddress address) throws InterruptedException {
        messageQueue.put(new Message(message, address, 0, null));
    }

    public void confirm(long sequence) {
        messageQueue.removeIf(message -> message.sequence == sequence);
    }

    @Override
    public void run() {
        while (true) {
            DatagramPacket packet = new DatagramPacket(new byte[0], 0);
            try {
                Message nextMessage = messageQueue.take();
                nextMessage.sequence = getSequenceNum();
                nextMessage.timestamp = new Date();
                byte[] data = nextMessage.message.toBuilder().setMsgSeq(nextMessage.sequence).build().toByteArray();
                packet.setData(data);
                packet.setLength(data.length);
                packet.setSocketAddress(nextMessage.address);
                socket.send(packet);
                pingActivity.notifyMessage();
            } catch (InterruptedException | IOException e) {
                e.printStackTrace();
            }
        }
    }

    public class ResendActivity implements Runnable, Observer {
        private Map<SnakesProto.GamePlayer, BlockingQueue<Message>> pendingRequests = new HashMap<>();

        @Override
        public void run() {
            while (true) {
                SnakesProto.GameConfig config = model.getConfig();
                try {
                    Thread.sleep(config.getPingDelayMs());
                    //pendingRequests.values().forEach();
                } catch (InterruptedException e) {
                    System.err.println("Resend thread was interrupted...");
                }

            }
        }

        @Override
        public void update(Observable o, Object arg) {

        }
    }

    public class PingActivity extends VaryingActivity implements Runnable {
        private final AtomicBoolean lock = new AtomicBoolean(false);

        void notifyMessage() {
            synchronized (lock) {
                lock.set(true);
                lock.notify();
            }
        }

        @Override
        public void run() {
            SnakesProto.GameMessage.PingMsg.Builder pingBuilder = SnakesProto.GameMessage.PingMsg.newBuilder();
            SnakesProto.GameMessage.Builder msgBuilder = SnakesProto.GameMessage.newBuilder();
            msgBuilder.setPing(pingBuilder);

            while (true) {
                try {
                    synchronized (activityLock) {
                        while (!activityLock.get()) {
                            activityLock.wait();
                        }
                    }
                    synchronized (lock) {
                        lock.wait(model.getConfig().getPingDelayMs());
                        if (!lock.get()) {
                            commit(msgBuilder
                                    .setMsgSeq(NetworkManager.this.getSequenceNum())
                                    .build(), model.getHost());
                        }
                        lock.set(false);
                    }
                } catch (InterruptedException e) {
                    System.out.println("Ping thread interrupted");
                }
            }
        }
    }
}
