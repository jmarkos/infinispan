package org.infinispan.server.core

import org.infinispan.{AdvancedCache, Cache}
import Operation._
import java.util.concurrent.TimeUnit
import transport._
import java.io.StreamCorruptedException
import transport.ExtendedByteBuf._
import DecoderState._
import logging.Log
import java.lang.StringBuilder
import org.infinispan.container.entries.CacheEntry
import org.infinispan.metadata.{Metadata, EmbeddedMetadata}
import org.infinispan.container.versioning.{NumericVersionGenerator, EntryVersion, VersionGenerator, NumericVersion}
import org.infinispan.context.Flag
import java.io.IOException
import io.netty.handler.codec.ReplayingDecoder
import io.netty.channel._
import io.netty.buffer.{Unpooled, ByteBuf}
import java.util
import io.netty.util.CharsetUtil
import java.net.SocketAddress
import javax.security.auth.Subject
import java.security.PrivilegedExceptionAction
import org.infinispan.configuration.cache.Configuration
import org.infinispan.factories.ComponentRegistry
import org.infinispan.remoting.rpc.RpcManager
import org.infinispan.manager.EmbeddedCacheManager
import java.security.PrivilegedAction

/**
 * Common abstract decoder for Memcached and Hot Rod protocols.
 *
 * @author Galder Zamarreño
 * @since 4.1
 */
abstract class AbstractProtocolDecoder[K, V](secure: Boolean, transport: NettyTransport)
      extends ReplayingDecoder[DecoderState](DECODE_HEADER) with ChannelOutboundHandler with ServerConstants with Log {
   import AbstractProtocolDecoder._

   type SuitableParameters <: RequestParameters
   type SuitableHeader <: RequestHeader

   private val isTrace = isTraceEnabled

   protected var header: SuitableHeader = null.asInstanceOf[SuitableHeader]
   protected var params: SuitableParameters = null.asInstanceOf[SuitableParameters]
   protected var key: K = null.asInstanceOf[K]
   protected var rawValue: Array[Byte] = null.asInstanceOf[Array[Byte]]
   protected var cache: AdvancedCache[K, V] = null
   protected var cacheConfiguration: Configuration = null
   protected var defaultLifespanTime: Long = _
   protected var defaultMaxIdleTime: Long = _
   var subject: Subject = ANONYMOUS

   def decode(ctx: ChannelHandlerContext, in: ByteBuf, out: util.List[AnyRef]): Unit = {
      if (secure) {
         secureDecodeDispatch(ctx, in, out)
      } else {
         decodeDispatch(ctx, in, out)
      }
   }

   private def decodeDispatch(ctx: ChannelHandlerContext, in: ByteBuf, out: util.List[AnyRef]): Unit = {
      try {
         if (isTrace) // To aid debugging
            trace("Decode using instance @%x", System.identityHashCode(this))
         state match {
            case DECODE_HEADER => decodeHeader(ctx, in, state, out)
            case DECODE_KEY => decodeKey(ctx, in, state)
            case DECODE_PARAMETERS => decodeParameters(ctx, in, state)
            case DECODE_VALUE => decodeValue(ctx, in, state)
         }
      } catch {
         case e: Exception => {
            val (serverException, isClientError) = createServerException(e, in)
            // If decode returns an exception, decode won't be called again so,
            // we need to fire the exception explicitly so that requests can
            // carry on being processed on same connection after a client error
            if (isClientError) {
               ctx.pipeline.fireExceptionCaught(serverException)
            } else {
               throw serverException
            }
         }
         case t: Throwable => throw t
      }
   }

   private def secureDecodeDispatch(ctx: ChannelHandlerContext, in: ByteBuf, out: util.List[AnyRef]): Unit = {
      try {
         if (isTrace) // To aid debugging
            trace("Decode using instance @%x", System.identityHashCode(this))
         state match {
            case DECODE_HEADER => decodeHeader(ctx, in, state, out)
            case DECODE_KEY =>
               Subject.doAs(subject, new PrivilegedExceptionAction[Unit] {
                  def run: Unit = {
                     decodeKey(ctx, in, state)
                  }
               })
            case DECODE_PARAMETERS =>
               Subject.doAs(subject, new PrivilegedExceptionAction[Unit] {
                  def run: Unit = {
                     decodeParameters(ctx, in, state)
                  }
               })
            case DECODE_VALUE =>
               Subject.doAs(subject, new PrivilegedExceptionAction[Unit] {
                  def run: Unit = {
                     decodeValue(ctx, in, state)
                  }
               })
         }
      } catch {
         case e: Exception => {
            val (serverException, isClientError) = createServerException(e, in)
            // If decode returns an exception, decode won't be called again so,
            // we need to fire the exception explicitly so that requests can
            // carry on being processed on same connection after a client error
            if (isClientError) {
               ctx.pipeline.fireExceptionCaught(serverException)
            } else {
               throw serverException
            }
         }
         case t: Throwable => throw t
      }
   }

   private def decodeHeader(ctx: ChannelHandlerContext, buffer: ByteBuf, state: DecoderState, out: util.List[AnyRef]): AnyRef = {
      header = createHeader
      val endOfOp = readHeader(buffer, header)
      if (endOfOp == None) {
         // Something went wrong reading the header, so get more bytes.
         // It can happen with Hot Rod if the header is completely corrupted
         return null
      }
      val ch = ctx.channel
      cache = getCache.getAdvancedCache
      cacheConfiguration = getCacheConfiguration
      defaultLifespanTime = cacheConfiguration.expiration().lifespan()
      defaultMaxIdleTime = cacheConfiguration.expiration().maxIdle()
      if (endOfOp.get) {
         val message = header.op match {
            case StatsRequest => writeResponse(ch, createStatsResponse)
            case _ => customDecodeHeader(ctx, buffer)
         }
         message match {
            case pr: PartialResponse => pr.buffer.map(out.add(_))
            case _ => null
         }
         null
      } else {
         checkpointTo(DECODE_KEY)
      }
   }

   private def decodeKey(ctx: ChannelHandlerContext, buffer: ByteBuf, state: DecoderState): AnyRef = {
      val ch = ctx.channel
      header.op match {
         // Get, put and remove are the most typical operations, so they're first
         case GetRequest => writeResponse(ch, get(buffer))
         case PutRequest => handleModification(ch, buffer)
         case RemoveRequest => handleModification(ch, buffer)
         case GetWithVersionRequest => writeResponse(ch, get(buffer))
         case PutIfAbsentRequest | ReplaceRequest | ReplaceIfUnmodifiedRequest =>
            handleModification(ch, buffer)
         case _ => customDecodeKey(ctx, buffer)
      }
   }

   def handleModification(ch: Channel, buf: ByteBuf): AnyRef = {
      val (k, endOfOp) = readKey(buf)
      key = k
      if (endOfOp) {
         // If it's the end of the operation, it can only be a remove
         writeResponse(ch, remove)
      } else {
         checkpointTo(DECODE_PARAMETERS)
      }
   }


   private def decodeParameters(ctx: ChannelHandlerContext, buffer: ByteBuf, state: DecoderState): AnyRef = {
      val ch = ctx.channel
      val endOfOp = readParameters(ch, buffer)
      if (!endOfOp && params.valueLength > 0) {
         // Create value holder and checkpoint only if there's more to read
         rawValue = new Array[Byte](params.valueLength)
         checkpointTo(DECODE_VALUE)
      } else if (params.valueLength == 0){
         rawValue = Array.empty
         decodeValue(ctx, buffer, state)
      } else {
         decodeValue(ctx, buffer, state)
      }
   }

   private def decodeValue(ctx: ChannelHandlerContext, buffer: ByteBuf, state: DecoderState): AnyRef = {
      val ch = ctx.channel
      val ret = header.op match {
         case PutRequest | PutIfAbsentRequest | ReplaceRequest | ReplaceIfUnmodifiedRequest  => {
            readValue(buffer)
            header.op match {
               case PutRequest => put
               case PutIfAbsentRequest => putIfAbsent
               case ReplaceRequest => replace
               case ReplaceIfUnmodifiedRequest => replaceIfUnmodified
            }
         }
         case RemoveRequest => remove
         case _ => customDecodeValue(ctx, buffer)
      }
      writeResponse(ch, ret)
   }

   protected def writeResponse(ch: Channel, response: AnyRef): AnyRef = {
      try {
         if (response != null) {
            if (isTrace) trace("Write response %s", response)
            response match {
               // We only expect Lists of ChannelBuffer instances, so don't worry about type erasure
               case l: Array[ByteBuf] => {
                 l.foreach(ch.write(_))
                 ch.flush
               }
               case a: Array[Byte] => ch.writeAndFlush(wrappedBuffer(a))
               case cs: CharSequence => ch.writeAndFlush(Unpooled.copiedBuffer(cs, CharsetUtil.UTF_8))
               case pr: PartialResponse => return pr
               case _ => ch.writeAndFlush(response)
            }
         }
         null
      } finally {
         resetParams
      }
   }

   private def resetParams: AnyRef = {
      checkpointTo(DECODE_HEADER)
      // Reset parameters to avoid leaking previous params
      // into a request that has no params
      params = null.asInstanceOf[SuitableParameters]
      rawValue = null.asInstanceOf[Array[Byte]] // Clear reference to value
      null
   }

   private def put: AnyRef = {
      // Get an optimised cache in case we can make the operation more efficient
      val prev = cache.put(key, createValue(), buildMetadata())
      createSuccessResponse(prev)
   }

   protected def buildMetadata(): Metadata = {
      val metadata = new EmbeddedMetadata.Builder
      metadata.version(generateVersion(cache))
      (params.lifespan, params.maxIdle) match {
         case (EXPIRATION_DEFAULT, EXPIRATION_DEFAULT) =>
            metadata.lifespan(defaultLifespanTime)
                    .maxIdle(defaultMaxIdleTime)
         case (_, EXPIRATION_DEFAULT) =>
            metadata.lifespan(toMillis(params.lifespan))
                    .maxIdle(defaultMaxIdleTime)
         case (_, _) =>
            metadata.lifespan(toMillis(params.lifespan))
                    .maxIdle(toMillis(params.maxIdle))
      }
      metadata.build()
   }

   override def actualReadableBytes(): Int = {
      super.actualReadableBytes()
   }

   private def putIfAbsent: AnyRef = {
      var prev = cache.get(key)
      if (prev == null) { // Generate new version only if key not present
         prev = cache.putIfAbsent(key, createValue(), buildMetadata())
      }
      if (prev == null)
         createSuccessResponse(prev)
      else
         createNotExecutedResponse(prev)
   }

   private def replace: AnyRef = {
      // Avoid listener notification for a simple optimization
      // on whether a new version should be calculated or not.
      var prev = cache.withFlags(Flag.SKIP_LISTENER_NOTIFICATION).get(key)
      if (prev != null) { // Generate new version only if key present
         prev = cache.replace(key, createValue(), buildMetadata())
      }
      if (prev != null)
         createSuccessResponse(prev)
      else
         createNotExecutedResponse(prev)
   }

   private def replaceIfUnmodified: AnyRef = {
      val entry = cache.withFlags(Flag.SKIP_LISTENER_NOTIFICATION).getCacheEntry(key)
      if (entry != null) {
         // Hacky, but CacheEntry has not been generified
         val prev: V = entry.getValue.asInstanceOf[V]
         val streamVersion = new NumericVersion(params.streamVersion)
         if (entry.getMetadata.version() == streamVersion) {
            val v = createValue()
            // Generate new version only if key present and version has not changed, otherwise it's wasteful
            val replaced = cache.replace(key, prev, v, buildMetadata())
            if (replaced)
               createSuccessResponse(prev)
            else
               createNotExecutedResponse(prev)
         } else {
            createNotExecutedResponse(prev)
         }
      } else createNotExistResponse
   }

   private def remove: AnyRef = {
      val prev = cache.remove(key)
      if (prev != null)
         createSuccessResponse(prev)
      else
         createNotExistResponse
   }

   protected def get(buffer: ByteBuf): AnyRef =
      createGetResponse(key, cache.getCacheEntry(readKey(buffer)._1))

   override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
      val ch = ctx.channel
      // Log it just in case the channel is closed or similar
      debug(cause, "Exception caught")
      if (!cause.isInstanceOf[IOException]) {
         val errorResponse = createErrorResponse(cause)
         if (errorResponse != null) {
            errorResponse match {
               case a: Array[Byte] => ch.writeAndFlush(wrappedBuffer(a))
               case cs: CharSequence => ch.writeAndFlush(Unpooled.copiedBuffer(cs, CharsetUtil.UTF_8))
               case null => // ignore
               case _ => ch.writeAndFlush(errorResponse)
            }
         }
      }
      // After writing back an error, reset params and revert to initial state
      resetParams
   }

   override def channelActive(ctx: ChannelHandlerContext) {
      transport.acceptedChannels.add(ctx.channel)
      super.channelActive(ctx)
   }

   def checkpointTo(state: DecoderState): AnyRef = {
      checkpoint(state)
      null // For netty's decoder that mandates a return
   }

   protected def createHeader: SuitableHeader

   protected def readHeader(b: ByteBuf, header: SuitableHeader): Option[Boolean]

   protected def getCache: Cache[K, V]

   protected def getCacheConfiguration: Configuration

   protected def getCacheRegistry: ComponentRegistry

   /**
    * Returns the key read along with a boolean indicating whether the
    * end of the operation was found or not. This allows client to
    * differentiate between extra parameters or pipelined sequence of
    * operations.
    */
   protected def readKey(b: ByteBuf): (K, Boolean)

   protected def readParameters(ch: Channel, b: ByteBuf): Boolean

   protected def readValue(b: ByteBuf)

   protected def createValue(): V

   protected def createSuccessResponse(prev: V): AnyRef

   protected def createNotExecutedResponse(prev: V): AnyRef

   protected def createNotExistResponse: AnyRef

   protected def createGetResponse(k: K, entry: CacheEntry[K, V]): AnyRef

   protected def createMultiGetResponse(pairs: Map[K, CacheEntry[K, V]]): AnyRef

   protected def createErrorResponse(t: Throwable): AnyRef

   protected def createStatsResponse: AnyRef

   protected def customDecodeHeader(ctx: ChannelHandlerContext, buffer: ByteBuf): AnyRef

   protected def customDecodeKey(ctx: ChannelHandlerContext, buffer: ByteBuf): AnyRef

   protected def customDecodeValue(ctx: ChannelHandlerContext, buffer: ByteBuf): AnyRef

   protected def createServerException(e: Exception, b: ByteBuf): (Exception, Boolean)

   protected def generateVersion(cache: Cache[K, V]): EntryVersion = {
      val registry = getCacheRegistry
      val cacheVersionGenerator = registry.getComponent(classOf[VersionGenerator])
      if (cacheVersionGenerator == null) {
         // It could be null, for example when not running in compatibility mode.
         // The reason for that is that if no other component depends on the
         // version generator, the factory does not get invoked.
         val newVersionGenerator = new NumericVersionGenerator()
                 .clustered(registry.getComponent(classOf[RpcManager]) != null)
         registry.registerComponent(newVersionGenerator, classOf[VersionGenerator])
         newVersionGenerator.generateNew()
      } else {
         cacheVersionGenerator.generateNew()
      }
   }

   /**
    * Transforms lifespan pass as seconds into milliseconds
    * following this rule:
    *
    * If lifespan is bigger than number of seconds in 30 days,
    * then it is considered unix time. After converting it to
    * milliseconds, we substract the current time in and the
    * result is returned.
    *
    * Otherwise it's just considered number of seconds from
    * now and it's returned in milliseconds unit.
    */
   protected def toMillis(lifespan: Int): Long = {
      if (lifespan > SecondsInAMonth) {
         val unixTimeExpiry = TimeUnit.SECONDS.toMillis(lifespan) - System.currentTimeMillis
         if (unixTimeExpiry < 0) 0 else unixTimeExpiry
      } else {
         TimeUnit.SECONDS.toMillis(lifespan)
      }
   }

  def bind(ctx: ChannelHandlerContext, localAddress: SocketAddress, promise: ChannelPromise): Unit = ctx.bind(localAddress, promise)

  def connect(ctx: ChannelHandlerContext, remoteAddress: SocketAddress, localAddress: SocketAddress, promise: ChannelPromise): Unit = ctx.connect(remoteAddress, localAddress, promise)

  def disconnect(ctx: ChannelHandlerContext, promise: ChannelPromise): Unit = ctx.disconnect(promise)

  def close(ctx: ChannelHandlerContext, promise: ChannelPromise): Unit = ctx.close(promise)

  def deregister(ctx: ChannelHandlerContext, promise: ChannelPromise): Unit = ctx.deregister(promise)

  def read(ctx: ChannelHandlerContext): Unit = ctx.read()

  def write(ctx: ChannelHandlerContext, msg: scala.Any, promise: ChannelPromise): Unit = {
    val readable = msg.asInstanceOf[ByteBuf].readableBytes()
    ctx.write(msg, promise.addListener(new ChannelFutureListener {
      def operationComplete(future: ChannelFuture): Unit = {
        if (future.isSuccess) {
          transport.updateTotalBytesWritten(readable)
        }
      }
    }))
  }

  def flush(ctx: ChannelHandlerContext): Unit = ctx.flush()

  override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
    transport.updateTotalBytesRead(msg.asInstanceOf[ByteBuf].readableBytes())
    super.channelRead(ctx, msg)
  }
}

object AbstractProtocolDecoder extends Log {
   private val SecondsInAMonth = 60 * 60 * 24 * 30
   private val DefaultTimeUnit = TimeUnit.MILLISECONDS
}

class RequestHeader {
   var op: Enumeration#Value = _

   override def toString = {
      new StringBuilder().append("RequestHeader").append("{")
         .append("op=").append(op)
         .append("}").toString
   }
}

class RequestParameters(val valueLength: Int, val lifespan: Int, val maxIdle: Int, val streamVersion: Long) {
   override def toString = {
      new StringBuilder().append("RequestParameters").append("{")
         .append("valueLength=").append(valueLength)
         .append(", lifespan=").append(lifespan)
         .append(", maxIdle=").append(maxIdle)
         .append(", streamVersion=").append(streamVersion)
         .append("}").toString
   }
}

class UnknownOperationException(reason: String) extends StreamCorruptedException(reason)

class PartialResponse(val buffer: Option[ByteBuf])
