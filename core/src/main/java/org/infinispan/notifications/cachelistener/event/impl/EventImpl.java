package org.infinispan.notifications.cachelistener.event.impl;

import net.jcip.annotations.NotThreadSafe;

import org.infinispan.Cache;
import org.infinispan.commons.util.InfinispanCollections;
import org.infinispan.commons.util.Util;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.marshall.core.MarshalledValue;
import org.infinispan.metadata.Metadata;
import org.infinispan.notifications.cachelistener.event.CacheEntriesEvictedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryActivatedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryEvictedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryInvalidatedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryLoadedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryModifiedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryPassivatedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryRemovedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryVisitedEvent;
import org.infinispan.notifications.cachelistener.event.DataRehashedEvent;
import org.infinispan.notifications.cachelistener.event.TopologyChangedEvent;
import org.infinispan.notifications.cachelistener.event.TransactionCompletedEvent;
import org.infinispan.notifications.cachelistener.event.TransactionRegisteredEvent;
import org.infinispan.remoting.transport.Address;
import org.infinispan.transaction.xa.GlobalTransaction;

import java.util.Collection;
import java.util.Map;

/**
 * Basic implementation of an event that covers all event types.
 *
 * @author Manik Surtani
 * @since 4.0
 */
@NotThreadSafe
public class EventImpl<K, V> implements CacheEntryActivatedEvent, CacheEntryCreatedEvent, CacheEntriesEvictedEvent, CacheEntryLoadedEvent, CacheEntryModifiedEvent,
                                        CacheEntryPassivatedEvent, CacheEntryRemovedEvent, CacheEntryVisitedEvent, TransactionCompletedEvent, TransactionRegisteredEvent,
                                  CacheEntryInvalidatedEvent, DataRehashedEvent, TopologyChangedEvent, CacheEntryEvictedEvent {
   private boolean pre = false; // by default events are after the fact
   private transient Cache<K, V> cache;
   private K key;
   private GlobalTransaction transaction;
   private Metadata metadata;
   private boolean originLocal = true; // by default events all originate locally
   private boolean transactionSuccessful;
   private Type type;
   private V value;
   private V oldValue;
   private ConsistentHash consistentHashAtStart, consistentHashAtEnd, unionConsistentHash;
   private int newTopologyId;
   private Map<? extends K, ? extends V> entries;
   private boolean created;
   private boolean commandRetried;

   public EventImpl() {
   }

   public static <K, V> EventImpl<K, V> createEvent(Cache<K, V> cache, Type type) {
      EventImpl<K, V> e = new EventImpl<K,V>();
      e.cache = cache;
      e.type = type;
      return e;
   }

   @Override
   public Type getType() {
      return type;
   }

   @Override
   public boolean isPre() {
      return pre;
   }

   @Override
   public Cache<K, V> getCache() {
      return cache;
   }

   @Override
   @SuppressWarnings("unchecked")
   public K getKey() {
      if (key instanceof MarshalledValue)
         key = (K) ((MarshalledValue) key).get();
      return key;
   }

   @Override
   public GlobalTransaction getGlobalTransaction() {
      return this.transaction;
   }

   @Override
   public boolean isOriginLocal() {
      return originLocal;
   }

   @Override
   public boolean isTransactionSuccessful() {
      return transactionSuccessful;
   }

   // ------------------------------ setters -----------------------------

   public void setPre(boolean pre) {
      this.pre = pre;
   }

   public void setKey(K key) {
      this.key = key;
   }

   public void setTransactionId(GlobalTransaction transaction) {
      this.transaction = transaction;
   }

   public void setOriginLocal(boolean originLocal) {
      this.originLocal = originLocal;
   }

   public void setTransactionSuccessful(boolean transactionSuccessful) {
      this.transactionSuccessful = transactionSuccessful;
   }

   public void setConsistentHashAtStart(ConsistentHash consistentHashAtStart) {
      this.consistentHashAtStart = consistentHashAtStart;
   }

   public void setConsistentHashAtEnd(ConsistentHash consistentHashAtEnd) {
      this.consistentHashAtEnd = consistentHashAtEnd;
   }

   public void setUnionConsistentHash(ConsistentHash unionConsistentHash) {
      this.unionConsistentHash = unionConsistentHash;
   }

   public void setNewTopologyId(int newTopologyId) {
      this.newTopologyId = newTopologyId;
   }

   public void setMetadata(Metadata metadata) {
      this.metadata = metadata;
   }

   public Metadata getMetadata() {
      return metadata;
   }

   @Override
   @SuppressWarnings("unchecked")
   public V getValue() {
      if (value instanceof MarshalledValue)
         value = (V) ((MarshalledValue) value).get();
      return value;
   }

   public void setCommandRetried(boolean commandRetried) {
      this.commandRetried = commandRetried;
   }

   @Override
   public boolean isCommandRetried() {
      return commandRetried;
   }

   @Override
   public boolean isCreated() {
      return created;
   }

   @Override
   public Object getOldValue() {
      return oldValue;
   }

   public void setValue(V value) {
      this.value = value;
   }

   public void setEntries(Map<? extends K, ? extends V> entries) {
      this.entries = entries;
   }

   public void setCreated(boolean created) {
      this.created = created;
   }

   public void setOldValue(V oldValue) {
      this.oldValue = oldValue;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      EventImpl<?, ?> event = (EventImpl<?, ?>) o;

      if (originLocal != event.originLocal) return false;
      if (pre != event.pre) return false;
      if (transactionSuccessful != event.transactionSuccessful) return false;
      if (cache != null ? !cache.equals(event.cache) : event.cache != null) return false;
      if (key != null ? !key.equals(event.key) : event.key != null) return false;
      if (transaction != null ? !transaction.equals(event.transaction) : event.transaction != null) return false;
      if (type != event.type) return false;
      if (value != null ? !value.equals(event.value) : event.value != null) return false;
      if (!Util.safeEquals(consistentHashAtStart, event.consistentHashAtStart)) return false;
      if (!Util.safeEquals(consistentHashAtEnd, event.consistentHashAtEnd)) return false;
      if (!Util.safeEquals(unionConsistentHash, event.unionConsistentHash)) return false;
      if (newTopologyId != event.newTopologyId) return false;
      if (created != event.created) return false;
      if (oldValue != null ? !oldValue.equals(event.oldValue) : event.oldValue != null) return false;

      return true;
   }

   @Override
   public int hashCode() {
      int result = (pre ? 1 : 0);
      result = 31 * result + (cache != null ? cache.hashCode() : 0);
      result = 31 * result + (key != null ? key.hashCode() : 0);
      result = 31 * result + (transaction != null ? transaction.hashCode() : 0);
      result = 31 * result + (originLocal ? 1 : 0);
      result = 31 * result + (transactionSuccessful ? 1 : 0);
      result = 31 * result + (type != null ? type.hashCode() : 0);
      result = 31 * result + (value != null ? value.hashCode() : 0);
      result = 31 * result + (consistentHashAtStart != null ? consistentHashAtStart.hashCode() : 0);
      result = 31 * result + (consistentHashAtEnd != null ? consistentHashAtEnd.hashCode() : 0);
      result = 31 * result + (unionConsistentHash != null ? unionConsistentHash.hashCode() : 0);
      result = 31 * result + newTopologyId;
      result = 31 * result + (created ? 1 : 0);
      result = 31 * result + (oldValue != null ? oldValue.hashCode() : 0);
      return result;
   }

   @Override
   public String toString() {
      if (type == Type.TOPOLOGY_CHANGED || type == Type.DATA_REHASHED)
         return "EventImpl{" +
               "type=" + type +
               ", pre=" + pre +
               ", cache=" + cache +
               ", consistentHashAtStart=" + consistentHashAtStart +
               ", consistentHashAtEnd=" + consistentHashAtEnd +
               ", unionConsistentHash=" + unionConsistentHash +
               ", newTopologyId=" + newTopologyId +
               '}';
      return "EventImpl{" +
            "type=" + type +
            ", pre=" + pre +
            ", cache=" + cache +
            ", key=" + key +
            ", value=" + value +
            ", oldValue=" + oldValue +
            ", transaction=" + transaction +
            ", originLocal=" + originLocal +
            ", transactionSuccessful=" + transactionSuccessful +
            ", entries=" + entries +
            ", created=" + created +
            '}';
   }

   @Override
   public Collection<Address> getMembersAtStart() {
      return consistentHashAtStart != null ? consistentHashAtStart.getMembers() : InfinispanCollections.<Address>emptySet();
   }

   @Override
   public Collection<Address> getMembersAtEnd() {
      return consistentHashAtEnd != null ? consistentHashAtEnd.getMembers() : InfinispanCollections.<Address>emptySet();
   }

   @Override
   public ConsistentHash getConsistentHashAtStart() {
      return consistentHashAtStart;
   }

   @Override
   public ConsistentHash getConsistentHashAtEnd() {
      return consistentHashAtEnd;
   }

   @Override
   public ConsistentHash getUnionConsistentHash() {
      return unionConsistentHash;
   }

   @Override
   public int getNewTopologyId() {
      return newTopologyId;
   }

   @Override
   @SuppressWarnings("unchecked")
   public Map<? extends K, ? extends V> getEntries() {
      return entries;
   }
}
