package sttp.client3.armeria

import com.linecorp.armeria.client.{ClientFactory, WebClient, WebClientBuilder}
import com.linecorp.armeria.client.encoding.DecodingClient
import sttp.client3.SttpBackendOptions

object ArmeriaWebClient {
  private def newClientFactory(options: SttpBackendOptions): ClientFactory = {
    val builder = ClientFactory
      .builder()
      .connectTimeoutMillis(options.connectionTimeout.toMillis)
    options.proxy.fold(builder.build()) { proxy =>
      builder
        .proxyConfig(proxy.asJavaProxySelector)
        .build()
    }
  }

  /** Create a new [[WebClient]] which is adjusted for sttp client's needs. */
  def newClient(): WebClient = newClient(identity[WebClientBuilder] _)

  /** Create a new [[WebClient]] which is adjusted for sttp client's needs. */
  def newClient(customizeWebClient: WebClientBuilder => WebClientBuilder): WebClient = {
    customizeWebClient(
      WebClient
        .builder()
        .decorator(
          DecodingClient
            .builder()
            .autoFillAcceptEncoding(false)
            .strictContentEncoding(true)
            .newDecorator()
        )
    )
      .build()
  }

  /** Create a new [[WebClient]] which is adjusted for sttp client's needs, and uses the timeouts/proxy specified in
    * `options`.
    */
  def newClient(
      options: SttpBackendOptions,
      customizeWebClient: WebClientBuilder => WebClientBuilder = identity
  ): WebClient = {
    customizeWebClient(
      WebClient
        .builder()
        .decorator(
          DecodingClient
            .builder()
            .autoFillAcceptEncoding(false)
            .strictContentEncoding(true)
            .newDecorator()
        )
        .factory(newClientFactory(options))
    )
      .build()
  }
}
