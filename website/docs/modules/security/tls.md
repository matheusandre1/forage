# TLS

Forage creates `SSLContextParameters` beans from properties, eliminating the need to hand-write keystore/truststore wiring in Java. Any Camel component that accepts `sslContextParameters` (HTTP, Netty, FTPS, Kafka, CXF, etc.) can reference the bean by name.

## Quick Start

```properties
forage.tls.keystore.path=server.p12
forage.tls.keystore.password=changeit
forage.tls.keystore.type=PKCS12
forage.tls.truststore.path=truststore.jks
forage.tls.truststore.password=trustme
forage.tls.secure.socket.protocol=TLSv1.3
```

This registers an `SSLContextParameters` bean named `sslContextParameters` in the Camel registry. Use it in routes:

```yaml
- to:
    uri: https://api.example.com/orders
    parameters:
      sslContextParameters: "#sslContextParameters"
```

## Properties

| Property | Description | Default |
|---|---|---|
| `forage.tls.keystore.path` | Path to the keystore file (filesystem or `classpath:` URI) | — |
| `forage.tls.keystore.password` | Keystore password | — |
| `forage.tls.keystore.type` | Keystore type (`JKS`, `PKCS12`, etc.) | `JKS` |
| `forage.tls.truststore.path` | Path to the truststore file | — |
| `forage.tls.truststore.password` | Truststore password | — |
| `forage.tls.truststore.type` | Truststore type | `JKS` |
| `forage.tls.client.authentication` | Client auth mode: `NONE`, `WANT`, or `REQUIRE` | `NONE` |
| `forage.tls.cipher.suites` | Comma-separated list of cipher suites | — |
| `forage.tls.secure.socket.protocol` | TLS protocol version | `TLSv1.3` |

All properties are optional. At least one of `keystore.path` or `truststore.path` must be set for a bean to be created.

## Named Profiles

Use prefixed names to create multiple TLS configurations:

```properties
# Internal services — mutual TLS
forage.internal.tls.keystore.path=/certs/internal.p12
forage.internal.tls.keystore.password=changeit
forage.internal.tls.keystore.type=PKCS12
forage.internal.tls.truststore.path=/certs/internal-ca.jks
forage.internal.tls.truststore.password=trustme
forage.internal.tls.client.authentication=REQUIRE

# External API — trust only, no client cert
forage.external.tls.truststore.path=/certs/external-ca.jks
forage.external.tls.truststore.password=trustme
```

This registers two beans: `internal` and `external`. Reference them in routes:

```yaml
# mTLS to internal service
- to:
    uri: https://internal-api:8443/data
    parameters:
      sslContextParameters: "#internal"

# Trust-only to external API
- to:
    uri: https://api.partner.com/v1/orders
    parameters:
      sslContextParameters: "#external"
```

## Cipher Suites

Restrict the allowed cipher suites for TLS hardening:

```properties
forage.tls.cipher.suites=TLS_AES_256_GCM_SHA384, TLS_AES_128_GCM_SHA256
```

## Runtime Support

`SSLContextParameters` is a core Camel class (`org.apache.camel.support.jsse`) — it works identically on plain Camel, Spring Boot, and Quarkus with no runtime-specific adapters needed.
