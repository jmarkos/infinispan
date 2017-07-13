package org.infinispan.conflict.impl;

import org.infinispan.AdvancedCache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.conflict.ConflictManager;
import org.infinispan.conflict.ConflictManagerFactory;
import org.infinispan.conflict.EntryMergePolicy;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.partitionhandling.BasePartitionHandlingTest;
import org.infinispan.partitionhandling.PartitionHandling;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Random;

import static org.testng.AssertJUnit.assertEquals;

@Test(groups = "functional", testName = "conflict.impl.ConflictManager2Test")
public class ConflictManager2Test extends BasePartitionHandlingTest {

   private static final String CACHE_NAME = "conflict-cache";

   public ConflictManager2Test() {
      this.cacheMode = CacheMode.DIST_SYNC;
      this.partitionHandling = PartitionHandling.ALLOW_READ_WRITES;
      this.numMembersInCluster = 2;
   }

   class CustomMergePolicy implements EntryMergePolicy<String, Integer> {
      @Override
      public CacheEntry<String, Integer> merge(CacheEntry<String, Integer> preferredEntry, List<CacheEntry<String, Integer>> otherEntries) {
         CacheEntry<String, Integer> result = preferredEntry;
         for (CacheEntry<String, Integer> otherEntry : otherEntries) {
            if (otherEntry.getValue() > result.getValue()) {
               result = otherEntry;
            }
         }
         return result;
      }
   }

   @Override
   protected void createCacheManagers() throws Throwable {
      super.createCacheManagers();
      ConfigurationBuilder builder = getDefaultClusteredCacheConfig(CacheMode.DIST_SYNC);
      builder.clustering().partitionHandling().whenSplit(partitionHandling).mergePolicy(new CustomMergePolicy()).stateTransfer().fetchInMemoryState(true);
      defineConfigurationOnAllManagers(CACHE_NAME, builder);
   }

   public void testResolveConflicts() {
      waitForClusterToForm(CACHE_NAME);

      Random r = new Random();
      // add conflicting keys
      for (int j = 0; j < 10; j++) {
         getCache(0).getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).put("k" + j, r.nextInt(50));
         getCache(1).getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).put("k" + j, r.nextInt(50) + 100);
      }

      ConflictManager<Object, Object> crm = ConflictManagerFactory.get(getCache(0).getAdvancedCache());
      assertEquals(10, crm.getConflicts().count());

      crm.resolveConflicts();

      assertEquals(0, crm.getConflicts().count());
   }

   private AdvancedCache<Object, Object> getCache(int index) {
      return advancedCache(index, CACHE_NAME);
   }

}
