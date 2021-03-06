package org.infinispan.iteration.impl;

import org.infinispan.commons.util.CloseableIterable;
import org.infinispan.commons.util.CloseableIterator;
import org.infinispan.filter.Converter;
import org.infinispan.filter.KeyValueFilter;
import org.infinispan.util.concurrent.ConcurrentHashSet;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CloseableIterable that also tracks the iterators it spawns so it can properly close them when the close method is
 * invoked.
 *
 * @author wburns
 * @since 7.0
 */
public class TrackingEntryIterable<K, V, C> implements CloseableIterable<Map.Entry<K, C>> {
   protected final EntryRetriever<K, V> entryRetriever;
   protected final KeyValueFilter<? super K, ? super V> filter;
   protected final Converter<? super K, ? super V, ? extends C> converter;
   protected final AtomicBoolean closed = new AtomicBoolean(false);
   protected final Set<CloseableIterator<Map.Entry<K, C>>> iterators =
         new ConcurrentHashSet<CloseableIterator<Map.Entry<K, C>>>();

   public TrackingEntryIterable(EntryRetriever<K, V> retriever, KeyValueFilter<? super K, ? super V> filter,
                                 Converter<? super K, ? super V, ? extends C> converter) {
      if (retriever == null) {
         throw new NullPointerException("Retriever cannot be null!");
      }
      if (filter == null) {
         throw new NullPointerException("Filter cannot be null!");
      }
      this.entryRetriever = retriever;
      this.filter = filter;
      this.converter = converter;
   }

   @Override
   public void close() throws IOException {
      closed.set(true);
      for (CloseableIterator<Map.Entry<K, C>> iterator : iterators) {
         iterator.close();
      }
   }

   @Override
   public Iterator<Map.Entry<K, C>> iterator() {
      if (closed.get()) {
         throw new IllegalStateException("Iterable has been closed - cannot be reused");
      }
      CloseableIterator<Map.Entry<K, C>> iterator = entryRetriever.retrieveEntries(filter, converter, null);
      iterators.add(iterator);
      // Note we have to check if we were closed afterwards just in case if a concurrent close occurred.
      if (closed.get()) {
         // Rely on fact that multiple closes don't have adverse effects
         try {
            iterator.close();
         } catch (IOException e) {
            // This exception should never be thrown
         }
         throw new IllegalStateException("Iterable has been closed - cannot be reused");
      }
      return iterator;
   }
}
