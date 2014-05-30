package org.infinispan.server.test.query;

import org.infinispan.arquillian.core.InfinispanResource;
import org.infinispan.arquillian.core.RemoteInfinispanServer;
import org.infinispan.arquillian.core.RunningServer;
import org.infinispan.arquillian.core.WithRunningServer;
import org.jboss.arquillian.junit.Arquillian;
import org.junit.runner.RunWith;

/**
 * Tests for remote queries over HotRod on a replicated cache using Infinispan directory.
 *
 * @author Adrian Nistor
 */
@RunWith(Arquillian.class)
public class RemoteQueryIspnDirTest extends RemoteQueryTest {

   @InfinispanResource("remote-query-merged")
   protected RemoteInfinispanServer server;

   public RemoteQueryIspnDirTest() {
      super("clustered", "repltestcache");
   }

   @Override
   protected RemoteInfinispanServer getServer() {
      return server;
   }
}
