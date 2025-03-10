package sttp.client3.httpclient.zio

import _root_.zio.interop.reactivestreams._
import org.reactivestreams.FlowAdapters
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.HttpClientBackend.EncodingHandler
import sttp.client3.impl.zio.{RIOMonadAsyncError, ZioSimpleQueue}
import sttp.client3.internal._
import sttp.client3.internal.httpclient.{BodyFromHttpClient, BodyToHttpClient, Sequencer}
import sttp.client3.internal.ws.SimpleQueue
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{FollowRedirectsBackend, HttpClientAsyncBackend, HttpClientBackend, SttpBackend, SttpBackendOptions}
import sttp.monad.MonadError
import zio.Chunk.ByteArray
import zio._
import zio.stream.{ZSink, ZStream, ZTransducer}

import java.io.UnsupportedEncodingException
import java.net.http.HttpRequest.{BodyPublisher, BodyPublishers}
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.util
import java.util.concurrent.Flow.Publisher
import java.{util => ju}
import scala.collection.JavaConverters._

class HttpClientZioBackend private (
    client: HttpClient,
    closeClient: Boolean,
    customizeRequest: HttpRequest => HttpRequest,
    customEncodingHandler: EncodingHandler[ZioStreams.BinaryStream]
) extends HttpClientAsyncBackend[
      Task,
      ZioStreams,
      ZioStreams with WebSockets,
      Publisher[ju.List[ByteBuffer]],
      ZioStreams.BinaryStream
    ](
      client,
      new RIOMonadAsyncError[Any],
      closeClient,
      customizeRequest,
      customEncodingHandler
    ) {

  override val streams: ZioStreams = ZioStreams

  override protected def createBodyHandler: HttpResponse.BodyHandler[Publisher[util.List[ByteBuffer]]] =
    BodyHandlers.ofPublisher()

  override protected def emptyBody(): ZStream[Any, Throwable, Byte] = ZStream.empty

  override protected def bodyHandlerBodyToBody(p: Publisher[util.List[ByteBuffer]]): ZStream[Any, Throwable, Byte] =
    FlowAdapters
      .toPublisher(p)
      .toStream()
      .mapConcatChunk(list => ByteArray(list.asScala.toList.flatMap(_.safeRead()).toArray))

  override protected val bodyToHttpClient: BodyToHttpClient[Task, ZioStreams] =
    new BodyToHttpClient[Task, ZioStreams] {
      override val streams: ZioStreams = ZioStreams
      override implicit def monad: MonadError[Task] = responseMonad
      override def streamToPublisher(stream: ZStream[Any, Throwable, Byte]): Task[BodyPublisher] = {
        import _root_.zio.interop.reactivestreams.{streamToPublisher => zioStreamToPublisher}
        val publisher = stream.mapChunks(byteChunk => Chunk(ByteBuffer.wrap(byteChunk.toArray))).toPublisher
        publisher.map { pub =>
          BodyPublishers.fromPublisher(FlowAdapters.toFlowPublisher(pub))
        }
      }
    }

  override protected val bodyFromHttpClient: BodyFromHttpClient[Task, ZioStreams, ZioStreams.BinaryStream] =
    new ZioBodyFromHttpClient

  override protected def createSimpleQueue[T]: Task[SimpleQueue[Task, T]] =
    for {
      runtime <- ZIO.runtime[Any]
      queue <- Queue.unbounded[T]
    } yield new ZioSimpleQueue(queue, runtime)

  override protected def createSequencer: Task[Sequencer[Task]] = ZioSequencer.create

  override protected def standardEncoding: (ZStream[Any, Throwable, Byte], String) => ZStream[Any, Throwable, Byte] = {
    case (body, "gzip") => body.transduce(ZTransducer.gunzip())
    case (body, "deflate") =>
      ZStream.managed(body.peel(ZSink.take[Byte](1))).flatMap { case (chunk, stream) =>
        val wrapped = chunk.headOption.exists(byte => (byte & 0x0f) == 0x08)
        (ZStream.fromChunk(chunk) ++ stream).transduce(ZTransducer.inflate(noWrap = !wrapped))
      }
    case (_, ce) => ZStream.fail(new UnsupportedEncodingException(s"Unsupported encoding: $ce"))
  }
}

object HttpClientZioBackend {

  type ZioEncodingHandler = EncodingHandler[ZioStreams.BinaryStream]

  private def apply(
      client: HttpClient,
      closeClient: Boolean,
      customizeRequest: HttpRequest => HttpRequest,
      customEncodingHandler: ZioEncodingHandler
  ): SttpBackend[Task, ZioStreams with WebSockets] =
    new FollowRedirectsBackend(
      new HttpClientZioBackend(
        client,
        closeClient,
        customizeRequest,
        customEncodingHandler
      )
    )

  def apply(
      options: SttpBackendOptions = SttpBackendOptions.Default,
      customizeRequest: HttpRequest => HttpRequest = identity,
      customEncodingHandler: ZioEncodingHandler = PartialFunction.empty
  ): Task[SttpBackend[Task, ZioStreams with WebSockets]] = {
    UIO.executor.flatMap(executor =>
      Task.effect(
        HttpClientZioBackend(
          HttpClientBackend.defaultClient(options, Some(executor.asJava)),
          closeClient = false, // we don't want to close ZIO's executor
          customizeRequest,
          customEncodingHandler
        )
      )
    )
  }

  def managed(
      options: SttpBackendOptions = SttpBackendOptions.Default,
      customizeRequest: HttpRequest => HttpRequest = identity,
      customEncodingHandler: ZioEncodingHandler = PartialFunction.empty
  ): ZManaged[Any, Throwable, SttpBackend[Task, ZioStreams with WebSockets]] =
    ZManaged.make(apply(options, customizeRequest, customEncodingHandler))(
      _.close().ignore
    )

  def layer(
      options: SttpBackendOptions = SttpBackendOptions.Default,
      customizeRequest: HttpRequest => HttpRequest = identity,
      customEncodingHandler: ZioEncodingHandler = PartialFunction.empty
  ): ZLayer[Any, Throwable, SttpClient] = {
    ZLayer.fromManaged(
      (for {
        backend <- HttpClientZioBackend(
          options,
          customizeRequest,
          customEncodingHandler
        )
      } yield backend).toManaged(_.close().ignore)
    )
  }

  def usingClient(
      client: HttpClient,
      customizeRequest: HttpRequest => HttpRequest = identity,
      customEncodingHandler: ZioEncodingHandler = PartialFunction.empty
  ): SttpBackend[Task, ZioStreams with WebSockets] =
    HttpClientZioBackend(
      client,
      closeClient = false,
      customizeRequest,
      customEncodingHandler
    )

  def layerUsingClient(
      client: HttpClient,
      customizeRequest: HttpRequest => HttpRequest = identity,
      customEncodingHandler: ZioEncodingHandler = PartialFunction.empty
  ): ZLayer[Any, Throwable, SttpClient] = {
    ZLayer.fromManaged(
      ZManaged
        .makeEffect(
          usingClient(
            client,
            customizeRequest,
            customEncodingHandler
          )
        )(_.close().ignore)
    )
  }

  /** Create a stub backend for testing, which uses the [[Task]] response wrapper, and supports `Stream[Throwable,
    * ByteBuffer]` streaming.
    *
    * See [[SttpBackendStub]] for details on how to configure stub responses.
    */
  def stub: SttpBackendStub[Task, ZioStreams with WebSockets] =
    SttpBackendStub(new RIOMonadAsyncError[Any])

  val stubLayer: ZLayer[Any, Nothing, SttpClientStubbing with SttpClient] = SttpClientStubbing.layer
}
