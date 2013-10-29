package com.github.ambry;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.nio.channels.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import java.io.EOFException;
import java.util.*;

/**
 * An NIO socket server. The threading model is
 *   1 Acceptor thread that handles new connections
 *   N Processor threads that each have their own selector and read requests from sockets
 *   M Handler threads that handle requests and produce responses back to the processor threads for writing.
 */
public class SocketServer implements NetworkServer {

  private final String host;
  private final int port;
  private final int numProcessorThreads;
  private final int maxQueuedRequests;
  private final int sendBufferSize;
  private final int recvBufferSize;
  private final int maxRequestSize;
  private final ArrayList<Processor> processors;
  private volatile Acceptor acceptor;
  private final SocketRequestResponseChannel requestResponseChannel;
  private Logger logger = LoggerFactory.getLogger(getClass());

  public SocketServer(String host, int port, int numProcessorThreads, int maxQueuedRequests,
                      int sendBufferSize, int recvBufferSize, int maxRequestSize) {
    this.host = host;
    this.port = port;
    this.numProcessorThreads = numProcessorThreads;
    this.maxQueuedRequests = maxQueuedRequests;
    this.sendBufferSize = sendBufferSize;
    this.recvBufferSize = recvBufferSize;
    this.maxRequestSize = maxRequestSize;
    processors = new ArrayList<Processor>(numProcessorThreads);
    requestResponseChannel = new SocketRequestResponseChannel(numProcessorThreads, maxQueuedRequests);
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public int getNumProcessorThreads() {
    return numProcessorThreads;
  }

  public int getMaxQueuedRequests() {
    return maxQueuedRequests;
  }

  public int getSendBufferSize() {
    return sendBufferSize;
  }

  public int getRecvBufferSize() {
    return recvBufferSize;
  }

  public int getMaxRequestSize() {
    return maxRequestSize;
  }

  @Override
  public RequestResponseChannel getRequestResponseChannel() {
    return requestResponseChannel;
  }

  public void start() throws IOException, InterruptedException {
    logger.info("Starting " + numProcessorThreads + " processor threads");
    for(int i = 0; i < numProcessorThreads; i++) {
      processors.add(i, new Processor(i, maxRequestSize, requestResponseChannel));
      Utils.newThread("ambry-processor-" + port + " " + i, processors.get(i), false).start();
    }
    // TODO register the processor threads for notification of responses

    // start accepting connections
    logger.info("Starting acceptor thread");
    this.acceptor = new Acceptor(host, port, processors, sendBufferSize, recvBufferSize);
    Utils.newThread("ambry-acceptor", acceptor, false).start();
    acceptor.awaitStartup();
    logger.info("Started server");
  }

  public void shutdown() {
    try {
      logger.info("Shutting down server");
      if(acceptor != null)
        acceptor.shutdown();
      for (Processor processor : processors)
        processor.shutdown();
      logger.info("Shutdown completed");
    } catch (Exception e) {
      // log here
    }
  }
}


/**
 * A base class with some helper variables and methods
 */
abstract class AbstractServerThread implements Runnable {

  protected final Selector selector;
  private final CountDownLatch startupLatch;
  private final CountDownLatch shutdownLatch;
  private final AtomicBoolean alive;
  protected Logger logger = LoggerFactory.getLogger(getClass());

  public AbstractServerThread() throws IOException {
    selector = Selector.open();
    startupLatch = new CountDownLatch(1);
    shutdownLatch = new CountDownLatch(1);
    alive = new AtomicBoolean(false);
  }

  /**
   * Initiates a graceful shutdown by signaling to stop and waiting for the shutdown to complete
   */
  public void shutdown() throws InterruptedException {
    alive.set(false);
    selector.wakeup();
    shutdownLatch.await();
  }

  /**
   * Wait for the thread to completely start up
   */
  public void awaitStartup() throws InterruptedException {
    startupLatch.await();
  }

  /**
   * Record that the thread startup is complete
   */
  protected void startupComplete() {
    alive.set(true);
    startupLatch.countDown();
  }

  /**
   * Record that the thread shutdown is complete
   */
  protected void shutdownComplete() {
    shutdownLatch.countDown();
  }

  /**
   * Is the server still running?
   */
  protected boolean isRunning() {
    return alive.get();
  }

  /**
   * Wakeup the thread for selection.
   */
  public void wakeup() {
    selector.wakeup();
  }
}

/**
 * Thread that accepts and configures new connections. There is only need for one of these
 */
class Acceptor extends AbstractServerThread {
  private final String host;
  private final int port;
  private final ArrayList<Processor> processors;
  private final int sendBufferSize;
  private final int recvBufferSize;
  private final ServerSocketChannel serverChannel;
  protected Logger logger = LoggerFactory.getLogger(getClass());


  public Acceptor(String host, int port, ArrayList<Processor> processors, int sendBufferSize, int recvBufferSize) throws IOException {
    this.host = host;
    this.port = port;
    this.processors = processors;
    this.sendBufferSize = sendBufferSize;
    this.recvBufferSize = recvBufferSize;
    this.serverChannel = openServerSocket(this.host, this.port);
  }

  /**
   * Accept loop that checks for new connection attempts
   */
  public void run() {
    try {
      serverChannel.register(selector, SelectionKey.OP_ACCEPT);
      startupComplete();
      int currentProcessor = 0;
      while(isRunning()) {
        int ready = selector.select(500);
        if(ready > 0) {
          Set<SelectionKey> keys = selector.selectedKeys();
          Iterator<SelectionKey> iter = keys.iterator();
          while(iter.hasNext() && isRunning()) {
            SelectionKey key = null;
            try {
              key = iter.next();
              iter.remove();
              if(key.isAcceptable())
                accept(key, processors.get(currentProcessor));
              else
                throw new IllegalStateException("Unrecognized key state for acceptor thread.");

              // round robin to the next processor thread
              currentProcessor = (currentProcessor + 1) % processors.size();
            } catch (Exception e) {
                // throw
            }
          }
        }
      }
      logger.debug("Closing server socket and selector.");
      serverChannel.close();
      selector.close();
      shutdownComplete();
    } catch (Exception e) {
      // log
    }
  }

  /*
   * Create a server socket to listen for connections on.
   */
   private ServerSocketChannel openServerSocket(String host, int port) throws IOException {
     InetSocketAddress address = null;
     if(host == null || host.trim().isEmpty())
        address = new InetSocketAddress(port);
     else
        address = new InetSocketAddress(host, port);
      ServerSocketChannel serverChannel = ServerSocketChannel.open();
      serverChannel.configureBlocking(false);
      serverChannel.socket().bind(address);
      logger.info("Awaiting socket connections on %s:%d.".format(address.getHostName(), port));
      return serverChannel;
   }

  /*
   * Accept a new connection
   */
   private void accept(SelectionKey key, Processor processor) throws SocketException, IOException {
     ServerSocketChannel serverSocketChannel = (ServerSocketChannel)key.channel();
     serverSocketChannel.socket().setReceiveBufferSize(recvBufferSize);
     SocketChannel socketChannel = serverSocketChannel.accept();
     socketChannel.configureBlocking(false);
     socketChannel.socket().setTcpNoDelay(true);
     socketChannel.socket().setSendBufferSize(sendBufferSize);
     logger.debug("Accepted connection from " + socketChannel.socket().getInetAddress() +
                   " on " + socketChannel.socket().getLocalSocketAddress() +
                   ". sendBufferSize [actual|requested]: ["+ socketChannel.socket().getSendBufferSize() +
                   "|" + sendBufferSize + "] " + "recvBufferSize [actual|requested]: [" +
                   socketChannel.socket().getReceiveBufferSize() + "|"+ recvBufferSize + "]");
     processor.accept(socketChannel);
   }
}

/**
 * Thread that processes all requests from a single connection. There are N of these running in parallel
 * each of which has its own selectors
 */
class Processor extends AbstractServerThread {
  private final int maxRequestSize;
  private SocketRequestResponseChannel channel;
  private final int id;
  private final ConcurrentLinkedQueue<SocketChannel> newConnections = new ConcurrentLinkedQueue<SocketChannel>();

  Processor(int id, int maxRequestSize, RequestResponseChannel channel) throws IOException {
    this.maxRequestSize = maxRequestSize;
    this.channel = (SocketRequestResponseChannel)channel;
    this.id = id;
  }

  public void run() {
    try {
      startupComplete();
      while(isRunning()) {
        // setup any new connections that have been queued up
        configureNewConnections();
        // register any new responses for writing
        processNewResponses();
        long startSelectTime = SystemTime.getInstance().milliseconds();
        int ready = selector.select(300);
        logger.trace("Processor id " + id + " selection time = " + (SystemTime.getInstance().milliseconds() - startSelectTime) + " ms");

        if(ready > 0) {
          Set<SelectionKey> keys = selector.selectedKeys();
          Iterator<SelectionKey> iter = keys.iterator();
          while(iter.hasNext() && isRunning()) {
            SelectionKey key = null;
            try {
              key = iter.next();
              iter.remove();

              if(key.isReadable())
                read(key);
              else if(key.isWritable())
                write(key);
              else if(!key.isValid())
                close(key);
              else
                throw new IllegalStateException("Unrecognized key state for processor thread.");
            }
            catch (EOFException e) {
              close(key);
              // handle InvalidRequestException
            }
            catch (Throwable e) {
              close(key);
            }
          }
        }
      }
      logger.debug("Closing server socket and selector.");
      selector.close();
      shutdownComplete();
    } catch (Exception e) {
      logger.error("Error while shutting down server " + e);
    }
  }


  private void processNewResponses() throws InterruptedException, IOException {
    SocketServerResponse curr = (SocketServerResponse)channel.receiveResponse(id);
    while(curr != null) {
      SocketServerRequest request = (SocketServerRequest)curr.getRequest();
      SelectionKey key = (SelectionKey)request.getRequestKey();
      try {
        if(curr.getOutput() == null) {
          // a null response send object indicates that there is no response to send to the client.
          // In this case, we just want to turn the interest ops to READ to be able to read more pipelined requests
          // that are sitting in the server's socket buffer
          logger.trace("Socket server received response and there is nothing to send to the client ");
          // to trace
          key.interestOps(SelectionKey.OP_READ);
          key.attach(null);
        }
        else {
          logger.trace("Socket server received response to send, registering for write: " + curr);
          key.interestOps(SelectionKey.OP_WRITE);
          key.attach(curr);
        }
      }
      catch (CancelledKeyException e) {
        logger.debug("Ignoring response for closed socket.");
        close(key);
      }
      finally {
        curr = (SocketServerResponse)channel.receiveResponse(id);
      }
    }
  }

  /**
   * Queue up a new connection for reading
   */
  public void accept(SocketChannel socketChannel) {
    newConnections.add(socketChannel);
    wakeup();
  }


  private void close(SelectionKey key) throws IOException {
    SocketChannel channel = (SocketChannel)key.channel();
    logger.debug("Closing connection from " + channel.socket().getRemoteSocketAddress());
    channel.socket().close();
    channel.close();
    key.attach(null);
    key.cancel();
  }

  /**
   * Register any new connections that have been queued up
   */
  private void configureNewConnections() throws ClosedChannelException {
    while(newConnections.size() > 0) {
      SocketChannel channel = newConnections.poll();
      logger.debug("Processor " + id + " listening to new connection from " + channel.socket().getRemoteSocketAddress());
      channel.register(selector, SelectionKey.OP_READ);
    }
  }

  /*
   * Process reads from ready sockets
   */
  private void read(SelectionKey key) throws InterruptedException, IOException {
    SocketChannel socketChannel = (SocketChannel)key.channel();
    SocketServerInputSet input = null;
    if(key.attachment() == null) {
      input = new SocketServerInputSet();
      key.attach(input);
    }
    else {
      input = (SocketServerInputSet)key.attachment();
    }
    input.readFrom(socketChannel);

    SocketAddress address = socketChannel.socket().getRemoteSocketAddress();
    logger.trace("bytes read from " + address);

    if(input.readComplete()) {
      SocketServerRequest req =
              new SocketServerRequest(id, key, input);
      channel.sendRequest(req);
      key.attach(null);
      // explicitly reset interest ops to not READ, no need to wake up the selector just yet
      key.interestOps(key.interestOps() & (~SelectionKey.OP_READ));
    }
    else {
      // more reading to be done
      logger.trace("Did not finish reading, registering for read again on connection " +
              socketChannel.socket().getRemoteSocketAddress());
      key.interestOps(SelectionKey.OP_READ);
      wakeup();
    }
  }

    /*
     * Process writes to ready sockets
     */
  private void write(SelectionKey key) throws IOException {
    SocketChannel socketChannel = (SocketChannel)key.channel();
    SocketServerResponse response =
            (SocketServerResponse)key.attachment();
    Send responseSend = response.getOutput();
    if(responseSend == null)
      throw new IllegalStateException("Registered for write interest but no response attached to key.");
    responseSend.writeTo(socketChannel);
    logger.trace("Bytes written to " + socketChannel.socket().getRemoteSocketAddress() + " using key " + key);

    if(responseSend.isComplete()) {
      logger.trace("Finished writing, registering for read on connection " + socketChannel.socket().getRemoteSocketAddress());
      // update metrics
      key.attach(null);
      // log trace
      key.interestOps(SelectionKey.OP_READ);
    }
    else {
      logger.trace("Did not finish writing, registering for write again on connection " +
              socketChannel.socket().getRemoteSocketAddress());
      key.interestOps(SelectionKey.OP_WRITE);
      wakeup();
    }
  }
}
