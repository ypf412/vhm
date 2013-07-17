package com.vmware.vhadoop.vhm.model.scenarios;

import static com.vmware.vhadoop.vhm.model.api.ResourceType.CPU;
import static com.vmware.vhadoop.vhm.model.api.ResourceType.MEMORY;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.vmware.vhadoop.api.vhm.events.EventConsumer;
import com.vmware.vhadoop.api.vhm.events.EventProducer;
import com.vmware.vhadoop.vhm.events.SerengetiLimitInstruction;
import com.vmware.vhadoop.vhm.model.api.Allocation;
import com.vmware.vhadoop.vhm.model.api.Workload;
import com.vmware.vhadoop.vhm.model.hadoop.HadoopJob;
import com.vmware.vhadoop.vhm.model.os.Linux;
import com.vmware.vhadoop.vhm.model.os.Process;
import com.vmware.vhadoop.vhm.model.vcenter.Folder;
import com.vmware.vhadoop.vhm.model.vcenter.Host;
import com.vmware.vhadoop.vhm.model.vcenter.OVA;
import com.vmware.vhadoop.vhm.model.vcenter.ResourcePool;
import com.vmware.vhadoop.vhm.model.vcenter.VM;
import com.vmware.vhadoop.vhm.model.vcenter.VirtualCenter;
import com.vmware.vhadoop.vhm.rabbit.ModelRabbitAdaptor.RabbitConnectionCallback;
import com.vmware.vhadoop.vhm.rabbit.VHMJsonReturnMessage;


public class Serengeti extends Folder implements EventProducer
{
   public static final int UNSET = -1;
   final static String COMPUTE_SUBDOMAIN = "compute";
   final static String ROUTEKEY_SEPARATOR = ":";
   public static final String UNKNOWN_HOSTNAME_FOR_COMPUTE_NODE = "unknown hostname for compute node";
   public static final String COMPUTE_NODE_IN_UNDETERMINED_STATE = "compute node was neither enabled or disabled";
   public static final String COMPUTE_NODE_ALREADY_IN_TARGET_STATE = "compute node was already in the target state";

   /** The frequency with which we want to check the state of the world, unprompted. milliseconds */
   long maxLatency = 5000;

   private static Logger _log = Logger.getLogger(Serengeti.class.getName());

   /** default number of standard cpus for compute nodes */
   long defaultCpus = 2;
   /** default memory for compute nodes in Mb */
   long defaultMem = 2 * 1024;

   VirtualCenter vCenter;

   Map<String,Master> clusters = new HashMap<String,Master>();
   Map<String,Folder> folders = new HashMap<String,Folder>();

   /** This is a record of whether VHM has asked us, as an event producer, to stop */
   boolean _stopped = false;
   EventProducerStoppingCallback _callback;
   EventConsumer eventConsumer;

   /**
    * Creates a "Serengeti" and adds it to the specified Orchestrator
    * @param id
    * @param orchestrator
    */
   public Serengeti(String id, VirtualCenter vCenter) {
      super(id);

      this.vCenter = vCenter;
      vCenter.add(this);
   }

   public VirtualCenter getVCenter() {
      return vCenter;
   }

   /**
    * Specifies how frequently the cluster Master should wake up and inspect the state of the world.
    * It may be prompted to take action by events more frequently than this limit
    * @param millis latency in milliseconds
    */
   public void setMaxLatency(long millis) {
      maxLatency = millis;
   }

   public long getMaxLatency() {
      return maxLatency;
   }

   public Master createCluster(String name, MasterTemplate template) {
      Allocation capacity = com.vmware.vhadoop.vhm.model.Allocation.zeroed();
      capacity.set(CPU, defaultCpus * vCenter.getCpuSpeed());
      capacity.set(MEMORY, defaultMem * 2);

      Master master = (Master) vCenter.createVM(name, capacity, template, this);
      Folder folder = new Folder(name);
      add(folder);
      folder.add(master);
      vCenter.add(folder);

      clusters.put(master.getId(), master);
      folders.put(master.getId(), folder);
      return master;
   }

   static String constructHostnameForCompute(Master master, String computeId) {
      return computeId+"."+COMPUTE_SUBDOMAIN+"."+master.clusterName;
   }

   static String getComputeIdFromHostname(String hostname) {
      int index = hostname.indexOf("."+COMPUTE_SUBDOMAIN+".");
      if (index == -1) {
         return null;
      }

      return hostname.substring(0, index);
   }

   public void generateLimitInstruction(String clusterId, String id, String actionsettarget, int targetComputeNodeNum) {
      Folder folder = folders.get(clusterId);
      if (folder != null) {
         eventConsumer.placeEventOnQueue(new SerengetiLimitInstruction(folder.name(), SerengetiLimitInstruction.actionSetTarget, targetComputeNodeNum, new RabbitConnectionCallback(packRouteKey(id, clusterId), Serengeti.this)));
      } else {
         _log.severe(name()+": expected to have a folder associated with cluster "+clusterId+", unable to send limit instruction");
      }
   }

   public static VHMJsonReturnMessage unpackRawPayload(byte[] json) {
      Gson gson = new Gson();
      return gson.fromJson(new String(json), VHMJsonReturnMessage.class);
   }

   static String packRouteKey(String msgId, String clusterId) {
      return msgId + ROUTEKEY_SEPARATOR + clusterId;
   }

   static String[] unpackRouteKey(String routeKey) {
      if (routeKey == null) {
         return null;
      }

      int index = routeKey.indexOf(ROUTEKEY_SEPARATOR);
      if (index == -1) {
         return null;
      }

      String id = routeKey.substring(0, index);
      String remainder = null;
      if (index != routeKey.length() - 1) {
         remainder = routeKey.substring(index+1);
      }

      return new String[] {id, remainder};
   }


   /**
    * Allows VHM to send 'RabbitMQ' messages to this serengeti detailing the results of actions
    * The message is passed along to the master in question
    * @param data
    */
   public void deliverMessage(byte[] data) {
      deliverMessage(null, data);
   }

   /**
    * Allows VHM to send 'RabbitMQ' messages to this serengeti detailing the results of actions
    * The message is passed along to the master in question
    * @param routeKey
    * @param data
    */
   public void deliverMessage(String routeKey, byte[] data) {
      _log.info(name()+" received message on route '"+routeKey+"': "+new String(data));

      VHMJsonReturnMessage msg = unpackRawPayload(data);
      String parts[] = unpackRouteKey(routeKey);
      if (parts == null || parts.length < 2) {
         _log.severe(name()+": received message reply without a destination cluster: "+routeKey);
         return;
      }

      Master master = clusters.get(parts[1]);
      if (master == null) {
         _log.severe(name()+": received message with unknown destination cluster: "+routeKey);
         return;
      }

      master.deliverMessage(parts[0], msg);
   }



   /***************** Serengeti event producer methods *****************************************************
    * Serengeti model is an event producer solely so that we can put events directly onto the VHM queue and
    * receive responses.
    */
   @Override
   public void start(EventProducerStoppingCallback callback) {
      _callback = callback;
      _stopped = false;
   }

   /**
    * Implements EventConsumer.stop()
    */
   @Override
   public void stop() {
      if (_callback != null && _stopped == false) {
         _callback.notifyStopping(this, false);
      }

      _stopped = true;
   }


   @Override
   public boolean isStopped() {
      return _stopped;
   }

   @Override
   public void registerEventConsumer(EventConsumer vhm) {
      eventConsumer = vhm;

      /* ensure that we're meeting our minimums for all clusters */
      for (Master master : clusters.values()) {
         master.applyTarget();
      }
   }
   /***************** Serengeti event producer methods - end *****************************************************/




   /***************** Compute Node start *****************************************************/
   static public class ComputeTemplate implements OVA<Compute> {
      /**
       * Creates a compute VM from the specified template. The variable data should be a Master VM from the corresponding
       * Master template
       */
      @Override
      public Compute create(VirtualCenter vCenter, String id, Allocation capacity, Object data) {
         Master master = (Master)data;
         Compute compute = new Compute(vCenter, master, id, capacity);
         compute.install(new Linux("Linux-"+id));
         compute.setHostname(Serengeti.constructHostnameForCompute(master, id));

         specialize(compute, master);

         return compute;
      }

      /**
       * Specializes the Serengeti compute node for a given deployment
       * @param master
       * @param data
       */
      protected void specialize(Compute compute, Master master) {
      }
   }

   static public class Compute extends VM
   {
      Master master;

      Compute(VirtualCenter vCenter, Master master, String id, Allocation capacity) {
         super(vCenter, id, capacity);
         this.master = master;
         setExtraInfo("vhmInfo.elastic", "true");
         setExtraInfo("vhmInfo.masterVM.uuid", master.getClusterId());
         setExtraInfo("vhmInfo.masterVM.moid", master.getId());

         _log.info(master.clusterId+": created cluster compute node ("+id+")");
      }

      public void execute(Process process) {
         _log.info(name()+": executing process "+process.name());
         getOS().exec(process);
      }

      /**
       * We over-ride remove so that we can inform the master that a task is done
       */
      @Override
      public Allocation remove(Workload workload) {
         if (workload instanceof HadoopJob.Task) {
            this.master.reportEndOfTask((Process)workload);
         }

         return super.remove(workload);
      }
   }
   /***************** Compute Node end *****************************************************/




   /***************** Master Node start *****************************************************/
   public static class MasterTemplate implements OVA<Master> {
      /**
       * Creates the basic VM and serengeti deployment. The data in this case is the parent
       * Serengeti instance.
       */
      @Override
      public Master create(VirtualCenter vCenter, String id, Allocation capacity, Object data) {
         Serengeti serengeti = (Serengeti)data;
         Master master = serengeti.new Master(vCenter, id, capacity);
         master.install(new Linux("Linux"));
         master.setHostname("master."+master.clusterName);

         specialize(master, serengeti);

         return master;
      }

      /**
       * Specializes the Serengeti cluster master for a given deployment
       * @param master
       * @param data
       */
      protected void specialize(Master master, Serengeti serengeti) {
      }
   }

   /**
    * This class represents the master VM of a cluster
    * @author ghicken
    *
    */
   public class Master extends VM
   {
      String clusterName;
      String clusterId;
      int computeNodesId = 0;
      int msgId = 0;
      final Set<Compute> computeNodes = new HashSet<Compute>();
      final Map<String,Compute> enabled = new HashMap<String,Compute>();
      final Map<String,Compute> disabled = new HashMap<String,Compute>();
      ResourcePool computePool;
      int targetComputeNodeNum = UNSET;
      ComputeTemplate computeOVA = new ComputeTemplate();
      final Map<String,VHMJsonReturnMessage> messages = new HashMap<String,VHMJsonReturnMessage>();

      public String getClusterId() {
         return clusterId;
      }

      protected Master(VirtualCenter vCenter, String cluster, Allocation capacity) {
         super(vCenter, cluster+"-master", capacity);
         clusterName = cluster;
         clusterId = getId();
         setExtraInfo("vhmInfo.masterVM.uuid", clusterId);
         setExtraInfo("vhmInfo.masterVM.moid", clusterId); /* I don't know if uuid and moid have to be the same, but it works if they are */
         setExtraInfo("vhmInfo.elastic", "false");

         /* serengeti.uuid is the folder id for the cluster. This must contain at least one VM from the cluster or we can't
          * correlate limit instructions with clusters. If not set here it will be discovered based on the cluster name passed
          * by the limit instruction
          */
         setExtraInfo("vhmInfo.serengeti.uuid", "");

         /* these two are necessary for manual mode to work, even though they aren't applicable */
         setExtraInfo("vhmInfo.vhm.enable", "false");
         setExtraInfo("vhmInfo.min.computeNodeNum", "0");

         setTargetComputeNodeNum(targetComputeNodeNum);

         _log.info(clusterId+": created cluster master ("+getId()+")");
      }


      /**
       * Allows VHM to send 'RabbitMQ' messages to this master detailing the results of actions
       * @param data
       */
      public void deliverMessage(String msgId, VHMJsonReturnMessage msg) {
         _log.info(name()+": received message, id: "+msgId+
                                       ", finished: "+msg.finished+
                                       ", succeeded: "+msg.succeed+
                                       ", progress: "+msg.progress+
                                       ", error_code: "+msg.error_code+
                                       ", error_msg: "+msg.error_msg+
                                       ", progress_msg: "+msg.progress_msg);

         synchronized(messages) {
            messages.put(msgId, msg);
            messages.notifyAll();
         }
      }

      /**
       * This waits for a response message from VHM with the given id. Currently this only notifies
       * when the completion message arrives, but logs the arrival of progress updates.
       *
       * If the wait times out then the most recent response matching the ID will be returned, or null
       * if none have been seen.
       *
       * @param id
       * @param timeout
       * @return
       */
      public VHMJsonReturnMessage waitForResponse(String id, long timeout) {
         long deadline = System.currentTimeMillis() + timeout;
         long remaining = timeout;
         int progress = -1;

         synchronized(messages) {
            VHMJsonReturnMessage response = messages.get(id);
            try {
               while ((response == null || !response.finished) && remaining > 0) {
                  messages.wait(remaining);
                  remaining = deadline - System.currentTimeMillis();
                  response = messages.get(id);
                  if (response != null && response.progress != progress) {
                     _log.info(name()+": received update for interaction "+id+", progress: "+progress);
                     progress = response.progress;
                  }
               }
            } catch (InterruptedException e) {}

            return response;
         }
      }


      /**
       * ensure that we're meeting our obligation for compute nodes
       * @return the msgId for the message under which we will see replies
       */
      protected String applyTarget() {
         if (eventConsumer == null) {
            return null;
         }

         if (targetComputeNodeNum == UNSET) {
            return null;
         }

         _log.info(clusterId+": dispatching SerengetiLimitInstruction ("+targetComputeNodeNum+")");
         String id = Integer.toString(msgId++);
         generateLimitInstruction(clusterId, id, SerengetiLimitInstruction.actionSetTarget, targetComputeNodeNum);

         return id;
      }

      /**
       * Sets the target compute node number for the cluster
       * @param target the number of nodes we want
       * @return the id of the interaction for retrieving responses, null if the command could not be dispatched
       */
      public String setTargetComputeNodeNum(int target) {
         if (targetComputeNodeNum != target) {
            targetComputeNodeNum = target;
            return applyTarget();
         }

         return null;
      }

      public int numberComputeNodesInPowerState(boolean power) {
         int nodes = 0;
         long timestamp = this.vCenter.getConfigurationTimestamp();
         long timestamp2 = 0;

         while (timestamp != timestamp2) {
            synchronized(computeNodes) {
               for (Compute compute : computeNodes) {
                  if (compute.powerState() == power) {
                     nodes++;
                  }
               }
            }
            /* check to see if state changed under our accounting */
            timestamp2 = vCenter.getConfigurationTimestamp();
         }

         return nodes;
      }

      public Set<Compute> getComputeNodesInPowerState(boolean power) {
         Set<Compute> compute = new HashSet<Compute>();
         long timestamp = vCenter.getConfigurationTimestamp();
         long timestamp2 = 0;

         while (timestamp != timestamp2) {
            synchronized(computeNodes) {
               for (Compute node : computeNodes) {
                  if (node.powerState() == power) {
                     compute.add(node);
                  }
               }
            }
            /* check to see if state changed under our accounting */
            timestamp2 = vCenter.getConfigurationTimestamp();
         }

         return compute;
      }

      public int numberComputeNodesInState(boolean enabled) {
         return this.enabled.size();
      }

      public synchronized Collection<Compute> getComputeNodesInState(boolean enabled) {
         if (enabled) {
            return new HashSet<Compute>(this.enabled.values());
         }

         return new HashSet<Compute>(this.disabled.values());
      }

      public Set<Compute> getComputeNodes() {
         synchronized(computeNodes) {
            return new HashSet<Compute>(computeNodes);
         }
      }

      public int availableComputeNodes() {
         return computeNodes.size();
      }

      public void setComputeNodeTemplate(ComputeTemplate template) {
         this.computeOVA = template;
      }

      public Compute[] createComputeNodes(int num, Host host) {
         Folder folder = folders.get(clusterId);
         if (folder == null) {
            _log.severe(name()+": unable to get folder for cluster "+clusterId+", unable to create compute nodes");
            return null;
         }

         if (computePool == null) {
            computePool = new ResourcePool(vCenter, clusterName+"-computeRP");
         }

         Compute nodes[] = new Compute[num];
         for (int i = 0; i < num; i++) {
            Allocation capacity = com.vmware.vhadoop.vhm.model.Allocation.zeroed();
            capacity.set(CPU, defaultCpus * vCenter.getCpuSpeed());
            capacity.set(MEMORY, defaultMem);

            Compute compute = (Compute) vCenter.createVM(clusterName+"-compute"+(computeNodesId++), capacity, computeOVA, this);
            nodes[i] = compute;

            compute.setExtraInfo("vhmInfo.serengeti.uuid", folder.name());
            /* assign it to a host */
            host.add(compute);
            /* keep it handy for future operations */
            synchronized (computeNodes) {
               /* we expose this external via various accessor methods that iterate over it */
               computeNodes.add(compute);
            }

            /* add it to the "cluster folder" and the compute node resource pool */
            folder.add(compute);
            computePool.add(compute);
            /* mark it as disabled to hadoop */
            synchronized(this) {
               disabled.put(compute.getId(), compute);
            }

            /* add this to the vApp so that we've a solid accounting for everything */
            Serengeti.this.add(compute);
         }

         return nodes;
      }


      @Override
      protected Allocation getDesiredAllocation() {
         /* we don't want to allocate anything on our own behalf based on tasks */
         Allocation desired = super.getDesiredAllocation();
         if (desired.getDuration() > maxLatency) {
            desired.setDuration(maxLatency);
         }

         return desired;
      }

      /**
       * Makes the node available for running tasks
       * @param hostname
       * @return null on success, error detail otherwise
       */
      public synchronized String enable(String hostname) {
         String id = getComputeIdFromHostname(hostname);
         if (id == null) {
            return UNKNOWN_HOSTNAME_FOR_COMPUTE_NODE;
         }

         Compute node = disabled.remove(id);
         if (node == null) {
            if (enabled.containsKey(id)) {
               return COMPUTE_NODE_ALREADY_IN_TARGET_STATE;
            }

            return COMPUTE_NODE_IN_UNDETERMINED_STATE;
         }

         _log.info(name()+" enabling compute node "+id);

         enabled.put(id, node);

         /* revise our job distribution if needed */
         reviseResourceUsage();

         /* return null on success */
         return null;
      }

      /**
       * Stops the task running on the specified compute node
       * and disables it from further consideration
       * @param hostname
       * @return null on success, error detail otherwise
       */
      public synchronized String disable(String hostname) {
         String id = getComputeIdFromHostname(hostname);
         if (id == null) {
            return UNKNOWN_HOSTNAME_FOR_COMPUTE_NODE;
         }

         Compute node = enabled.remove(id);
         if (node == null) {
            if (disabled.containsKey(id)) {
               return COMPUTE_NODE_ALREADY_IN_TARGET_STATE;
            }

            return COMPUTE_NODE_IN_UNDETERMINED_STATE;
         }

         Compute old = disabled.put(id, node);
         if (old == null) {
            _log.info(name()+" disabled compute node "+id);
         }

         disabled.put(id, node);

         /* return null on success */
         return null;
      }


      /**
       * Callback for tasks to report completion
       * @param task
       */
      void reportEndOfTask(Process task) {
         reviseResourceUsage();
      }
   }
   /***************** Master Node end *****************************************************/
}