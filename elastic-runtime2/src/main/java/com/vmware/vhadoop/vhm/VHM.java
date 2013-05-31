package com.vmware.vhadoop.vhm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.vmware.vhadoop.api.vhm.ClusterMap.ExtraInfoToClusterMapper;
import com.vmware.vhadoop.api.vhm.ClusterMapReader;
import com.vmware.vhadoop.api.vhm.ExecutionStrategy;
import com.vmware.vhadoop.api.vhm.VCActions;
import com.vmware.vhadoop.api.vhm.events.ClusterScaleCompletionEvent;
import com.vmware.vhadoop.api.vhm.events.ClusterScaleEvent;
import com.vmware.vhadoop.api.vhm.events.ClusterStateChangeEvent;
import com.vmware.vhadoop.api.vhm.events.EventConsumer;
import com.vmware.vhadoop.api.vhm.events.EventProducer;
import com.vmware.vhadoop.api.vhm.events.NotificationEvent;
import com.vmware.vhadoop.api.vhm.strategy.ScaleStrategy;
import com.vmware.vhadoop.util.ThreadLocalCompoundStatus;
import com.vmware.vhadoop.vhm.events.AbstractClusterScaleEvent;
import com.vmware.vhadoop.vhm.events.AbstractNotificationEvent;
import com.vmware.vhadoop.vhm.events.SerengetiLimitInstruction;
import com.vmware.vhadoop.vhm.strategy.ManualScaleStrategy;

public class VHM implements EventConsumer {
   private Set<EventProducer> _eventProducers;
   private Queue<NotificationEvent> _eventQueue;
   private boolean _initialized;
   private ClusterMapImpl _clusterMap;
   private ExecutionStrategy _executionStrategy;
   private VCActions _vcActions;
   private MultipleReaderSingleWriterClusterMapAccess _clusterMapAccess;
   private ClusterMapReader _parentClusterMapReader;
   private boolean _started = false;

   private static final Logger _log = Logger.getLogger(VHM.class.getName());

   public VHM(VCActions vcActions, ScaleStrategy[] scaleStrategies,
         ExtraInfoToClusterMapper strategyMapper, ThreadLocalCompoundStatus threadLocalStatus) {
      _eventProducers = new HashSet<EventProducer>();
      _eventQueue = new LinkedList<NotificationEvent>();
      _initialized = true;
      _clusterMap = new ClusterMapImpl(strategyMapper);
      _vcActions = vcActions;
      _clusterMapAccess = MultipleReaderSingleWriterClusterMapAccess.getClusterMapAccess(_clusterMap);
      _parentClusterMapReader = new AbstractClusterMapReader(_clusterMapAccess, threadLocalStatus) {};
      initScaleStrategies(scaleStrategies);
      _executionStrategy = new ThreadPoolExecutionStrategy();
      registerEventProducer((ThreadPoolExecutionStrategy)_executionStrategy);
   }

   private void initScaleStrategies(ScaleStrategy[] scaleStrategies) {
      for (ScaleStrategy strategy : scaleStrategies) {
         _clusterMap.registerScaleStrategy(strategy);
         strategy.initialize(_parentClusterMapReader);
      }
   }

   public void registerEventProducer(EventProducer eventProducer) {
      _eventProducers.add(eventProducer);
      eventProducer.registerEventConsumer(this);
      if (eventProducer instanceof ClusterMapReader) {
         ((ClusterMapReader)eventProducer).initialize(_parentClusterMapReader);
      }
      eventProducer.start();
   }

   private void addEventToQueue(NotificationEvent event) {
      Queue<NotificationEvent> toKeepQueue = null;
      if (event.getCanClearQueue()) {
         for (NotificationEvent e : _eventQueue) {
            if (!e.getCanBeClearedFromQueue()) {
               if (toKeepQueue == null) {
                  toKeepQueue = new LinkedList<NotificationEvent>();
               }
               toKeepQueue.add(e);
            }
         }
         _eventQueue.clear();
      }
      _eventQueue.add(event);
      if (toKeepQueue != null) {
         _eventQueue.addAll(toKeepQueue);
      }
   }

   /* This can be called by multiple threads */
   @Override
   public void placeEventOnQueue(NotificationEvent event) {
      if (!_initialized) {
         return;
      }
      if (event != null) {
         synchronized(_eventQueue) {
            addEventToQueue(event);
            _eventQueue.notify();
         }
      }
   }

   @Override
   public void placeEventCollectionOnQueue(List<? extends NotificationEvent> events) {
      if (!_initialized) {
         return;
      }
      synchronized(_eventQueue) {
         for (NotificationEvent event : events) {
            addEventToQueue(event);
         }
         _eventQueue.notify();
      }
   }

   public Set<NotificationEvent> pollForEvents() {
      HashSet<NotificationEvent> results = null;
      synchronized(_eventQueue) {
         while (_eventQueue.peek() == null) {
            try {
               _eventQueue.wait();
            } catch (InterruptedException e) {
               _log.warning("Interrupted unexpectedly while waiting for event");
            }
         }
         results = new HashSet<NotificationEvent>();
         while (_eventQueue.peek() != null) {
            /* Use of a Set ensured duplicates are eliminated */
            /* TODO: add an event key to do event consolidation. At the moment events use the default equality so this has little effect */
            results.add(_eventQueue.poll());
         }
      }
      return results;
   }

   public NotificationEvent getEventPending() {
      synchronized(_eventQueue) {
         return _eventQueue.peek();
      }
   }

   private String getClusterIdForVCFolder(String folderName) {
      List<String> vms = _vcActions.listVMsInFolder(folderName);
      String clusterId = _clusterMap.getClusterIdFromVMs(vms);
      _clusterMap.associateFolderWithCluster(clusterId, folderName);
      return clusterId;
   }

   /* TODO: Note that currently, this method cannot deal with a clusterScaleEvent with just a hostId
    * We should be able to deal with this at some point - ie: general host contention impacts multiple clusters */
   private String completeClusterScaleEventDetails(AbstractClusterScaleEvent event) {
      String clusterId = event.getClusterId();

      if (event instanceof SerengetiLimitInstruction) {
         try {
            clusterId = getClusterIdForVCFolder(((SerengetiLimitInstruction)event).getClusterFolderName());
         } catch (NullPointerException e) {
            clusterId = null;
         }
      }

      if (clusterId == null) {
         /* Find the clusterId from the VM */
         String hostId = event.getHostId();
         String vmId = event.getVmId();
         /* Find the host if it has not been provided */
         if (hostId == null) {
            if (vmId != null) {
               hostId = _clusterMap.getHostIdForVm(vmId);
               event.setHostId(hostId);
            }
         }
         if (vmId != null) {
            clusterId = _clusterMap.getClusterIdForVm(vmId);
         } else {
            _log.warning("No usable data from ClusterScaleEvent (" +
                  event.getVmId() + "," + event.getHostId() + "," + event.getClusterId() + ")");
            if (event instanceof SerengetiLimitInstruction) {
               SerengetiLimitInstruction sEvent = (SerengetiLimitInstruction)event;
               _log.warning("SerengetiEvent for cluster=" + sEvent.getClusterFolderName());
            }
            _clusterMap.dumpState(Level.WARNING);
         }
      }

      event.setClusterId(clusterId);
      return clusterId;
   }

   private void getQueuedScaleEventsForCluster(Set<NotificationEvent> events, Map<String, Set<ClusterScaleEvent>> results) {
      if (results != null) {
         for (NotificationEvent event : events) {
            if (event instanceof AbstractClusterScaleEvent) {
               String clusterId = completeClusterScaleEventDetails((AbstractClusterScaleEvent)event);
               if (clusterId != null) {
                  Set<ClusterScaleEvent> clusterScaleEvents = results.get(clusterId);
                  if (clusterScaleEvents == null) {
                     clusterScaleEvents = new HashSet<ClusterScaleEvent>();
                     results.put(clusterId, clusterScaleEvents);
                  }
                  clusterScaleEvents.add((ClusterScaleEvent)event);
               }
            }
         }
      }
   }

   private Set<ClusterStateChangeEvent> getClusterStateChangeEvents(Set<NotificationEvent> events) {
      Set<ClusterStateChangeEvent> results = new HashSet<ClusterStateChangeEvent>();
      for (NotificationEvent event : events) {
         if (event instanceof ClusterStateChangeEvent) {
            results.add((ClusterStateChangeEvent)event);
         }
      }
      return results;
   }

   private Set<ClusterScaleCompletionEvent> getClusterScaleCompletionEvents(Set<NotificationEvent> events) {
      Set<ClusterScaleCompletionEvent> results = new HashSet<ClusterScaleCompletionEvent>();
      for (NotificationEvent event : events) {
         if (event instanceof ClusterScaleCompletionEvent) {
            results.add((ClusterScaleCompletionEvent)event);
         }
      }
      return results;
   }

   /* For now, remove any events that the scale strategy is not designed to be able to handle */
   private Set<ClusterScaleEvent> consolidateClusterEvents(ScaleStrategy scaleStrategy, Set<ClusterScaleEvent> scaleEventsForCluster) {
      Set<ClusterScaleEvent> toRemove = null;
      for (ClusterScaleEvent event : scaleEventsForCluster) {
         boolean isAssignableFromAtLeastOne = false;
         for (Class<? extends ClusterScaleEvent> typeHandled : scaleStrategy.getScaleEventTypesHandled()) {
            if (typeHandled.isAssignableFrom(event.getClass())) {
               isAssignableFromAtLeastOne = true;
               break;
            }
         }
         if (!isAssignableFromAtLeastOne) {
            if (toRemove == null) {
               toRemove = new HashSet<ClusterScaleEvent>();
            }
            toRemove.add(event);
         }
      }
      if (toRemove != null) {
         int beforeSize = scaleEventsForCluster.size();
         scaleEventsForCluster.removeAll(toRemove);
         int afterSize = scaleEventsForCluster.size();
         _log.info("Consolidating scale events from "+beforeSize+" to "+afterSize+" for scaleStrategy "+scaleStrategy);
      }
      return scaleEventsForCluster;
   }

   private SerengetiLimitInstruction pendingBlockingSwitchToManual(Set<ClusterScaleEvent> consolidatedEvents) {
      for (ClusterScaleEvent clusterScaleEvent : consolidatedEvents) {
         if (clusterScaleEvent instanceof SerengetiLimitInstruction) {
            SerengetiLimitInstruction returnVal = (SerengetiLimitInstruction)clusterScaleEvent;
            if (returnVal.getAction().equals(SerengetiLimitInstruction.actionWaitForManual)) {
               return returnVal;
            }
         }
      }
      return null;
   }

   private void handleEvents(Set<NotificationEvent> events) {
      final Set<ClusterStateChangeEvent> clusterStateChangeEvents = getClusterStateChangeEvents(events);
      final Set<ClusterScaleCompletionEvent> completionEvents = getClusterScaleCompletionEvents(events);

      final Map<String, Set<ClusterScaleEvent>> clusterScaleEvents = new HashMap<String, Set<ClusterScaleEvent>>();

      /* Update ClusterMap first */
      if ((clusterStateChangeEvents.size() + completionEvents.size()) > 0) {
         _clusterMapAccess.runCodeInWriteLock(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
               Set<ClusterScaleEvent> impliedScaleEvents = new HashSet<ClusterScaleEvent>();
               for (ClusterStateChangeEvent event : clusterStateChangeEvents) {
                  _log.info("ClusterStateChangeEvent received: "+event.getClass().getName());
                  String clusterId = _clusterMap.handleClusterEvent(event, impliedScaleEvents);
                  if (clusterId != null) {
                     if (impliedScaleEvents.size() > 0) {
                        if (clusterScaleEvents.get(clusterId) == null) {
                           clusterScaleEvents.put(clusterId, impliedScaleEvents);
                           impliedScaleEvents = new HashSet<ClusterScaleEvent>();
                        } else {
                           clusterScaleEvents.get(clusterId).addAll(impliedScaleEvents);
                           impliedScaleEvents.clear();
                        }
                     }
                  }
               }
               for (ClusterScaleCompletionEvent event : completionEvents) {
                  _log.info("ClusterScaleCompletionEvent received: "+event.getClass().getName());
                  _clusterMap.handleCompletionEvent(event);
               }
               return null;
            }
         });
      }

      getQueuedScaleEventsForCluster(events, clusterScaleEvents);

      if (clusterScaleEvents.size() > 0) {
         for (String clusterId : clusterScaleEvents.keySet()) {
            ScaleStrategy scaleStrategy = _clusterMap.getScaleStrategyForCluster(clusterId);
            Set<ClusterScaleEvent> unconsolidatedEvents = clusterScaleEvents.get(clusterId);
            Set<ClusterScaleEvent> consolidatedEvents = consolidateClusterEvents(scaleStrategy, unconsolidatedEvents);
            if (consolidatedEvents.size() > 0) {
               if (scaleStrategy != null) {
                  /* If there is an instruction from Serengeti to switch to manual, strip out that one event and dump the others */
                  SerengetiLimitInstruction switchToManualEvent = pendingBlockingSwitchToManual(consolidatedEvents);
                  if (switchToManualEvent != null) {
                     /* If Serengeti has made the necessary change to extraInfo AND any other scaling has completed, inform completion */
                     boolean extraInfoChanged = _clusterMap.getScaleStrategyKey(clusterId).equals(ManualScaleStrategy.MANUAL_SCALE_STRATEGY_KEY);
                     boolean scalingCompleted = !_executionStrategy.isClusterScaleInProgress(clusterId);
                     if (extraInfoChanged && scalingCompleted) {
                        _log.info("Switch to manual scale strategy for cluster <%C"+clusterId+"%C> is now complete. Reporting back to Serengeti");
                        switchToManualEvent.reportCompletion();
                     } else {
                        /* Continue to block Serengeti CLI by putting the event back on the queue */
                        placeEventCollectionOnQueue(Arrays.asList(new ClusterScaleEvent[]{switchToManualEvent}));
                     }
                  } else if (!_executionStrategy.handleClusterScaleEvents(clusterId, scaleStrategy, consolidatedEvents)) {
                     /* If we couldn't schedule handling of the events, put them back on the queue in their un-consolidated form */
                     _log.finest("Putting event collection back onto VHM queue - size="+unconsolidatedEvents.size());
                     placeEventCollectionOnQueue(new ArrayList<ClusterScaleEvent>(unconsolidatedEvents));
                  }
               } else {
                  _log.severe("No scale strategy associated with cluster "+clusterId);
               }
            }
         }
      }
   }

   public Thread start() {
      _started = true;
      Thread t = new Thread(new Runnable() {
         @Override
         public void run() {
            try {
               while (_started) {
                  Set<NotificationEvent> events = pollForEvents();
                  handleEvents(events);
                  Thread.sleep(500);
               }
            } catch (Throwable e) {
               _log.log(Level.WARNING, "VHM stopping due to exception ", e);
            }
            _log.info("VHM stopping...");
         }}, "VHM_Main_Thread");
      t.start();
      return t;
   }

   public void stop(boolean hardStop) {
      _started = false;
      for (EventProducer eventProducer : _eventProducers) {
         eventProducer.stop();
      }
      placeEventOnQueue(new AbstractNotificationEvent(hardStop, false) {});
   }

   VCActions getVCActions() {
      return _vcActions;
   }

}
