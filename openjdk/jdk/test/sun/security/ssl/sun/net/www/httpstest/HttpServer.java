/*
 * Copyright (c) 2002, 2004, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import sun.net.www.MessageHeader;
import java.util.*;
import javax.net.ssl.*;
import javax.net.ssl.SSLEngineResult.*;
import java.security.*;

/**
 * This class implements a simple HTTPS server. It uses multiple threads to
 * handle connections in parallel, and will spin off a new thread to handle
 * each request. (this is easier to implement with SSLEngine)
 * <p>
 * It must be instantiated with a {@link HttpCallback} object to which
 * requests are given and must be handled.
 * <p>
 * Simple synchronization between the client(s) and server can be done
 * using the {@link #waitForCondition(String)}, {@link #setCondition(String)} and
 * {@link #rendezvous(String,int)} methods.
 *
 * NOTE NOTE NOTE NOTE NOTE NOTE NOTE
 *
 * If you make a change in here, please don't forget to make the
 * corresponding change in the J2SE equivalent.
 *
 * NOTE NOTE NOTE NOTE NOTE NOTE NOTE
 */

public class HttpServer {

    ServerSocketChannel schan;
    int threads;
    int cperthread;
    HttpCallback cb;
    Server[] servers;

    // ssl related fields
    static SSLContext sslCtx;

    /**
     * Create a <code>HttpServer<code> instance with the specified callback object
     * for handling requests. One thread is created to handle requests,
     * and up to ten TCP connections will be handled simultaneously.
     * @param cb the callback object which is invoked to handle each
     *  incoming request
     */

    public HttpServer (HttpCallback cb) throws IOException {
        this (cb, 1, 10, 0);
    }

    /**
     * Create a <code>HttpServer<code> instance with the specified number of
     * threads and maximum number of connections per thread. This functions
     * the same as the 4 arg constructor, where the port argument is set to zero.
     * @param cb the callback object which is invoked to handle each
     *     incoming request
     * @param threads the number of threads to create to handle requests
     *     in parallel
     * @param cperthread the number of simultaneous TCP connections to
     *     handle per thread
     */

    public HttpServer (HttpCallback cb, int threads, int cperthread)
        throws IOException {
        this (cb, threads, cperthread, 0);
    }

    /**
     * Create a <code>HttpServer<code> instance with the specified number
     * of threads and maximum number of connections per thread and running on
     * the specified port. The specified number of threads are created to
     * handle incoming requests, and each thread is allowed
     * to handle a number of simultaneous TCP connections.
     * @param cb the callback object which is invoked to handle
     *  each incoming request
     * @param threads the number of threads to create to handle
     *  requests in parallel
     * @param cperthread the number of simultaneous TCP connections
     *  to handle per thread
     * @param port the port number to bind the server to. <code>Zero</code>
     *  means choose any free port.
     */

    public HttpServer (HttpCallback cb, int threads, int cperthread, int port)
        throws IOException {
        schan = ServerSocketChannel.open ();
        InetSocketAddress addr = new InetSocketAddress (port);
        schan.socket().bind (addr);
        this.threads = threads;
        this.cb = cb;
        this.cperthread = cperthread;

        try {
            // create and initialize a SSLContext
            KeyStore ks = KeyStore.getInstance("JKS");
            KeyStore ts = KeyStore.getInstance("JKS");
            char[] passphrase = "passphrase".toCharArray();

            ks.load(new FileInputStream(System.getProperty("javax.net.ssl.keyStore")), passphrase);
            ts.load(new FileInputStream(System.getProperty("javax.net.ssl.trustStore")), passphrase);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, passphrase);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ts);

            sslCtx = SSLContext.getInstance("TLS");

            sslCtx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            servers = new Server [threads];
            for (int i=0; i<threads; i++) {
                servers[i] = new Server (cb, schan, cperthread);
                servers[i].start();
            }
        } catch (Exception ex) {
            throw new RuntimeException("test failed. cause: "+ex.getMessage());
        }
    }

    /** Tell all threads in the server to exit within 5 seconds.
     *  This is an abortive termination. Just prior to the thread exiting
     *  all channels in that thread waiting to be closed are forceably closed.
     */

    public void terminate () {
        for (int i=0; i<threads; i++) {
            servers[i].terminate ();
        }
    }

    /**
     * return the local port number to which the server is bound.
     * @return the local port number
     */

    public int getLocalPort () {
        return schan.socket().getLocalPort ();
    }

    static class Server extends Thread {

        ServerSocketChannel schan;
        Selector selector;
        SelectionKey listenerKey;
        SelectionKey key; /* the current key being processed */
        HttpCallback cb;
        ByteBuffer consumeBuffer;
        int maxconn;
        int nconn;
        ClosedChannelList clist;
        boolean shutdown;

        Server (HttpCallback cb, ServerSocketChannel schan, int maxconn) {
            this.schan = schan;
            this.maxconn = maxconn;
            this.cb = cb;
            nconn = 0;
            consumeBuffer = ByteBuffer.allocate (512);
            clist = new ClosedChannelList ();
            try {
                selector = Selector.open ();
                schan.configureBlocking (false);
                listenerKey = schan.register (selector, SelectionKey.OP_ACCEPT);
            } catch (IOException e) {
                System.err.println ("Server could not start: " + e);
            }
        }

        /* Stop the thread as soon as possible */
        public synchronized void terminate () {
            shutdown = true;
        }

        public void run ()  {
            try {
                while (true) {
                    selector.select (1000);
                    Set selected = selector.selectedKeys();
                    Iterator iter = selected.iterator();
                    while (iter.hasNext()) {
                        key = (SelectionKey)iter.next();
                        if (key.equals (listenerKey)) {
                            SocketChannel sock = schan.accept ();
                            if (sock == null) {
                                /* false notification */
                                iter.remove();
                                continue;
                            }
                            sock.configureBlocking (true);
                            SSLEngine sslEng = sslCtx.createSSLEngine();
                            sslEng.setUseClientMode(false);
                            new ServerWorker(cb, sock, sslEng).start();
                            nconn ++;
                            if (nconn == maxconn) {
                                /* deregister */
                                listenerKey.cancel ();
                                listenerKey = null;
                            }
                        } else {
                            if (key.isReadable()) {
                                boolean closed = false;
                                SocketChannel chan = (SocketChannel) key.channel();
                                if (key.attachment() != null) {
                                    closed = consume (chan);
                                }

                                if (closed) {
                                    chan.close ();
                                    key.cancel ();
                                    if (nconn == maxconn) {
                                        listenerKey = schan.register (selector, SelectionKey.OP_ACCEPT);
                                    }
                                    nconn --;
                                }
                            }
                        }
                        iter.remove();
                    }
                    clist.check();

                    synchronized (this) {
                        if (shutdown) {
                            clist.terminate ();
                            return;
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println ("Server exception: " + e);
                // TODO finish
            }
        }

        /* read all the data off the channel without looking at it
             * return true if connection closed
             */
        boolean consume (SocketChannel chan) {
            try {
                consumeBuffer.clear ();
                int c = chan.read (consumeBuffer);
                if (c == -1)
                    return true;
            } catch (IOException e) {
                return true;
            }
            return false;
        }
    }

    static class ServerWorker extends Thread {
        private ByteBuffer inNetBB;
        private ByteBuffer outNetBB;
        private ByteBuffer inAppBB;
        private ByteBuffer outAppBB;

        SSLEngine sslEng;
        SocketChannel schan;
        HttpCallback cb;
        HandshakeStatus currentHSStatus;
        boolean initialHSComplete;
        /*
         * All inbound data goes through this buffer.
         *
         * It might be nice to use a cache of ByteBuffers so we're
         * not alloc/dealloc'ing all over the place.
         */

        /*
         * Application buffers, also used for handshaking
         */
        private int appBBSize;

        ServerWorker (HttpCallback cb, SocketChannel schan, SSLEngine sslEng) {
            this.sslEng = sslEng;
            this.schan = schan;
            this.cb = cb;
            currentHSStatus = HandshakeStatus.NEED_UNWRAP;
            initialHSComplete = false;
            int netBBSize = sslEng.getSession().getPacketBufferSize();
            inNetBB =  ByteBuffer.allocate(netBBSize);
            outNetBB = ByteBuffer.allocate(netBBSize);
            appBBSize = sslEng.getSession().getApplicationBufferSize();
            inAppBB = ByteBuffer.allocate(appBBSize);
            outAppBB = ByteBuffer.allocate(appBBSize);
        }

        public SSLEngine getSSLEngine() {
            return sslEng;
        }

        public ByteBuffer outNetBB() {
            return outNetBB;
        }

        public ByteBuffer outAppBB() {
            return outAppBB;
        }

        public void run () {
            try {
                SSLEngineResult result;

                while (!initialHSComplete) {

                    switch (currentHSStatus) {

                    case NEED_UNWRAP:
                        int bytes = schan.read(inNetBB);

needIO:
                        while (currentHSStatus == HandshakeStatus.NEED_UNWRAP) {
                            /*
                             * Don't need to resize requestBB, since no app data should
                             * be generated here.
                             */
                            inNetBB.flip();
                            result = sslEng.unwrap(inNetBB, inAppBB);
                            inNetBB.compact();
                            currentHSStatus = result.getHandshakeStatus();

                            switch (result.getStatus()) {

                            case OK:
                                switch (currentHSStatus) {
                                case NOT_HANDSHAKING:
                                    throw new IOException(
                                                          "Not handshaking during initial handshake");

                                case NEED_TASK:
                                    Runnable task;
                                    while ((task = sslEng.getDelegatedTask()) != null) {
                                        task.run();
                                        currentHSStatus = sslEng.getHandshakeStatus();
                                    }
                                    break;
                                }

                                break;

                            case BUFFER_UNDERFLOW:
                                break needIO;

                            default: // BUFFER_OVERFLOW/CLOSED:
                                throw new IOException("Received" + result.getStatus() +
                                                      "during initial handshaking");
                            }
                        }

                        /*
                         * Just transitioned from read to write.
                         */
                        if (currentHSStatus != HandshakeStatus.NEED_WRAP) {
                            break;
                        }

                        // Fall through and fill the write buffer.

                    case NEED_WRAP:
                        /*
                         * The flush above guarantees the out buffer to be empty
                         */
                        outNetBB.clear();
                        result = sslEng.wrap(inAppBB, outNetBB);
                        outNetBB.flip();
                        schan.write (outNetBB);
                        outNetBB.compact();
                        currentHSStatus = result.getHandshakeStatus();

                        switch (result.getStatus()) {
                        case OK:

                            if (currentHSStatus == HandshakeStatus.NEED_TASK) {
                                Runnable task;
                                while ((task = sslEng.getDelegatedTask()) != null) {
                                    task.run();
                                    currentHSStatus = sslEng.getHandshakeStatus();
                                }
                            }

                            break;

                        default: // BUFFER_OVERFLOW/BUFFER_UNDERFLOW/CLOSED:
                            throw new IOException("Received" + result.getStatus() +
                                                  "during initial handshaking");
                        }
                        break;

                    case FINISHED:
                        initialHSComplete = true;
                        break;
                    default: // NOT_HANDSHAKING/NEED_TASK
                        throw new RuntimeException("Invalid Handshaking State" +
                                                   currentHSStatus);
                    } // switch
                }
                // read the application data; using non-blocking mode
                schan.configureBlocking(false);
                read(schan, sslEng);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        /* return true if the connection is closed, false otherwise */

        private boolean read (SocketChannel chan, SSLEngine sslEng) {
            HttpTransaction msg;
            boolean res;
            try {
                InputStream is = new BufferedInputStream (new NioInputStream (chan, sslEng, inNetBB, inAppBB));
                String requestline = readLine (is);
                MessageHeader mhead = new MessageHeader (is);
                String clen = mhead.findValue ("Content-Length");
                String trferenc = mhead.findValue ("Transfer-Encoding");
                String data = null;
                if (trferenc != null && trferenc.equals ("chunked"))
                    data = new String (readChunkedData (is));
                else if (clen != null)
                    data = new String (readNormalData (is, Integer.parseInt (clen)));
                String[] req = requestline.split (" ");
                if (req.length < 2) {
                    /* invalid request line */
                    return false;
                }
                String cmd = req[0];
                URI uri = null;
                try {
                    uri = new URI (req[1]);
                    msg = new HttpTransaction (this, cmd, uri, mhead, data, null, chan);
                    cb.request (msg);
                } catch (URISyntaxException e) {
                    System.err.println ("Invalid URI: " + e);
                    msg = new HttpTransaction (this, cmd, null, null, null, null, chan);
                    msg.sendResponse (501, "Whatever");
                }
                res = false;
            } catch (IOException e) {
                res = true;
            }
            return res;
        }

        byte[] readNormalData (InputStream is, int len) throws IOException {
            byte [] buf  = new byte [len];
            int c, off=0, remain=len;
            while (remain > 0 && ((c=is.read (buf, off, remain))>0)) {
                remain -= c;
                off += c;
            }
            return buf;
        }

        private void readCRLF(InputStream is) throws IOException {
            int cr = is.read();
            int lf = is.read();

            if (((cr & 0xff) != 0x0d) ||
                ((lf & 0xff) != 0x0a)) {
                throw new IOException(
                    "Expected <CR><LF>:  got '" + cr + "/" + lf + "'");
            }
        }

        byte[] readChunkedData (InputStream is) throws IOException {
            LinkedList l = new LinkedList ();
            int total = 0;
            for (int len=readChunkLen(is); len!=0; len=readChunkLen(is)) {
                l.add (readNormalData(is, len));
                total += len;
                readCRLF(is); // CRLF at end of chunk
            }
            readCRLF(is); // CRLF at end of Chunked Stream.
            byte[] buf = new byte [total];
            Iterator i = l.iterator();
            int x = 0;
            while (i.hasNext()) {
                byte[] b = (byte[])i.next();
                System.arraycopy (b, 0, buf, x, b.length);
                x += b.length;
            }
            return buf;
        }

        private int readChunkLen (InputStream is) throws IOException {
            int c, len=0;
            boolean done=false, readCR=false;
            while (!done) {
                c = is.read ();
                if (c == '\n' && readCR) {
                    done = true;
                } else {
                    if (c == '\r' && !readCR) {
                        readCR = true;
                    } else {
                        int x=0;
                        if (c >= 'a' && c <= 'f') {
                            x = c - 'a' + 10;
                        } else if (c >= 'A' && c <= 'F') {
                            x = c - 'A' + 10;
                        } else if (c >= '0' && c <= '9') {
                            x = c - '0';
                        }
                        len = len * 16 + x;
                    }
                }
            }
            return len;
        }

        private String readLine (InputStream is) throws IOException {
            boolean done=false, readCR=false;
            byte[] b = new byte [512];
            int c, l = 0;

            while (!done) {
                c = is.read ();
                if (c == '\n' && readCR) {
                    done = true;
                } else {
                    if (c == '\r' && !readCR) {
                        readCR = true;
                    } else {
                        b[l++] = (byte)c;
                    }
                }
            }
            return new String (b);
        }

        /** close the channel associated with the current key by:
         * 1. shutdownOutput (send a FIN)
         * 2. mark the key so that incoming data is to be consumed and discarded
         * 3. After a period, close the socket
         */

        synchronized void orderlyCloseChannel (SocketChannel ch) throws IOException {
            ch.socket().shutdownOutput();
        }

        synchronized void abortiveCloseChannel (SocketChannel ch) throws IOException {
            Socket s = ch.socket ();
            s.setSoLinger (true, 0);
            ch.close();
        }
    }


    /**
     * Implements blocking reading semantics on top of a non-blocking channel
     */

    static class NioInputStream extends InputStream {
        SSLEngine sslEng;
        SocketChannel channel;
        Selector selector;
        ByteBuffer inNetBB;
        ByteBuffer inAppBB;
        SelectionKey key;
        int available;
        byte[] one;
        boolean closed;
        ByteBuffer markBuf; /* reads may be satisifed from this buffer */
        boolean marked;
        boolean reset;
        int readlimit;

        public NioInputStream (SocketChannel chan, SSLEngine sslEng, ByteBuffer inNetBB, ByteBuffer inAppBB) throws IOException {
            this.sslEng = sslEng;
            this.channel = chan;
            selector = Selector.open();
            this.inNetBB = inNetBB;
            this.inAppBB = inAppBB;
            key = chan.register (selector, SelectionKey.OP_READ);
            available = 0;
            one = new byte[1];
            closed = marked = reset = false;
        }

        public synchronized int read (byte[] b) throws IOException {
            return read (b, 0, b.length);
        }

        public synchronized int read () throws IOException {
            return read (one, 0, 1);
        }

        public synchronized int read (byte[] b, int off, int srclen) throws IOException {

            int canreturn, willreturn;

            if (closed)
                return -1;

            if (reset) { /* satisfy from markBuf */
                canreturn = markBuf.remaining ();
                willreturn = canreturn>srclen ? srclen : canreturn;
                markBuf.get(b, off, willreturn);
                if (canreturn == willreturn) {
                    reset = false;
                }
            } else { /* satisfy from channel */
                canreturn = available();
                if (canreturn == 0) {
                    block ();
                    canreturn = available();
                }
                willreturn = canreturn>srclen ? srclen : canreturn;
                inAppBB.get(b, off, willreturn);
                available -= willreturn;

                if (marked) { /* copy into markBuf */
                    try {
                        markBuf.put (b, off, willreturn);
                    } catch (BufferOverflowException e) {
                        marked = false;
                    }
                }
            }
            return willreturn;
        }

        public synchronized int available () throws IOException {
            if (closed)
                throw new IOException ("Stream is closed");

            if (reset)
                return markBuf.remaining();

            if (available > 0)
                return available;

            inAppBB.clear ();
            int bytes = channel.read (inNetBB);

            int needed = sslEng.getSession().getApplicationBufferSize();
            if (needed > inAppBB.remaining()) {
                inAppBB = ByteBuffer.allocate(needed);
            }
            inNetBB.flip();
            SSLEngineResult result = sslEng.unwrap(inNetBB, inAppBB);
            inNetBB.compact();
            available = result.bytesProduced();

            if (available > 0)
                inAppBB.flip();
            else if (available == -1)
                throw new IOException ("Stream is closed");
            return available;
        }

        /**
         * block() only called when available==0 and buf is empty
         */
        private synchronized void block () throws IOException {
            //assert available == 0;
            int n = selector.select ();
            //assert n == 1;
            selector.selectedKeys().clear();
            available ();
        }

        public void close () throws IOException {
            if (closed)
                return;
            channel.close ();
            closed = true;
        }

        public synchronized void mark (int readlimit) {
            if (closed)
                return;
            this.readlimit = readlimit;
            markBuf = ByteBuffer.allocate (readlimit);
            marked = true;
            reset = false;
        }

        public synchronized void reset () throws IOException {
            if (closed )
                return;
            if (!marked)
                throw new IOException ("Stream not marked");
            marked = false;
            reset = true;
            markBuf.flip ();
        }
    }

    static class NioOutputStream extends OutputStream {
        SSLEngine sslEng;
        SocketChannel channel;
        ByteBuffer outNetBB;
        ByteBuffer outAppBB;
        SelectionKey key;
        Selector selector;
        boolean closed;
        byte[] one;

        public NioOutputStream (SocketChannel channel, SSLEngine sslEng, ByteBuffer outNetBB, ByteBuffer outAppBB) throws IOException {
            this.sslEng = sslEng;
            this.channel = channel;
            this.outNetBB = outNetBB;
            this.outAppBB = outAppBB;
            selector = Selector.open ();
            key = channel.register (selector, SelectionKey.OP_WRITE);
            closed = false;
            one = new byte [1];
        }

        public synchronized void write (int b) throws IOException {
            one[0] = (byte)b;
            write (one, 0, 1);
        }

        public synchronized void write (byte[] b) throws IOException {
            write (b, 0, b.length);
        }

        public synchronized void write (byte[] b, int off, int len) throws IOException {
            if (closed)
                throw new IOException ("stream is closed");

            outAppBB = ByteBuffer.allocate (len);
            outAppBB.put (b, off, len);
            outAppBB.flip ();
            int n;
            outNetBB.clear();
            int needed = sslEng.getSession().getPacketBufferSize();
            if (outNetBB.capacity() < needed) {
                outNetBB = ByteBuffer.allocate(needed);
            }
            SSLEngineResult ret = sslEng.wrap(outAppBB, outNetBB);
            outNetBB.flip();
            int newLen = ret.bytesProduced();
            while ((n = channel.write (outNetBB)) < newLen) {
                newLen -= n;
                if (newLen == 0)
                    return;
                selector.select ();
                selector.selectedKeys().clear ();
            }
        }

        public void close () throws IOException {
            if (closed)
                return;
            channel.close ();
            closed = true;
        }
    }

    /**
     * Utilities for synchronization. A condition is
     * identified by a string name, and is initialized
     * upon first use (ie. setCondition() or waitForCondition()). Threads
     * are blocked until some thread calls (or has called) setCondition() for the same
     * condition.
     * <P>
     * A rendezvous built on a condition is also provided for synchronizing
     * N threads.
     */

    private static HashMap conditions = new HashMap();

    /*
     * Modifiable boolean object
     */
    private static class BValue {
        boolean v;
    }

    /*
     * Modifiable int object
     */
    private static class IValue {
        int v;
        IValue (int i) {
            v =i;
        }
    }


    private static BValue getCond (String condition) {
        synchronized (conditions) {
            BValue cond = (BValue) conditions.get (condition);
            if (cond == null) {
                cond = new BValue();
                conditions.put (condition, cond);
            }
            return cond;
        }
    }

    /**
     * Set the condition to true. Any threads that are currently blocked
     * waiting on the condition, will be unblocked and allowed to continue.
     * Threads that subsequently call waitForCondition() will not block.
     * If the named condition did not exist prior to the call, then it is created
     * first.
     */

    public static void setCondition (String condition) {
        BValue cond = getCond (condition);
        synchronized (cond) {
            if (cond.v) {
                return;
            }
            cond.v = true;
            cond.notifyAll();
        }
    }

    /**
     * If the named condition does not exist, then it is created and initialized
     * to false. If the condition exists or has just been created and its value
     * is false, then the thread blocks until another thread sets the condition.
     * If the condition exists and is already set to true, then this call returns
     * immediately without blocking.
     */

    public static void waitForCondition (String condition) {
        BValue cond = getCond (condition);
        synchronized (cond) {
            if (!cond.v) {
                try {
                    cond.wait();
                } catch (InterruptedException e) {}
            }
        }
    }

    /* conditions must be locked when accessing this */
    static HashMap rv = new HashMap();

    /**
     * Force N threads to rendezvous (ie. wait for each other) before proceeding.
     * The first thread(s) to call are blocked until the last
     * thread makes the call. Then all threads continue.
     * <p>
     * All threads that call with the same condition name, must use the same value
     * for N (or the results may be not be as expected).
     * <P>
     * Obviously, if fewer than N threads make the rendezvous then the result
     * will be a hang.
     */

    public static void rendezvous (String condition, int N) {
        BValue cond;
        IValue iv;
        String name = "RV_"+condition;

        /* get the condition */

        synchronized (conditions) {
            cond = (BValue)conditions.get (name);
            if (cond == null) {
                /* we are first caller */
                if (N < 2) {
                    throw new RuntimeException ("rendezvous must be called with N >= 2");
                }
                cond = new BValue ();
                conditions.put (name, cond);
                iv = new IValue (N-1);
                rv.put (name, iv);
            } else {
                /* already initialised, just decrement the counter */
                iv = (IValue) rv.get (name);
                iv.v --;
            }
        }

        if (iv.v > 0) {
            waitForCondition (name);
        } else {
            setCondition (name);
            synchronized (conditions) {
                clearCondition (name);
                rv.remove (name);
            }
        }
    }

    /**
     * If the named condition exists and is set then remove it, so it can
     * be re-initialized and used again. If the condition does not exist, or
     * exists but is not set, then the call returns without doing anything.
     * Note, some higher level synchronization
     * may be needed between clear and the other operations.
     */

    public static void clearCondition(String condition) {
        BValue cond;
        synchronized (conditions) {
            cond = (BValue) conditions.get (condition);
            if (cond == null) {
                return;
            }
            synchronized (cond) {
                if (cond.v) {
                    conditions.remove (condition);
                }
            }
        }
    }
}
