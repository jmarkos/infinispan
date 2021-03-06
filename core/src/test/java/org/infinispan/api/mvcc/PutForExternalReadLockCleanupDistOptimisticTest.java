package org.infinispan.api.mvcc;

import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.test.fwk.CleanupAfterMethod;
import org.infinispan.transaction.LockingMode;
import org.testng.annotations.Test;

@Test(groups = "functional", testName = "api.mvcc.PutForExternalReadLockCleanupDistOptimisticTest")
@CleanupAfterMethod
public class PutForExternalReadLockCleanupDistOptimisticTest extends PutForExternalReadLockCleanupTest {

   @Override
   protected final boolean transactional() {
      return true;
   }

   @Override
   protected void amendConfiguration(ConfigurationBuilder builder) {
      builder.transaction().lockingMode(LockingMode.OPTIMISTIC).useSynchronization(false)
            .recovery().disable()
            .locking().useLockStriping(false);
   }
}
