/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.com;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.neo4j.helpers.Exceptions;
import org.neo4j.helpers.Pair;
import org.neo4j.helpers.Triplet;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.kernel.impl.nioneo.store.StoreId;
import org.neo4j.kernel.impl.util.StringLogger;

/**
 * Sits on the master side, receiving serialized requests from slaves (via
 * {@link Client}). Delegates actual work to an instance of a specified communication
 * interface, injected in the constructor. 
 */
public abstract class Server<M, R> extends Protocol implements ChannelPipelineFactory
{
    public static final int DEFAULT_BACKUP_PORT = 6362;

    // It's ok if there are more transactions, since these worker threads doesn't
    // do any actual work themselves, but spawn off other worker threads doing the
    // actual work. So this is more like a core Netty I/O pool worker size.
    protected final static int DEFAULT_MAX_NUMBER_OF_CONCURRENT_TRANSACTIONS = 200;

    private final ChannelFactory channelFactory;
    private final ServerBootstrap bootstrap;
    private final M realMaster;
    private final ChannelGroup channelGroup;
    private final Map<Channel, Pair<SlaveContext, AtomicLong /*time last heard of*/>> connectedSlaveChannels =
            new HashMap<Channel, Pair<SlaveContext,AtomicLong>>();
    private final Map<Channel, Pair<ChannelBuffer, ByteBuffer>> channelBuffers =
            new HashMap<Channel, Pair<ChannelBuffer,ByteBuffer>>();
    private final ExecutorService executor;
    private final ExecutorService masterCallExecutor;
    private final StringLogger msgLog;
    private final Map<Channel, PartialRequest> partialRequests =
            Collections.synchronizedMap( new HashMap<Channel, PartialRequest>() );
    private final int frameLength;
    private volatile boolean shuttingDown;
    
    // Executor for channels that we know should be finished, but can't due to being
    // active at the moment.
    private final ExecutorService unfinishedTransactionExecutor;
    
    // This is because there's a bug in Netty causing some channelClosed/channelDisconnected
    // events to not be sent. This is merely a safety net to catch the remained of the closed
    // channels that netty doesn't tell us about.
    private final ScheduledExecutorService silentChannelExecutor;
    
    public Server( M realMaster, final int port, String storeDir, int frameLength )
    {
        this( realMaster, port, storeDir, frameLength, DEFAULT_MAX_NUMBER_OF_CONCURRENT_TRANSACTIONS );
    }
    
    public Server( M realMaster, final int port, String storeDir, int frameLength,
            int maxNumberOfConcurrentTransactions )
    {
        this.realMaster = realMaster;
        this.frameLength = frameLength;
        this.msgLog = StringLogger.getLogger( storeDir );
        executor = Executors.newCachedThreadPool();
        masterCallExecutor = Executors.newCachedThreadPool();
        unfinishedTransactionExecutor = Executors.newScheduledThreadPool( 2 );
        channelFactory = new NioServerSocketChannelFactory(
                executor, executor, maxNumberOfConcurrentTransactions );
        silentChannelExecutor = Executors.newSingleThreadScheduledExecutor();
        silentChannelExecutor.scheduleWithFixedDelay( silentChannelFinisher(), 5, 5, TimeUnit.SECONDS );
        bootstrap = new ServerBootstrap( channelFactory );
        bootstrap.setPipelineFactory( this );
        
        Channel channel;
        try
        {
            channel = bootstrap.bind( new InetSocketAddress( port ) );
        }
        catch ( ChannelException e )
        {
            msgLog.logMessage( "Failed to bind master server to port " + port, e );
            executor.shutdown();
            throw e;
        }
        channelGroup = new DefaultChannelGroup();
        channelGroup.add( channel );
        msgLog.logMessage( getClass().getSimpleName() + " communication server started and bound to " + port, true );
    }

    private Runnable silentChannelFinisher()
    {
        // This poller is here because sometimes Netty doesn't tell us when channels are
        // closed or disconnected. Most of the time it does, but this acts as a safety
        // net for those we don't get notifications for. When the bug is fixed remove this.
        return new Runnable()
        {
            @Override
            public void run()
            {
                Map<Channel, Boolean/*starting to get old?*/> channels = new HashMap<Channel, Boolean>();
                synchronized ( connectedSlaveChannels )
                {
                    for ( Map.Entry<Channel, Pair<SlaveContext, AtomicLong>> channel : connectedSlaveChannels.entrySet() )
                    {   // Has this channel been silent for a while?
                        long age = System.currentTimeMillis()-channel.getValue().other().get();
                        if ( age > 30*1000 )
                        {
                            msgLog.logMessage( "Found a silent channel " + channel + ", " + age );
                            channels.put( channel.getKey(), Boolean.TRUE );
                        }
                        else if ( age > 5*1000 )
                        {   // Then add it to a list to check
                            channels.put( channel.getKey(), Boolean.FALSE );
                        }
                    }
                }
                for ( Map.Entry<Channel, Boolean> channel : channels.entrySet() )
                {
                    if ( channel.getValue() || !channel.getKey().isOpen() || !channel.getKey().isConnected() || !channel.getKey().isBound() )
                    {
                        tryToFinishOffChannel( channel.getKey() );
                    }
                }
            }
        };
    }

    public ChannelPipeline getPipeline() throws Exception
    {
        ChannelPipeline pipeline = Channels.pipeline();
        addLengthFieldPipes( pipeline, frameLength );
        pipeline.addLast( "serverHandler", new ServerHandler() );
        return pipeline;
    }

    private class ServerHandler extends SimpleChannelHandler
    {
        @Override
        public void messageReceived( ChannelHandlerContext ctx, MessageEvent event )
                throws Exception
        {
            try
            {
                ChannelBuffer message = (ChannelBuffer) event.getMessage();
                handleRequest( message, event.getChannel() );
            }
            catch ( Throwable e )
            {
                ctx.getChannel().close();
                tryToFinishOffChannel( ctx.getChannel() );
                e.printStackTrace();
                if ( e instanceof Exception )
                {
                    throw (Exception) e;
                }
                throw new RuntimeException( e );
            }
        }

        @Override
        public void channelClosed( ChannelHandlerContext ctx, ChannelStateEvent e )
                throws Exception
        {
            if ( !ctx.getChannel().isOpen() )
            {
                tryToFinishOffChannel( ctx.getChannel() );
            }
        }

        @Override
        public void channelDisconnected( ChannelHandlerContext ctx, ChannelStateEvent e )
                throws Exception
        {
            if ( !ctx.getChannel().isConnected() )
            {
                tryToFinishOffChannel( ctx.getChannel() );
            }
        }

        @Override
        public void exceptionCaught( ChannelHandlerContext ctx, ExceptionEvent e ) throws Exception
        {
            ctx.getChannel().close();
            e.getCause().printStackTrace();
        }
    }
    
    protected void tryToFinishOffChannel( Channel channel )
    {
        Pair<SlaveContext, AtomicLong> slave = null;
        synchronized ( connectedSlaveChannels )
        {
            slave = connectedSlaveChannels.remove( channel );
        }
        if ( slave == null )
        {
            return;
        }
        tryToFinishOffChannel( channel, slave.first() );
    }

    protected void tryToFinishOffChannel( Channel channel, SlaveContext slave )
    {
        try
        {
            finishOffChannel( channel, slave );
            unmapSlave( channel, slave );
        }
        catch ( IllegalStateException e ) // From TxManager.resume (if the tx is already active)
        {
            submitSilent( unfinishedTransactionExecutor, newTransactionFinisher( slave ) );
        }
        catch ( Throwable failure ) // Unknown error trying to finish off the tx
        {
            submitSilent( unfinishedTransactionExecutor, newTransactionFinisher( slave ) );
            msgLog.logMessage( "Could not finish off dead channel", failure );
        }
    }

    private void submitSilent( ExecutorService service, Runnable job )
    {
        try
        {
            service.submit( job );
        }
        catch ( RejectedExecutionException e )
        {   // Don't scream and shout if we're shutting down, because a rejected execution
            // is expected at that time.
            if ( !shuttingDown ) throw e;
        }
    }

    private Runnable newTransactionFinisher( final SlaveContext slave )
    {
        return new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    finishOffChannel( null, slave );
                }
                catch ( Throwable e )
                {
                    // Introduce some delay here. it becomes like a busy wait if it never succeeds
                    sleepNicely( 200 );
                    unfinishedTransactionExecutor.submit( newTransactionFinisher( slave ) );
                }
            }

            private void sleepNicely( int millis )
            {
                try
                {
                    Thread.sleep( millis );
                }
                catch ( InterruptedException e )
                {
                    Thread.interrupted();
                }
            }
        };
    }

    protected void handleRequest( ChannelBuffer buffer, final Channel channel ) throws IOException
    {
        byte continuation = buffer.readByte();
        if ( continuation == ChunkingChannelBuffer.CONTINUATION_MORE )
        {
            PartialRequest partialRequest = partialRequests.get( channel );
            if ( partialRequest == null )
            {
                // This is the first chunk in a multi-chunk request
                RequestType<M> type = getRequestContext( buffer.readByte() );
                SlaveContext context = readContext( buffer );
                Pair<ChannelBuffer, ByteBuffer> targetBuffers = mapSlave( channel, context, type );
                partialRequest = new PartialRequest( type, context, targetBuffers );
                partialRequests.put( channel, partialRequest );
            }
            partialRequest.add( buffer );
        }
        else
        {
            PartialRequest partialRequest = partialRequests.remove( channel );
            RequestType<M> type = null;
            SlaveContext context = null;
            Pair<ChannelBuffer, ByteBuffer> targetBuffers;
            ChannelBuffer bufferToReadFrom = null;
            ChannelBuffer bufferToWriteTo = null;
            if ( partialRequest == null )
            {
                // This is the one and single chunk in the request
                type = getRequestContext( buffer.readByte() );
                context = readContext( buffer );
                targetBuffers = mapSlave( channel, context, type );
                bufferToReadFrom = buffer;
                bufferToWriteTo = targetBuffers.first();
            }
            else
            {
                // This is the last chunk in a multi-chunk request
                type = partialRequest.type;
                context = partialRequest.context;
                targetBuffers = partialRequest.buffers;
                partialRequest.add( buffer );
                bufferToReadFrom = targetBuffers.first();
                bufferToWriteTo = ChannelBuffers.dynamicBuffer();
            }

            bufferToWriteTo.clear();
            final ChunkingChannelBuffer chunkingBuffer = new ChunkingChannelBuffer( bufferToWriteTo, channel, frameLength );
            submitSilent( masterCallExecutor, masterCaller( type, channel, context, chunkingBuffer, bufferToReadFrom, targetBuffers.other() ) );
        }
    }

    private Runnable masterCaller( final RequestType<M> type, final Channel channel, final SlaveContext context,
            final ChunkingChannelBuffer targetBuffer, final ChannelBuffer bufferToReadFrom, final ByteBuffer targetByteBuffer )
    {
        return new Runnable()
        {
            @SuppressWarnings( "unchecked" )
            public void run()
            {
                Response<R> response = null;
                try
                {
                    response = type.getMasterCaller().callMaster( realMaster, context, bufferToReadFrom, targetBuffer );
                    type.getObjectSerializer().write( response.response(), targetBuffer );
                    writeStoreId( response.getStoreId(), targetBuffer );
                    writeTransactionStreams( response.transactions(), targetBuffer, targetByteBuffer );
                    targetBuffer.done();
                    responseWritten( type, channel, context );
                }
                catch ( Throwable e )
                {
                    e.printStackTrace();
                    channel.close();
                    tryToFinishOffChannel( channel, context );
                    throw Exceptions.launderedException( e );
                }
                finally
                {
                    if ( response != null ) response.close();
                    unmapSlave( channel, context );
                }
            }
        };
    }
    
    protected void responseWritten( RequestType<M> type, Channel channel, SlaveContext context )
    {
    }
    
    private static void writeStoreId( StoreId storeId, ChannelBuffer targetBuffer )
    {
        targetBuffer.writeBytes( storeId.serialize() );
    }
    
    private static <T> void writeTransactionStreams( TransactionStream txStream,
            ChannelBuffer buffer, ByteBuffer readBuffer ) throws IOException
    {
        if ( !txStream.hasNext() )
        {
            buffer.writeByte( 0 );
            return;
        }
        
        String[] datasources = txStream.dataSourceNames();
        assert datasources.length <= 255 : "too many data sources";
        buffer.writeByte( datasources.length );
        Map<String, Integer> datasourceId = new HashMap<String, Integer>();
        for ( int i = 0; i < datasources.length; i++ )
        {
            String datasource = datasources[i];
            writeString( buffer, datasource );
            datasourceId.put( datasource, i + 1/*0 means "no more transactions"*/);
        }
        for ( Triplet<String, Long, TxExtractor> tx : IteratorUtil.asIterable( txStream ) )
        {
            buffer.writeByte( datasourceId.get( tx.first() ) );
            buffer.writeLong( tx.second() );
            BlockLogBuffer blockBuffer = new BlockLogBuffer( buffer );
            tx.third().extract( blockBuffer );
            blockBuffer.done();
        }
        buffer.writeByte( 0/*no more transactions*/);
    }

    protected SlaveContext readContext( ChannelBuffer buffer )
    {
        long sessionId = buffer.readLong();
        int machineId = buffer.readInt();
        int eventIdentifier = buffer.readInt();
        int txsSize = buffer.readByte();
        @SuppressWarnings( "unchecked" )
        Pair<String, Long>[] lastAppliedTransactions = new Pair[txsSize];
        for ( int i = 0; i < txsSize; i++ )
        {
            lastAppliedTransactions[i] = Pair.of( readString( buffer ), buffer.readLong() );
        }
        return new SlaveContext( sessionId, machineId, eventIdentifier, lastAppliedTransactions );
    }

    protected abstract RequestType<M> getRequestContext( byte id );

    protected Pair<ChannelBuffer, ByteBuffer> mapSlave( Channel channel, SlaveContext slave, RequestType<M> type )
    {
        channelGroup.add( channel );
        Pair<ChannelBuffer, ByteBuffer> buffer = null;
        synchronized ( connectedSlaveChannels )
        {
            // Checking for machineId -1 excludes the "empty" slave contexts
            // which some communication points pass in as context.
            if ( slave != null && slave.machineId() != SlaveContext.EMPTY.machineId() )
            {
                Pair<SlaveContext, AtomicLong> previous = connectedSlaveChannels.get( channel );
                if ( previous != null )
                {
                    previous.other().set( System.currentTimeMillis() );
                }
                else
                {
                    connectedSlaveChannels.put( channel, Pair.of( slave, new AtomicLong( System.currentTimeMillis() ) ) );
                }
            }
            buffer = channelBuffers.get( channel );
            if ( buffer == null )
            {
                buffer = Pair.of( ChannelBuffers.dynamicBuffer(), ByteBuffer.allocateDirect( 1*1024*1024 ) );
                channelBuffers.put( channel, buffer );
            }
            buffer.first().clear();
        }
        return buffer;
    }

    protected void unmapSlave( Channel channel, SlaveContext slave )
    {
        synchronized ( connectedSlaveChannels )
        {
            connectedSlaveChannels.remove( channel );
            channelBuffers.remove( channel );
            channelGroup.remove( channel );
        }
    }
    
    protected M getMaster()
    {
        return realMaster;
    }

    public void shutdown()
    {
        // Close all open connections
        shuttingDown = true;
        silentChannelExecutor.shutdown();
        unfinishedTransactionExecutor.shutdown();
        masterCallExecutor.shutdown();
        msgLog.logMessage( getClass().getSimpleName() + " shutdown, closing all channels", true );
        channelGroup.close().awaitUninterruptibly();
        executor.shutdown();
        // TODO This should work, but blocks with busy wait sometimes
//        channelFactory.releaseExternalResources();
    }

    protected abstract void finishOffChannel( Channel channel, SlaveContext context );

    public Map<Channel, SlaveContext> getConnectedSlaveChannels()
    {
        Map<Channel, SlaveContext> result = new HashMap<Channel, SlaveContext>();
        synchronized ( connectedSlaveChannels )
        {
            for ( Map.Entry<Channel, Pair<SlaveContext, AtomicLong>> entry : connectedSlaveChannels.entrySet() )
            {
                result.put( entry.getKey(), entry.getValue().first() );
            }
        }
        return result;
    }

    // =====================================================================
    // Just some methods which aren't really used when running an HA cluster,
    // but exposed so that other tools can reach that information.
    // =====================================================================

    private class PartialRequest
    {
        final SlaveContext context;
        final Pair<ChannelBuffer, ByteBuffer> buffers;
        final RequestType<M> type;

        public PartialRequest( RequestType<M> type, SlaveContext context, Pair<ChannelBuffer, ByteBuffer> buffers )
        {
            this.type = type;
            this.context = context;
            this.buffers = buffers;
        }

        public void add( ChannelBuffer buffer )
        {
            this.buffers.first().writeBytes( buffer );
        }
    }
}
