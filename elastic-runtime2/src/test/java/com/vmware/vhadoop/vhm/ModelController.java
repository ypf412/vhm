package com.vmware.vhadoop.vhm;

import java.util.logging.Logger;

import com.vmware.vhadoop.api.vhm.HadoopActions;
import com.vmware.vhadoop.api.vhm.VCActions;
import com.vmware.vhadoop.util.ThreadLocalCompoundStatus;
import com.vmware.vhadoop.vhm.hadoop.ModelHadoopAdaptor;
import com.vmware.vhadoop.vhm.model.scenarios.Serengeti;
import com.vmware.vhadoop.vhm.rabbit.ModelRabbitAdaptor;
import com.vmware.vhadoop.vhm.vc.ModelVcAdapter;

public class ModelController extends BootstrapMain {
   private static Logger _log = Logger.getLogger(ModelController.class.getName());

   Serengeti vApp;

   public ModelController(Serengeti serengeti) {
      this.vApp = serengeti;
   }

   public ModelController(final String configFileName, final String logFileName, Serengeti serengeti) {
      super(configFileName, logFileName);
      this.vApp = serengeti;
   }

   @Override
   public VCActions getVCInterface(ThreadLocalCompoundStatus tlcs) {
      return new ModelVcAdapter(vApp.getVCenter());
   }

   @Override
   HadoopActions getHadoopInterface(ThreadLocalCompoundStatus tlcs) {
      return new ModelHadoopAdaptor(vApp.getVCenter(), tlcs);
   }

   @Override
   ModelRabbitAdaptor getRabbitInterface() {
      return new ModelRabbitAdaptor(vApp.getVCenter());
   }
}
