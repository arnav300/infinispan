package org.infinispan.conflict.impl;

import static org.infinispan.factories.KnownComponentNames.STATE_TRANSFER_EXECUTOR;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.infinispan.AdvancedCache;
import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commands.remote.ClusteredGetCommand;
import org.infinispan.commons.CacheException;
import org.infinispan.commons.util.EnumUtil;
import org.infinispan.configuration.cache.PartitionHandlingConfiguration;
import org.infinispan.conflict.EntryMergePolicy;
import org.infinispan.conflict.EntryMergePolicyFactoryRegistry;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.InternalCacheValue;
import org.infinispan.container.entries.NullCacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.InvocationContextFactory;
import org.infinispan.context.impl.FlagBitSets;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.distribution.LocalizedCacheTopology;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.factories.annotations.Stop;
import org.infinispan.interceptors.AsyncInterceptorChain;
import org.infinispan.remoting.responses.CacheNotFoundResponse;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.responses.UnsureResponse;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.impl.MapResponseCollector;
import org.infinispan.statetransfer.StateConsumer;
import org.infinispan.topology.CacheTopology;
import org.infinispan.util.TimeService;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * @author Ryan Emerson
 */
public class DefaultConflictManager<K, V> implements InternalConflictManager<K, V> {

   private static Log log = LogFactory.getLog(DefaultConflictManager.class);
   private static boolean trace = log.isTraceEnabled();

   private static final long localFlags = EnumUtil.bitSetOf(Flag.CACHE_MODE_LOCAL, Flag.SKIP_OWNERSHIP_CHECK, Flag.SKIP_LOCKING);
   private static final Flag[] userMergeFlags = new Flag[] {Flag.IGNORE_RETURN_VALUES};
   private static final Flag[] autoMergeFlags = new Flag[] {Flag.IGNORE_RETURN_VALUES, Flag.PUT_FOR_STATE_TRANSFER};

   @Inject private AsyncInterceptorChain interceptorChain;
   @Inject private AdvancedCache<K, V> cache;
   @Inject private CommandsFactory commandsFactory;
   @Inject private DistributionManager distributionManager;
   @Inject @ComponentName(STATE_TRANSFER_EXECUTOR)
   private ExecutorService stateTransferExecutor;
   @Inject private InvocationContextFactory invocationContextFactory;
   @Inject private RpcManager rpcManager;
   @Inject private StateConsumer stateConsumer;
   @Inject private StateReceiver<K, V> stateReceiver;
   @Inject private EntryMergePolicyFactoryRegistry mergePolicyRegistry;
   @Inject private TimeService timeService;

   private String cacheName;
   private Address localAddress;
   private long conflictTimeout;
   private EntryMergePolicy<K, V> entryMergePolicy;
   private final AtomicBoolean streamInProgress = new AtomicBoolean();
   private final Map<K, VersionRequest> versionRequestMap = new HashMap<>();
   private final Queue<VersionRequest> retryQueue = new ConcurrentLinkedQueue<>();
   private volatile LocalizedCacheTopology installedTopology;
   private volatile boolean running = false;
   private volatile ReplicaSpliterator conflictSpliterator;

   @Start
   public void start() {
      this.cacheName = cache.getName();
      this.localAddress = rpcManager.getAddress();
      this.installedTopology = distributionManager.getCacheTopology();

      PartitionHandlingConfiguration config = cache.getCacheConfiguration().clustering().partitionHandling();
      this.entryMergePolicy = mergePolicyRegistry.createInstance(config);

      // TODO make this an explicit configuration param in PartitionHandlingConfiguration
      this.conflictTimeout = cache.getCacheConfiguration().clustering().stateTransfer().timeout();
      this.running = true;
      if (trace) log.tracef("Starting %s. isRunning=%s", this.getClass().getSimpleName(), !running);
   }

   @Stop(priority = 0)
   public void stop() {
      if (trace) log.tracef("Stopping %s", this.getClass().getSimpleName());
      this.running = false;
      synchronized (versionRequestMap) {
         if (trace) log.tracef("Stopping %s. isRunning=%s. %s", this.getClass().getSimpleName(), running, Arrays.toString(Thread.currentThread().getStackTrace()));
         cancelVersionRequests();
         versionRequestMap.clear();
      }

      if (isConflictResolutionInProgress() && conflictSpliterator != null)
         conflictSpliterator.stop();
   }

   @Override
   public void onTopologyUpdate(LocalizedCacheTopology cacheTopology) {
      if (!running)
         return;

      this.installedTopology = cacheTopology;
      if (trace) log.tracef("(isRunning=%s) Installed new topology %s: %s", running, cacheTopology.getTopologyId(), cacheTopology);
   }

   @Override
   public void cancelVersionRequests() {
      if (!running)
         return;

      synchronized (versionRequestMap) {
         versionRequestMap.values().forEach(VersionRequest::cancelRequestIfOutdated);
      }
   }

   @Override
   public void restartVersionRequests() {
      if (!running)
         return;

      VersionRequest request;
      while ((request = retryQueue.poll()) != null) {
         if (trace) log.tracef("Retrying %s", request);
         request.start();
      }
   }

   @Override
   public Map<Address, InternalCacheValue<V>> getAllVersions(final K key) {
      checkIsRunning();

      final VersionRequest request;
      synchronized (versionRequestMap) {
         request = versionRequestMap.computeIfAbsent(key, k -> new VersionRequest(k, stateConsumer.isStateTransferInProgress()));
      }

      try {
         return request.completableFuture.get();
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new CacheException(e);
      } catch (ExecutionException e) {
         if (e.getCause() instanceof CacheException)
            throw (CacheException) e.getCause();

         throw new CacheException(e.getCause());
      } finally {
         synchronized (versionRequestMap) {
            versionRequestMap.remove(key);
         }
      }
   }

   @Override
   public Stream<Map<Address, CacheEntry<K, V>>> getConflicts() {
      checkIsRunning();
      return getConflicts(installedTopology);
   }

   private Stream<Map<Address, CacheEntry<K, V>>> getConflicts(LocalizedCacheTopology topology) {
      if (topology.getPhase() != CacheTopology.Phase.CONFLICT_RESOLUTION && stateConsumer.isStateTransferInProgress())
         throw log.getConflictsStateTransferInProgress(cacheName);

      if (!streamInProgress.compareAndSet(false, true))
         throw log.getConflictsAlreadyInProgress();

      conflictSpliterator = new ReplicaSpliterator(topology);
      if (!running) {
         conflictSpliterator.stop();
         return Stream.empty();
      }
      return StreamSupport
            .stream(new ReplicaSpliterator(topology), false)
            .filter(filterConsistentEntries());
   }

   @Override
   public boolean isConflictResolutionInProgress() {
      return streamInProgress.get();
   }

   @Override
   public void resolveConflicts() {
      if (entryMergePolicy == null)
         throw new CacheException("Cannot resolve conflicts as no EntryMergePolicy has been configured");

      resolveConflicts(entryMergePolicy);
   }

   @Override
   public void resolveConflicts(EntryMergePolicy<K, V> mergePolicy) {
      checkIsRunning();
      doResolveConflicts(installedTopology, mergePolicy, true);
   }

   @Override
   public void resolveConflicts(CacheTopology topology) {
      if (!running)
         return;

      LocalizedCacheTopology localizedTopology;
      if (topology instanceof LocalizedCacheTopology) {
         localizedTopology = (LocalizedCacheTopology) topology;
      } else {
         localizedTopology = distributionManager.createLocalizedCacheTopology(topology);
      }
      doResolveConflicts(localizedTopology, entryMergePolicy, false);
   }

   private void doResolveConflicts(final LocalizedCacheTopology topology, final EntryMergePolicy<K, V> mergePolicy,
                                   final boolean userCall) {
      final Set<Address> preferredPartition = new HashSet<>(topology.getCurrentCH().getMembers());
      final AdvancedCache<K, V> cache = this.cache.withFlags(userCall ? userMergeFlags : autoMergeFlags);

      if (trace)
         log.tracef("Cache %s Attempting to resolve conflicts.  All Members %s, Installed topology %s, Preferred Partition %s",
               cacheName, topology.getMembers(), topology, preferredPartition);

      final Phaser phaser = new Phaser(1);
      getConflicts(topology).forEach(conflictMap -> {
         phaser.register();
         stateTransferExecutor.execute(() -> {
            if (trace) log.tracef("Cache %s Conflict detected %s", cacheName, conflictMap);

            Collection<CacheEntry<K, V>> entries = conflictMap.values();
            Optional<K> optionalEntry = entries.stream()
                  .filter(entry -> !(entry instanceof NullCacheEntry))
                  .map(CacheEntry::getKey)
                  .findAny();

            final K key = optionalEntry.orElseThrow(() -> new CacheException("All returned conflicts are NullCacheEntries. This should not happen!"));
            Address primaryReplica = topology.getDistribution(key).primary();

            List<Address> preferredEntries = conflictMap.entrySet().stream()
                  .map(Map.Entry::getKey)
                  .filter(preferredPartition::contains)
                  .collect(Collectors.toList());

            // If only one entry exists in the preferred partition, then use that entry
            CacheEntry<K, V> preferredEntry;
            if (preferredEntries.size() == 1) {
               preferredEntry = conflictMap.remove(preferredEntries.get(0));
            } else {
               // If multiple conflicts exist in the preferred partition, then use primary replica from the preferred partition
               // If not a merge, then also use primary as preferred entry
               // Preferred is null if no entry exists in preferred partition
               preferredEntry = conflictMap.remove(primaryReplica);
            }

            if (trace) log.tracef("Cache %s Applying EntryMergePolicy %s to PreferredEntry %s, otherEntries %s",
                  cacheName, mergePolicy.getClass().getName(), preferredEntry, entries);

            CacheEntry<K, V> entry = preferredEntry instanceof NullCacheEntry ? null : preferredEntry;
            List<CacheEntry<K, V>> otherEntries = entries.stream().filter(e -> !(e instanceof NullCacheEntry)).collect(Collectors.toList());
            CacheEntry<K, V> mergedEntry = mergePolicy.merge(entry, otherEntries);

            CompletableFuture<V> future;
            if (mergedEntry == null) {
               if (trace) log.tracef("Cache %s Executing remove on conflict: key %s", cacheName, key);
               future = cache.removeAsync(key);
            } else {
               if (trace) log.tracef("Cache %s Executing update on conflict: key %s with value %s", cacheName, key, mergedEntry.getValue());
               future = cache.putAsync(key, mergedEntry.getValue(), mergedEntry.getMetadata());
            }
            future.whenComplete((responseMap, exception) -> {
               if (trace) log.tracef("Cache %s ResolveConflicts future complete for key %s: ResponseMap=%s, Exception=%s",
                     cacheName, key, responseMap, exception);

               phaser.arriveAndDeregister();
               if (exception != null)
                  log.exceptionDuringConflictResolution(key, exception);
            });
         });
      });
      phaser.arriveAndAwaitAdvance();

      if (trace) log.tracef("Cache %s Finished resolving conflicts for topologyId=%s", cacheName,  topology.getTopologyId());
   }

   @Override
   public boolean isStateTransferInProgress() {
      return stateConsumer.isStateTransferInProgress();
   }

   private void checkIsRunning() {
      if (!running)
         throw new CacheException(String.format("Cache %s Unable to process request as the ConflictManager has been stopped", cacheName));
   }

   private class VersionRequest {
      final K key;
      final boolean postpone;
      final CompletableFuture<Map<Address, InternalCacheValue<V>>> completableFuture = new CompletableFuture<>();
      volatile CompletableFuture<Map<Address, Response>> rpcFuture;
      volatile Collection<Address> keyOwners;

      VersionRequest(K key, boolean postpone) {
         this.key = key;
         this.postpone = postpone;

         if (trace) log.tracef("Cache %s Creating %s", cacheName,this);

         if (postpone) {
            retryQueue.add(this);
         } else {
            start();
         }
      }

      void cancelRequestIfOutdated() {
         Collection<Address> latestOwners = installedTopology.getWriteOwners(key);
         if (rpcFuture != null && !completableFuture.isDone() && !keyOwners.equals(latestOwners)) {
            rpcFuture = null;
            keyOwners.clear();
            if (rpcFuture.cancel(false)) {
               retryQueue.add(this);

               if (trace) log.tracef("Cancelling %s for nodes %s. New write owners %s", this, keyOwners, latestOwners);
            }
         }
      }

      void start() {
         LocalizedCacheTopology topology = installedTopology;
         keyOwners = topology.getWriteOwners(key);

         if (trace) log.tracef("Attempting %s from owners %s", this, keyOwners);

         final Map<Address, InternalCacheValue<V>> versionsMap = new HashMap<>();
         if (keyOwners.contains(localAddress)) {
            GetCacheEntryCommand cmd = commandsFactory.buildGetCacheEntryCommand(key, localFlags);
            InvocationContext ctx = invocationContextFactory.createNonTxInvocationContext();
            InternalCacheEntry<K, V> internalCacheEntry = (InternalCacheEntry<K, V>) interceptorChain.invoke(ctx, cmd);
            InternalCacheValue<V> icv = internalCacheEntry == null ? null : internalCacheEntry.toInternalCacheValue();
            synchronized (versionsMap) {
               versionsMap.put(localAddress, icv);
            }
         }

         ClusteredGetCommand cmd = commandsFactory.buildClusteredGetCommand(key, FlagBitSets.SKIP_OWNERSHIP_CHECK);
         cmd.setTopologyId(topology.getTopologyId());
         MapResponseCollector collector = MapResponseCollector.ignoreLeavers(keyOwners.size());
         rpcFuture = rpcManager.invokeCommand(keyOwners, cmd, collector, rpcManager.getSyncRpcOptions()).toCompletableFuture();
         rpcFuture.whenComplete((responseMap, exception) -> {
            if (trace) log.tracef("%s received responseMap %s, exception %s", this, responseMap, exception);

            if (exception != null) {
               String msg = String.format("%s encountered when attempting '%s' on cache '%s'", exception.getCause(), this, cacheName);
               completableFuture.completeExceptionally(new CacheException(msg, exception.getCause()));
               return;
            }

            for (Map.Entry<Address, Response> entry : responseMap.entrySet()) {
               if (trace) log.tracef("%s received response %s from %s", this, entry.getValue(), entry.getKey());
               Response rsp = entry.getValue();
               if (rsp instanceof SuccessfulResponse) {
                  SuccessfulResponse response = (SuccessfulResponse) rsp;
                  Object rspVal = response.getResponseValue();
                  synchronized (versionsMap) {
                     versionsMap.put(entry.getKey(), (InternalCacheValue<V>) rspVal);
                  }
               } else if(rsp instanceof UnsureResponse) {
                  log.debugf("Received UnsureResponse, restarting request %s", this);
                  this.start();
                  return;
               } else if (rsp instanceof CacheNotFoundResponse) {
                  if (trace) log.tracef("Ignoring CacheNotFoundResponse: %s", rsp);
               } else {
                  completableFuture.completeExceptionally(new CacheException(String.format("Unable to retrieve key %s from %s: %s", key, entry.getKey(), entry.getValue())));
                  return;
               }
            }
            completableFuture.complete(versionsMap);
         });
      }

      @Override
      public String toString() {
         return "VersionRequest{" +
               "key=" + key +
               ", postpone=" + postpone +
               '}';
      }
   }

   private Predicate<? super Map<Address, CacheEntry<K, V>>> filterConsistentEntries() {
      return map -> map.values().stream().distinct().limit(2).count() > 1 || map.values().isEmpty();
   }

   private class ReplicaSpliterator extends Spliterators.AbstractSpliterator<Map<Address, CacheEntry<K, V>>> {
      private final LocalizedCacheTopology topology;
      private final int totalSegments;
      private final long endTime;
      private int nextSegment = 0;
      private Iterator<Map<Address, CacheEntry<K, V>>> iterator = Collections.emptyIterator();
      private volatile CompletableFuture<List<Map<Address, CacheEntry<K, V>>>> segmentRequestFuture;

      ReplicaSpliterator(LocalizedCacheTopology topology) {
         super(Long.MAX_VALUE, DISTINCT | NONNULL);
         this.topology = topology;
         this.totalSegments = topology.getWriteConsistentHash().getNumSegments();
         this.endTime = timeService.expectedEndTime(conflictTimeout, TimeUnit.MILLISECONDS);
      }


      @Override
      public boolean tryAdvance(Consumer<? super Map<Address, CacheEntry<K, V>>> action) {
         while (!iterator.hasNext()) {
            if (nextSegment < totalSegments) {
               try {
                  if (trace)
                     log.tracef("Attempting to receive all replicas for segment %s with topology %s", nextSegment, topology);
                  segmentRequestFuture = stateReceiver.getAllReplicasForSegment(nextSegment, topology);
                  long remainingTime = timeService.remainingTime(endTime, TimeUnit.MILLISECONDS);
                  List<Map<Address, CacheEntry<K, V>>> segmentEntries = segmentRequestFuture.get(remainingTime, TimeUnit.MILLISECONDS);
                  if (trace && !segmentEntries.isEmpty())
                     log.tracef("Segment %s entries received: %s", nextSegment, segmentEntries);
                  nextSegment++;
                  iterator = segmentEntries.iterator();
               } catch (CancellationException e) {
                  if (trace) log.tracef("ReplicaSpliterator caught %s", e);
                  streamInProgress.set(false);
                  return false;
               } catch (InterruptedException e) {
                  if (trace) log.tracef("ReplicaSpliterator caught %s", e);
                  stateReceiver.stop();
                  streamInProgress.set(false);
                  Thread.currentThread().interrupt();
                  throw new CacheException(e);
               } catch (ExecutionException | TimeoutException e) {
                  if (trace) log.tracef("ReplicaSpliterator caught %s", e);
                  streamInProgress.set(false);
                  throw new CacheException(e.getMessage(), e.getCause());
               }
            } else {
               streamInProgress.compareAndSet(true, false);
               return false;
            }
         }
         action.accept(iterator.next());
         return true;
      }

      void stop() {
         if (trace) log.tracef("Stop called on ReplicaSpliterator. Current segment %s", nextSegment);
         if (segmentRequestFuture != null)
            segmentRequestFuture.cancel(true);
      }
   }
}
