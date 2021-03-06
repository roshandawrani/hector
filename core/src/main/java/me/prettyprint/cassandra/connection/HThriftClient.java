package me.prettyprint.cassandra.connection;

import java.util.concurrent.atomic.AtomicLong;

import me.prettyprint.cassandra.service.CassandraHost;
import me.prettyprint.cassandra.service.SystemProperties;
import me.prettyprint.hector.api.exceptions.HInvalidRequestException;
import me.prettyprint.hector.api.exceptions.HectorTransportException;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.commons.lang.StringUtils;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class HThriftClient {

  private static Logger log = LoggerFactory.getLogger(HThriftClient.class);

  private static final AtomicLong serial = new AtomicLong(0);

  final CassandraHost cassandraHost;

  private final long mySerial;
  private final int timeout;
  private String keyspaceName;

  private TTransport transport;
  private Cassandra.Client cassandraClient;

  HThriftClient(CassandraHost cassandraHost) {
    this.cassandraHost = cassandraHost;
    this.timeout = getTimeout(cassandraHost);
    mySerial = serial.incrementAndGet();
  }

  /**
   * Returns a new Cassandra.Client on each invocation using the underlying transport
   *
   */
  public Cassandra.Client getCassandra() {
    if ( !isOpen() ) {
      throw new IllegalStateException("getCassandra called on client that was not open. You should not have gotten here.");
    }
    if ( cassandraClient == null ) {
      cassandraClient = new Cassandra.Client(new TBinaryProtocol(transport));
    }
    return cassandraClient;
  }

  public Cassandra.Client getCassandra(String keyspaceNameArg) {
    getCassandra();     
    if ( keyspaceNameArg != null && !StringUtils.equals(keyspaceName, keyspaceNameArg)) {
      keyspaceName = keyspaceNameArg;
      try {
        cassandraClient.set_keyspace(keyspaceName);
        if ( log.isDebugEnabled() )
          log.debug("keyspace reset from {} to {}", keyspaceName, keyspaceNameArg);
      } catch (InvalidRequestException ire) {
        throw new HInvalidRequestException(ire);
      } catch (TException e) {
        throw new HectorTransportException(e);
      }

    }
    return cassandraClient;
  }

  HThriftClient close() {
    if ( log.isDebugEnabled() ) {
      log.debug("Closing client {}", this);
    }
    if ( isOpen() ) {
      try {
        transport.flush();
      } catch (Exception e) {
        log.error("Could not flush transport (to be expected if the pool is shutting down) in close for client: " + toString(), e);
      } finally {
        try {
          transport.close();
        } catch (Exception e) {
          log.error("Error on transport close for client: " +toString(), e);
        }
      }
    }
    return this;
  }


  HThriftClient open() {
    if ( isOpen() ) {
      throw new IllegalStateException("Open called on already open connection. You should not have gotten here.");
    }
    if ( log.isDebugEnabled() ) {
      log.debug("Creating a new thrift connection to {}", cassandraHost);
    }

    if (cassandraHost.getUseThriftFramedTransport()) {
      transport = new TFramedTransport(new TSocket(cassandraHost.getHost(), cassandraHost.getPort(), timeout));
    } else {
      transport = new TSocket(cassandraHost.getHost(), cassandraHost.getPort(), timeout);
    }
    try {
      transport.open();
    } catch (TTransportException e) {
      // Thrift exceptions aren't very good in reporting, so we have to catch the exception here and
      // add details to it.
      log.debug("Unable to open transport to " + cassandraHost.getName());
      //clientMonitor.incCounter(Counter.CONNECT_ERROR);
      throw new HectorTransportException("Unable to open transport to " + cassandraHost.getName() +" , " +
          e.getLocalizedMessage(), e);
    }
    return this;
  }


  boolean isOpen() {
    boolean open = false;
    if (transport != null) {
      open = transport.isOpen();
    }
    if ( log.isDebugEnabled() ) {
      log.debug("Transport open status {} for client {}", open, this);
    }
    return open;
  }

  /**
   * If CassandraHost was not null we use {@link CassandraHost#getCassandraThriftSocketTimeout()}
   * if it was greater than zero. Otherwise look for an environment
   * variable name CASSANDRA_THRIFT_SOCKET_TIMEOUT value.
   * If doesn't exist, returns 0.
   * @param cassandraHost
   */
  private int getTimeout(CassandraHost cassandraHost) {
    int timeoutVar = 0;
    if ( cassandraHost != null && cassandraHost.getCassandraThriftSocketTimeout() > 0 ) {
      timeoutVar = cassandraHost.getCassandraThriftSocketTimeout();
    } else {
      String timeoutStr = System.getProperty(
          SystemProperties.CASSANDRA_THRIFT_SOCKET_TIMEOUT.toString());
      if (timeoutStr != null && timeoutStr.length() > 0) {
        try {
          timeoutVar = Integer.valueOf(timeoutStr);
        } catch (NumberFormatException e) {
          log.error("Invalid value for CASSANDRA_THRIFT_SOCKET_TIMEOUT", e);
        }
      }
    }
    return timeoutVar;
  }


  @Override
  public String toString() {
    return String.format(NAME_FORMAT, cassandraHost.getUrl(), mySerial);
  }

  /**
   * Compares the toString of these clients
   */
  @Override
  public boolean equals(Object obj) {
    return this.toString().equals(obj.toString());
  }



  private static final String NAME_FORMAT = "CassandraClient<%s-%d>";
}
